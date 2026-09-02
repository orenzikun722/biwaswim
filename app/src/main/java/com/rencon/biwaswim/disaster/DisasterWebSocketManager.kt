package com.rencon.biwaswim.disaster

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.edit
import com.rencon.biwaswim.disaster.model.DisasterAlertMessage
import com.rencon.biwaswim.disaster.model.DisasterEEWArea
import com.rencon.biwaswim.disaster.model.DisasterEarthquake
import com.rencon.biwaswim.disaster.model.DisasterHypocenter
import com.rencon.biwaswim.disaster.model.DisasterIssue
import com.rencon.biwaswim.disaster.model.DisasterPayloadData
import com.rencon.biwaswim.disaster.model.disasterJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * 災害配信用WebSocketサーバーに常時接続し、緊急地震速報や地震情報を購読・配信するマネージャー
 */
class DisasterWebSocketManager private constructor(context: Context) {

    interface DisasterAlertListener {
        /** WebSocket 接続状態が変化したとき */
        fun onConnectionStatusChanged(isConnected: Boolean, statusMessage: String) {}

        /** 災害情報（EEW / 地震情報）を受信したとき（Mainスレッドで呼ばれます） */
        fun onDisasterAlertReceived(alert: DisasterAlertMessage)

        /** エラー発生時 */
        fun onDisasterError(errorMessage: String) {}
    }

