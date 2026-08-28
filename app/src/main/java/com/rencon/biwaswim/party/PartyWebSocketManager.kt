package com.rencon.biwaswim.party

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener


class PartyWebSocketManager {
    @Serializable
    data class PartyCreateResponse(
        val error: String?,
        val roomId: String?,
        val success: Boolean?
    )
    private val WEBSOCKET_SERVER_ORIGIN = "http://192.168.1.82:8787"
    var APP_ID: String? = null
    var wsConnection: WebSocket? = null
    private val JSON: MediaType = "application/json; charset=utf-8".toMediaType()
    fun join(roomId: String){
        val client = OkHttpClient()

        val request = Request.Builder()
            .url(WEBSOCKET_SERVER_ORIGIN.replace("http://", "ws://"))
            .build()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                wsConnection = webSocket
            }

            override fun onMessage(webSocket: WebSocket, text: String) {

            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                t.printStackTrace()
            }
        }

        val webSocket = client.newWebSocket(request, listener)
    }


    fun leave(){
        wsConnection?.close(1000, null)
        wsConnection = null
    }
    fun create(): String {
        if(APP_ID == null) return ""
        val json: String = """
            clientId: $APP_ID
        """.trimIndent()
        val body = RequestBody.create(JSON, json);
        val request: Request = Request.Builder()
            .url("$WEBSOCKET_SERVER_ORIGIN/create")
            .post(body)
            .build()

        val client = OkHttpClient()

        val response = client.newCall(request).execute()

        if (!response.isSuccessful) {
            throw Exception("Unexpected code $response")
        }

        val data = response.body.string()
        val partyCreateResponse = Json.decodeFromString< PartyCreateResponse>(data)
        if(partyCreateResponse.success == true) {
            return partyCreateResponse.roomId ?: ""
        }
        throw Exception(partyCreateResponse.error ?: "Failed to create party")
    }
}