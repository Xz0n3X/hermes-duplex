package org.hermes.duplex.network

import okhttp3.*
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

class WebSocketDuplexClient {

    private val client = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var sessionId: String? = null

    fun connect(authToken: String, serverUrl: String = "ws://192.168.1.50:8765") {
        val request = Request.Builder()
            .url(serverUrl)
            .addHeader("X-Hermes-Token", authToken)
            .addHeader("User-Agent", "HermesDuplex-Android/0.79-poc")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                sendSessionInit()
            }

            override fun onMessage(ws: WebSocket, text: String) {
                handleServerMessage(text)
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                disconnect()
            }
        })
    }

    private fun sendSessionInit() {
        val payload = JSONObject().apply {
            put("type", "session.init")
            put("id", UUID.randomUUID().toString())
            put("timestamp", System.currentTimeMillis())
            put("payload", JSONObject().apply {
                put("clientVersion", "0.79-poc")
                put("protocolVersion", "1.4-duplex")
                put("audioFormat", JSONObject().apply {
                    put("codec", "OPUS")
                    put("sampleRate", 24000)
                    put("channels", 1)
                })
            })
        }
        webSocket?.send(payload.toString())
    }

    fun sendAudio(pcmOrOpusData: ByteArray) {
        webSocket?.send(pcmOrOpusData.toByteString(0, pcmOrOpusData.size))
    }

    private fun handleServerMessage(jsonString: String) {
        val obj = JSONObject(jsonString)
        val type = obj.optString("type")
        if (type == "session.ready") {
            sessionId = obj.getJSONObject("payload").optString("sessionId")
        }
    }

    fun disconnect() {
        webSocket?.close(1000, "Client initiated disconnect")
        webSocket = null
    }
}