    private val appContext = context.applicationContext
    private val prefs: SharedPreferences = appContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    private val listeners = CopyOnWriteArrayList<DisasterAlertListener>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.IO + Job())

    private var webSocket: WebSocket? = null
    private var isManualDisconnect = false
    private var reconnectJob: Job? = null
    private var retryCount = 0

    var isConnected: Boolean = false
        private set

    private val okHttpClient = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    var serverUrl: String
        get() = prefs.getString(KEY_WS_URL, DEFAULT_WS_URL) ?: DEFAULT_WS_URL
        set(value) {
            prefs.edit { putString(KEY_WS_URL, value.trim()) }
            reconnect()
        }

    var isEnabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) {
            prefs.edit { putBoolean(KEY_ENABLED, value) }
            if (value) {
                connect()
            } else {
                disconnect()
            }
        }

    fun addListener(listener: DisasterAlertListener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
            listener.onConnectionStatusChanged(
                isConnected,
                if (isConnected) "災害配信サーバー接続中" else "未接続"
            )
        }
    }

    fun removeListener(listener: DisasterAlertListener) {
        listeners.remove(listener)
    }

    /**
     * 災害配信WebSocketに接続を開始する
     */
    fun connect() {
        if (!isEnabled) {
            Log.d(TAG, "Disaster alert is disabled by user setting.")
            return
        }
        if (isConnected || webSocket != null) {
            Log.d(TAG, "Already connected or connecting.")
            return
        }

        isManualDisconnect = false
        reconnectJob?.cancel()

        scope.launch {
            try {
                val url = serverUrl
                Log.d(TAG, "Connecting to disaster WebSocket: $url")
                notifyStatusChanged(false, "接続試行中: $url")

                val request = Request.Builder()
                    .url(url)
                    .build()

                val listener = object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        Log.i(TAG, "Disaster WebSocket opened: ${response.message}")
                        this@DisasterWebSocketManager.webSocket = webSocket
                        isConnected = true
                        retryCount = 0
                        notifyStatusChanged(true, "災害配信サーバー接続中")
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        Log.d(TAG, "Received disaster raw message: $text")
                        handleIncomingMessage(text)
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        Log.d(TAG, "Disaster WebSocket closing: $code / $reason")
                        webSocket.close(1000, null)
                        handleDisconnect("切断処理中")
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        Log.d(TAG, "Disaster WebSocket closed: $code / $reason")
                        handleDisconnect("切断されました")
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        Log.e(TAG, "Disaster WebSocket failure: ${t.message}", t)
                        handleDisconnect("接続エラー: ${t.localizedMessage ?: "通信に失敗しました"}")
                    }
                }

                okHttpClient.newWebSocket(request, listener)
            } catch (e: Exception) {
                Log.e(TAG, "Exception during connect(): ${e.message}", e)
                handleDisconnect("URLエラーまたは例外: ${e.localizedMessage}")
            }
        }
    }

    /**
     * メッセージ受信時のパース＆通知処理
     */
    private fun handleIncomingMessage(rawJson: String) {
        try {
            val alert = disasterJson.decodeFromString<DisasterAlertMessage>(rawJson)
            Log.i(TAG, "Successfully parsed disaster alert: ${alert.summary ?: alert.type}")
            mainHandler.post {
                for (listener in listeners) {
                    listener.onDisasterAlertReceived(alert)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse disaster json: '$rawJson'", e)
        }
    }

    private fun handleDisconnect(reason: String) {
        webSocket = null
        isConnected = false
        notifyStatusChanged(false, reason)

        if (!isManualDisconnect && isEnabled) {
            scheduleReconnect()
        }
    }

    /**
     * 自動再接続のスケジューリング（指数バックオフ: 3秒〜最長30秒）
     */
    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            val delayMs = (3000L * (1 shl retryCount.coerceAtMost(4))).coerceAtMost(30000L)
            retryCount++
            Log.d(TAG, "Scheduling reconnect in ${delayMs / 1000}s (retry=$retryCount)...")
            notifyStatusChanged(false, "${delayMs / 1000}秒後に再接続します")
            delay(delayMs)
            if (isActive && !isManualDisconnect && isEnabled && !isConnected) {
                connect()
            }
        }
    }

    fun disconnect() {
        isManualDisconnect = true
        reconnectJob?.cancel()
        webSocket?.close(1000, "App requested disconnect")
        webSocket = null
        isConnected = false
        notifyStatusChanged(false, "停止中")
    }

    fun reconnect() {
        disconnect()
        connect()
    }

    private fun notifyStatusChanged(connected: Boolean, status: String) {
        mainHandler.post {
            for (listener in listeners) {
                listener.onConnectionStatusChanged(connected, status)
            }
        }
    }

    /**
     * 動作テスト・シミュレーション用の擬似EEW/地震情報発信
     */
    fun simulateAlert(isEEW: Boolean = true, scale: Int = 40) {
        val nowStr = SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.JAPAN).format(Date())
        val alert = if (isEEW) {
            val scaleStr = DisasterAlertMessage.formatScale(scale)
            DisasterAlertMessage(
                type = "eew",
                matchedScale = scale,
                scaleHuman = scaleStr,
                summary = "【緊急地震速報】最大予想震度$scaleStr / 震源: 滋賀県北部 M5.4 (第1報)",
                timestamp = nowStr,
                data = DisasterPayloadData(
                    id = "sim_eew_${System.currentTimeMillis()}",
                    code = 556,
                    cancelled = false,
                    issue = DisasterIssue(
                        source = "気象庁",
                        time = nowStr,
                        type = "Focus",
                        serial = "1"
                    ),
                    earthquake = DisasterEarthquake(
                        originTime = nowStr,
                        hypocenter = DisasterHypocenter(
                            name = "滋賀県北部（琵琶湖沖）",
                            depth = 10,
                            magnitude = 5.4,
                            latitude = 35.3,
                            longitude = 136.1
                        ),
                        maxScale = scale
                    ),
                    areas = listOf(
                        DisasterEEWArea(pref = "滋賀県", name = "滋賀県北部", scaleFrom = scale, scaleTo = scale),
                        DisasterEEWArea(pref = "滋賀県", name = "滋賀県南部", scaleFrom = (scale - 10).coerceAtLeast(10), scaleTo = scale),
                        DisasterEEWArea(pref = "京都府", name = "京都府南部", scaleFrom = (scale - 10).coerceAtLeast(10), scaleTo = (scale - 5).coerceAtLeast(10)),
                        DisasterEEWArea(pref = "福井県", name = "福井県嶺南", scaleFrom = (scale - 10).coerceAtLeast(10), scaleTo = scale)
                    )
                )
            )
        } else {
            val scaleStr = DisasterAlertMessage.formatScale(scale)
            DisasterAlertMessage(
                type = "quake",
                matchedScale = scale,
                scaleHuman = scaleStr,
                summary = "【地震情報】最大震度$scaleStr / 震源: 近江盆地 M4.8 深さ15km",
                timestamp = nowStr,
                data = DisasterPayloadData(
                    id = "sim_quake_${System.currentTimeMillis()}",
                    code = 551,
                    time = nowStr,
                    issue = DisasterIssue(
                        source = "気象庁",
                        time = nowStr,
                        type = "DetailScale"
                    ),
                    earthquake = DisasterEarthquake(
                        time = nowStr,
                        hypocenter = DisasterHypocenter(
                            name = "滋賀県南部",
                            depth = 15,
                            magnitude = 4.8,
                            latitude = 35.1,
                            longitude = 135.9
                        ),
                        maxScale = scale,
                        domesticTsunami = "None"
                    )
                )
            )
        }

        mainHandler.post {
            for (listener in listeners) {
                listener.onDisasterAlertReceived(alert)
            }
        }
    }

    companion object {
        private const val TAG = "DisasterWsManager"
        private const val PREF_NAME = "disaster_settings"
        private const val KEY_WS_URL = "disaster_ws_url"
        private const val KEY_ENABLED = "disaster_ws_enabled"

        // デフォルト接続先（AndroidエミュレータからのホストPC接続 10.0.2.2:8080 または 実機用localhost:8080）
        const val DEFAULT_WS_URL = "ws://100.71.64.27:8080/"

        @Volatile
        private var instance: DisasterWebSocketManager? = null

        fun getInstance(context: Context): DisasterWebSocketManager {
            return instance ?: synchronized(this) {
                instance ?: DisasterWebSocketManager(context).also { instance = it }
            }
        }
    }
}
