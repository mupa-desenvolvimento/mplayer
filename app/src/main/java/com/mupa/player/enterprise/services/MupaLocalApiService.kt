package com.mupa.player.enterprise.services

import android.app.Service
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.IBinder
import com.mupa.player.enterprise.managers.DeviceIdentityManager
import com.mupa.player.enterprise.managers.MupaCommandCenter
import com.mupa.player.enterprise.managers.SettingsManager
import com.mupa.player.enterprise.utils.NetworkInfoProvider
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.Charset
import java.util.concurrent.Executors

class MupaLocalApiService : Service() {
    private val executor = Executors.newCachedThreadPool()
    @Volatile
    private var serverSocket: ServerSocket? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (serverSocket == null) {
            startServer()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { serverSocket?.close() }
        serverSocket = null
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun startServer() {
        executor.execute {
            val socket = runCatching { ServerSocket(PORT, 50, InetAddress.getByName("127.0.0.1")) }.getOrNull()
                ?: return@execute
            serverSocket = socket
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: continue
                executor.execute { handleClient(client) }
            }
        }
    }

    private fun handleClient(socket: Socket) {
        socket.use { s ->
            val input = BufferedInputStream(s.getInputStream())
            val output = BufferedOutputStream(s.getOutputStream())

            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0].uppercase()
            val path = parts[1]

            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = readLine(input) ?: break
                if (line.isBlank()) break
                val idx = line.indexOf(':')
                if (idx > 0) {
                    headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
                }
            }

            val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
            val body = if (contentLength > 0) {
                val buf = ByteArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val r = input.read(buf, read, contentLength - read)
                    if (r <= 0) break
                    read += r
                }
                String(buf, 0, read, Charsets.UTF_8)
            } else {
                ""
            }

            val response = when (method) {
                "GET" -> handleGet(path)
                "POST" -> handlePost(path, body)
                else -> httpJson(405, JSONObject().put("error", "method_not_allowed"))
            }

            writeResponse(output, response.first, response.second)
        }
    }

    private fun handleGet(path: String): Pair<Int, String> {
        return when (path) {
            "/status" -> {
                val deviceId = DeviceIdentityManager(applicationContext).getCachedId()
                val ip = NetworkInfoProvider.getIpAddress(applicationContext)
                val online = isOnline()
                val kiosk = SettingsManager(applicationContext).getKioskModeCached()
                val obj = JSONObject()
                    .put("device", deviceId)
                    .put("online", online)
                    .put("kiosk", kiosk)
                    .put("ip", ip)
                httpJson(200, obj)
            }
            "/device" -> {
                val id = DeviceIdentityManager(applicationContext).getCachedId()
                val obj = JSONObject().put("device_id", id)
                httpJson(200, obj)
            }
            else -> httpJson(404, JSONObject().put("error", "not_found"))
        }
    }

    private fun handlePost(path: String, body: String): Pair<Int, String> {
        return when (path) {
            "/command", "/lock", "/unlock", "/reload", "/kiosk" -> {
                val json = when (path) {
                    "/lock" -> JSONObject().put("comando", "lock_device").toString()
                    "/unlock" -> JSONObject().put("comando", "unlock_device").toString()
                    "/reload" -> JSONObject().put("comando", "reset_app").toString()
                    "/kiosk" -> body.ifBlank { JSONObject().put("comando", "fullscreen").toString() }
                    else -> body
                }

                val ack = MupaCommandCenter.dispatch(json)
                if (ack == null) {
                    httpJson(503, JSONObject().put("error", "player_not_ready"))
                } else {
                    val obj = JSONObject()
                        .put("device_id", ack.device_id)
                        .put("status", ack.status)
                        .put("comando", ack.comando)
                        .put("timestamp", ack.executado_em)
                        .put("detalhe", ack.detalhe)
                    httpJson(200, obj)
                }
            }
            else -> httpJson(404, JSONObject().put("error", "not_found"))
        }
    }

    private fun isOnline(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java)
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun httpJson(code: Int, obj: JSONObject): Pair<Int, String> {
        return code to obj.toString()
    }

    private fun writeResponse(out: BufferedOutputStream, code: Int, body: String) {
        val reason = when (code) {
            200 -> "OK"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            503 -> "Service Unavailable"
            else -> "OK"
        }
        val bytes = body.toByteArray(Charsets.UTF_8)
        val header = buildString {
            append("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Content-Length: ").append(bytes.size).append("\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        out.write(header.toByteArray(Charset.forName("UTF-8")))
        out.write(bytes)
        out.flush()
    }

    private fun readLine(input: BufferedInputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
            if (sb.length > 8192) break
        }
        return sb.toString()
    }

    companion object {
        private const val PORT = 8989
    }
}
