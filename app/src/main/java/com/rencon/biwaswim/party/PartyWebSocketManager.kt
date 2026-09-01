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
        /** パーティメンバーの名前が更新または通知されたとき */
        fun onMemberNameUpdated(clientId: String, userName: String) {}
        /** パーティのメンバー一覧が更新されたとき */
        fun onMembersUpdated(clientIds: List<String>) {}
        /** 他のパーティメンバーの位置情報が更新されたとき */
        fun onMemberLocationUpdated(clientId: String, latitude: Double, longitude: Double) {}
    }

    data object companion {
        private val WEBSOCKET_SERVER_ORIGIN = "https://biwaswim-party.kiirokun1142.workers.dev"
        var APP_ID: String? = null
        var USER_NAME: String? = null
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
                        // 接続確立時に自分のユーザー名を送信
                        if (APP_ID != null && !USER_NAME.isNullOrBlank()) {
                            webSocket.send("name,$APP_ID,$USER_NAME")
                        }
                        callback?.onConnected()
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        Log.d("PartyWebSocketManager", "Received raw WebSocket message: '$text'")
                        // サーバーから "join:<clientId>" / "leave:<clientId>" / "name:<clientId>,<userName>" / "location:<clientId>,<lat>,<lon>,[<userName>]" 形式等で届く想定
                        when {
                            text.startsWith("name,") || text.startsWith("name:") || text.startsWith("user,") || text.startsWith("user:") -> {
                                val prefix = when {
                                    text.startsWith("name,") -> "name,"
                                    text.startsWith("name:") -> "name:"
                                    text.startsWith("user,") -> "user,"
                                    else -> "user:"
                                }
                                val body = text.removePrefix(prefix)
                                val parts = body.split(",").map(String::trim)
                                if (parts.size >= 2) {
                                    val clientId = parts[0]
                                    val userName = parts[1]
                                    if (clientId.isNotEmpty() && userName.isNotEmpty()) {
                                        Log.d("PartyWebSocketManager", "Parsed member name: clientId=$clientId, userName=$userName")
                                        callback?.onMemberNameUpdated(clientId, userName)
                                    }
                                }
                            }
                            text.startsWith("join,") || text.startsWith("join:") -> {
                                val prefix = if (text.startsWith("join,")) "join," else "join:"
                                val clientId = text.removePrefix(prefix).trim()
                                Log.d("PartyWebSocketManager", "Parsed member joined: $clientId")
                                callback?.onMemberJoined(clientId)
                            }
                            text.startsWith("leave,") || text.startsWith("leave:") -> {
                                val prefix = if (text.startsWith("leave,")) "leave," else "leave:"
                                val clientId = text.removePrefix(prefix).trim()
                                Log.d("PartyWebSocketManager", "Parsed member left: $clientId")
                                callback?.onMemberLeft(clientId)
                            }
                            text.startsWith("members,") || text.startsWith("members:") -> {
                                val prefix = if (text.startsWith("members,")) "members," else "members:"
                                val clientIds = text.removePrefix(prefix).replace("\"", "").replace("[", "").replace("]", "").split(",").map(String::trim).filter(String::isNotEmpty)
                                Log.d("PartyWebSocketManager", "Parsed members list: $clientIds")
                                callback?.onMembersUpdated(clientIds)
                            }
                            text.startsWith("location,") || text.startsWith("location:") || text.startsWith("loc,") || text.startsWith("loc:") -> {
                                val prefix = when {
                                    text.startsWith("location,") -> "location,"
                                    text.startsWith("location:") -> "location:"
                                    text.startsWith("loc,") -> "loc,"
                                    else -> "loc:"
                                }
                                val body = text.removePrefix(prefix)
                                val parts = body.split(",").map(String::trim)
                                Log.d("PartyWebSocketManager", "Parsed location message body='$body', parts=$parts")
                                if (parts.size >= 3) {
                                    val clientId = parts[0]
                                    val lat = parts[1].toDoubleOrNull()
                                    val lon = parts[2].toDoubleOrNull()
                                    if (parts.size >= 4) {
                                        val userName = parts[3]
                                        if (userName.isNotEmpty()) {
                                            callback?.onMemberNameUpdated(clientId, userName)
                                        }
                                    }
                                    Log.d("PartyWebSocketManager", "Triggering onMemberLocationUpdated: clientId=$clientId, lat=$lat, lon=$lon")
                                    if (lat != null && lon != null) {
                                        callback?.onMemberLocationUpdated(clientId, lat, lon)
                                    } else {
                                        Log.w("PartyWebSocketManager", "Failed to parse lat/lon numbers from: $parts")
                                    }
                                } else {
                                    Log.w("PartyWebSocketManager", "Location message has insufficient parts (< 3): $parts")
                                }
                            }
                            else -> {
                                Log.d("PartyWebSocketManager", "Received other/unrecognized message: $text")
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
         * 自分のユーザー名を WebSocket 経由でパーティメンバーに送信する。
         * 送信フォーマット: name,<clientId>,<userName>
         */
        fun sendUserName(userName: String): Boolean {
            USER_NAME = userName
            val clientId = APP_ID
            if (clientId == null) {
                Log.w("PartyWebSocketManager", "sendUserName failed: APP_ID is null")
                return false
            }
            val ws = wsConnection
            if (ws == null) {
                Log.d("PartyWebSocketManager", "sendUserName: wsConnection is null (not connected yet)")
                return false
            }
            val message = "name,$clientId,$userName"
            val success = ws.send(message)
            Log.d("PartyWebSocketManager", "sendUserName -> '$message', success=$success")
            return success
        }

        /**
         * 現在位置を WebSocket 経由でパーティメンバーに送信する。
         * 送信フォーマット: location,<clientId>,<latitude>,<longitude>,<userName>
         */
        fun sendLocation(latitude: Double, longitude: Double): Boolean {
            val clientId = APP_ID
            if (clientId == null) {
                Log.w("PartyWebSocketManager", "sendLocation failed: APP_ID is null")
                return false
            }
            val ws = wsConnection
            if (ws == null) {
                Log.w("PartyWebSocketManager", "sendLocation failed: wsConnection is null (not joined in party)")
                return false
            }
            val name = USER_NAME ?: ""
            val message = "location,$clientId,$latitude,$longitude,$name"
            val success = ws.send(message)
            Log.d("PartyWebSocketManager", "sendLocation -> '$message', success=$success")
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