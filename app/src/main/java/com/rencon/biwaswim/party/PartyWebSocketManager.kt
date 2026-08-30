package com.rencon.biwaswim.party

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.net.SocketTimeoutException


class PartyWebSocketManager {
    @Serializable
    data class PartyCreateResponse(
        val error: String? = null,
        val roomId: String? = null,
        val success: Boolean? = null
    )
    @Serializable
    data class PartyCreateRequest(
        val clientId: String
    )

    /**
     * パーティ接続の状態をコールバックで通知するインターフェース。
     * コールバックはバックグラウンドスレッドから呼ばれる場合があるため、
     * UI 操作が必要な場合は呼び出し側で runOnUiThread を使うこと。
     */
    interface PartyConnectionCallback {
        /** WebSocket 接続が確立されたとき（自分自身の接続成功） */
        fun onConnected()
        /** WebSocket 接続中にエラーが発生したとき */
        fun onError(message: String)
        /** WebSocket が閉じられたとき */
        fun onClosed()
        /** 誰か（自分を含む）がパーティに参加したとき */
        fun onMemberJoined(clientId: String) {}
        /** 誰か（自分を含む）がパーティから退室したとき */
        fun onMemberLeft(clientId: String) {}
        /** パーティのメンバー一覧が更新されたとき */
        fun onMembersUpdated(clientIds: List<String>) {}
        /** 他のパーティメンバーの位置情報が更新されたとき */
        fun onMemberLocationUpdated(clientId: String, latitude: Double, longitude: Double) {}
    }

    data object companion {
        private val WEBSOCKET_SERVER_ORIGIN = "http://192.168.1.82:8787"
        var APP_ID: String? = null
        var wsConnection: WebSocket? = null

        /**
         * 指定した roomId のパーティに WebSocket で参加する。
         * 接続結果は [callback] を通して通知される。
         */
        suspend fun join(
            roomId: String,
            hostAppId: String,
            callback: PartyConnectionCallback? = null
        ) {
            withContext(Dispatchers.IO) {
                val client = OkHttpClient()

                val request = Request.Builder()
                    .url(
                        WEBSOCKET_SERVER_ORIGIN.replace(
                            "http",
                            "ws"
                        ) + "/join/$roomId?clientId=$APP_ID&hostClientId=$hostAppId"
                    )
                    .build()
                Log.d("PartyWebSocketManager", "Joining WebSocket: ${request.url}")

                val listener = object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        wsConnection = webSocket
                        Log.d("PartyWebSocketManager", "WebSocket opened: $response")
                        callback?.onConnected()
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        Log.d("PartyWebSocketManager", "Received message: $text")
                        // サーバーから "join:<clientId>" / "leave:<clientId>" / "location:<clientId>,<lat>,<lon>" 形式等で届く想定
                        when {
                            text.startsWith("join,") -> {
                                val clientId = text.removePrefix("join,").trim()
                                callback?.onMemberJoined(clientId)
                            }
                            text.startsWith("leave,") -> {
                                val clientId = text.removePrefix("leave,").trim()
                                callback?.onMemberLeft(clientId)
                            }
                            text.startsWith("members,") -> {
                                val clientIds = text.removePrefix("members,").replace("\"", "").replace("[", "").replace("]", "").split(",").map(String::trim).filter(String::isNotEmpty)

                                callback?.onMembersUpdated(clientIds)
                            }
                            text.startsWith("location,") || text.startsWith("loc,") -> {
                                val prefix = if (text.startsWith("location,")) "location," else "loc,"
                                val body = text.removePrefix(prefix)
                                val parts = body.split(",").map(String::trim)
                                if (parts.size >= 3) {
                                    val clientId = parts[0]
                                    val lat = parts[1].toDoubleOrNull()
                                    val lon = parts[2].toDoubleOrNull()
                                    if (lat != null && lon != null) {
                                        callback?.onMemberLocationUpdated(clientId, lat, lon)
                                    }
                                }
                            }
                        }
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        Log.d("PartyWebSocketManager", "WebSocket closing: $code / $reason")
                        webSocket.close(1000, null)
                        wsConnection = null
                        callback?.onClosed()
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        Log.d("PartyWebSocketManager", "WebSocket closed: $code / $reason")
                        wsConnection = null
                    }

                    override fun onFailure(
                        webSocket: WebSocket,
                        t: Throwable,
                        response: Response?
                    ) {
                        Log.e("PartyWebSocketManager", "WebSocket failure: $t")
                        t.printStackTrace()
                        wsConnection = null
                        val message = when (t) {
                            is SocketTimeoutException -> "接続がタイムアウトしました"
                            is java.net.ConnectException -> "サーバーに接続できませんでした"
                            else -> t.localizedMessage ?: "不明なエラー"
                        }
                        callback?.onError(message)
                    }
                }

                client.newWebSocket(request, listener)
            }
        }

        /**
         * 現在位置を WebSocket 経由でパーティメンバーに送信する。
         * 送信フォーマット: location,<clientId>,<latitude>,<longitude>
         */
        fun sendLocation(latitude: Double, longitude: Double): Boolean {
            val clientId = APP_ID ?: return false
            val message = "location,$clientId,$latitude,$longitude"
            val success = wsConnection?.send(message) ?: false
            if (success) {
                Log.d("PartyWebSocketManager", "Sent location successfully: $message")
            } else {
                Log.w("PartyWebSocketManager", "Failed to send location (wsConnection is null or closed)")
            }
            return success
        }

        suspend fun leave() {
            withContext(Dispatchers.IO) {
                wsConnection?.close(1000, null)
                wsConnection = null
            }
        }

        /**
         * パーティルームを作成し、作成された roomId を返す。
         * 失敗した場合は例外をスローする。
         */
        suspend fun create(): String {
            return withContext(Dispatchers.IO) {
                if (APP_ID == null) throw IllegalStateException("APP_ID が未設定です")

                val jsonBody = PartyCreateRequest(clientId = APP_ID ?: "")
                val stringBody: String = Json.encodeToString(jsonBody)

                val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
                val body: RequestBody = stringBody.toRequestBody(mediaType)
                val request: Request = Request.Builder()
                    .url("$WEBSOCKET_SERVER_ORIGIN/create")
                    .post(body)
                    .build()

                val client = OkHttpClient()

                // execute() で同期実行し、レスポンスを正しく取得する
                val response = client.newCall(request).execute()
                response.use {
                    if (!it.isSuccessful) {
                        Log.e("PartyWebSocketManager", "create error: ${it.code}")
                        throw Exception("サーバーエラー: ${it.code}")
                    }
                    val data = it.body?.string() ?: throw Exception("レスポンスが空です")
                    Log.d("PartyWebSocketManager", "create response: $data")
                    val partyCreateResponse = Json.decodeFromString<PartyCreateResponse>(data)
                    if (partyCreateResponse.success == true) {
                        return@withContext partyCreateResponse.roomId
                            ?: throw Exception("roomId が取得できませんでした")
                    }
                    throw Exception(partyCreateResponse.error ?: "パーティの作成に失敗しました")
                }
            }
        }
    }

}