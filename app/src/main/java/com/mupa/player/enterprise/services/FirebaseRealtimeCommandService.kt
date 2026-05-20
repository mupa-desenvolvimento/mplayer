package com.mupa.player.enterprise.services

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

data class DeviceCommand(
    val comando: String?,
    val pacote: String?,
    val codbar: String?,
    val ip_server: String?,
    val url: String?,
    val timestamp: Long,
)

data class CommandAck(
    val device_id: String,
    val status: String,
    val comando: String,
    val executado_em: Long,
    val detalhe: String? = null,
)

class FirebaseRealtimeCommandService(
    private val context: Context,
    private val deviceId: String,
    private val onCommand: (DeviceCommand) -> Unit,
    private val onAck: (CommandAck) -> Unit,
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private var commandsRef: DatabaseReference? = null
    private var dispositivosRef: DatabaseReference? = null
    private var childListener: ChildEventListener? = null
    private var valueListener: ValueEventListener? = null
    private var poller: ScheduledExecutorService? = null

    fun start(): Boolean {
        if (deviceId.isBlank()) return false
        if (commandsRef != null || dispositivosRef != null) return true

        val app = FirebaseApp.initializeApp(context) ?: return false
        val db = FirebaseDatabase.getInstance(app)

        commandsRef = db.getReference("commands").child(deviceId)
        dispositivosRef = db.getReference("dispositivos").child(deviceId)

        val cl = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                handleSnapshot(snapshot)
            }

            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {
                handleSnapshot(snapshot)
            }

            override fun onChildRemoved(snapshot: DataSnapshot) = Unit
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) = Unit
            override fun onCancelled(error: DatabaseError) = Unit
        }

        val vl = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!snapshot.exists()) return
                handleSnapshot(snapshot)
            }

            override fun onCancelled(error: DatabaseError) = Unit
        }

        childListener = cl
        valueListener = vl

        commandsRef?.addChildEventListener(cl)
        commandsRef?.addValueEventListener(vl)
        dispositivosRef?.addValueEventListener(vl)

        val base = app.options.databaseUrl?.trim().orEmpty().ifBlank { db.reference.toString() }
        if (base.isNotBlank()) startPolling(base)
        return true
    }

    fun stop() {
        val cl = childListener
        val vl = valueListener

        commandsRef?.let { ref ->
            if (cl != null) ref.removeEventListener(cl)
            if (vl != null) ref.removeEventListener(vl)
        }
        dispositivosRef?.let { ref ->
            if (vl != null) ref.removeEventListener(vl)
        }

        childListener = null
        valueListener = null
        commandsRef = null
        dispositivosRef = null

        poller?.shutdownNow()
        poller = null
    }

    fun sendAck(ack: CommandAck) {
        val app = FirebaseApp.initializeApp(context) ?: return
        val db = FirebaseDatabase.getInstance(app)
        val ref = db.getReference("device_logs").child(deviceId).push()
        val map = hashMapOf<String, Any?>(
            "device_id" to ack.device_id,
            "status" to ack.status,
            "comando" to ack.comando,
            "executado_em" to ack.executado_em,
            "detalhe" to ack.detalhe,
        )
        ref.setValue(map)
        onAck(ack)
    }

    private fun handleSnapshot(snapshot: DataSnapshot) {
        val cmd = parseSnapshot(snapshot) ?: return
        handleCommand(cmd)
    }

    private fun parseSnapshot(snapshot: DataSnapshot): DeviceCommand? {
        val value = snapshot.value ?: return null

        val obj = when (value) {
            is Map<*, *> -> JSONObject(value)
            is String -> runCatching { JSONObject(value) }.getOrNull()
            else -> null
        } ?: return null

        val comando = obj.optString("comando", "").ifBlank { null }
        if (comando.isNullOrBlank()) return null

        val timestamp = when (val t = obj.opt("timestamp")) {
            is Number -> t.toLong()
            is String -> t.trim().toLongOrNull() ?: 0L
            else -> 0L
        }

        return DeviceCommand(
            comando = comando,
            pacote = obj.optString("pacote", "").ifBlank { null },
            codbar = obj.optString("codbar", "").ifBlank { null },
            ip_server = obj.optString("ip_server", "").ifBlank { null },
            url = obj.optString("url", "").ifBlank { null },
            timestamp = timestamp,
        )
    }

    private fun handleCommand(cmd: DeviceCommand) {
        if (!shouldProcess(cmd)) return
        persistProcessed(cmd)
        onCommand(cmd)
    }

    private fun startPolling(databaseUrl: String) {
        if (poller != null) return
        val normalizedBase = databaseUrl.trim().trimEnd('/')
        poller = Executors.newSingleThreadScheduledExecutor()
        poller?.scheduleAtFixedRate(
            {
                pollOnce(normalizedBase, "commands")
                pollOnce(normalizedBase, "dispositivos")
            },
            0L,
            2L,
            TimeUnit.SECONDS,
        )
    }

    private fun pollOnce(baseUrl: String, root: String) {
        val url = "$baseUrl/$root/$deviceId.json"
        val body = httpGet(url) ?: return
        if (body.isBlank() || body.trim() == "null") return

        val obj = runCatching { JSONObject(body) }.getOrNull()
            ?: runCatching { JSONObject(JSONObject().put("wrapped", body).getString("wrapped")) }.getOrNull()
            ?: return

        val cmd = parseJsonObject(obj) ?: return
        handleCommand(cmd)
    }

    private fun parseJsonObject(obj: JSONObject): DeviceCommand? {
        val comando = obj.optString("comando", "").ifBlank { null }
        if (comando.isNullOrBlank()) return null

        val timestamp = when (val t = obj.opt("timestamp")) {
            is Number -> t.toLong()
            is String -> t.trim().toLongOrNull() ?: 0L
            else -> 0L
        }

        return DeviceCommand(
            comando = comando,
            pacote = obj.optString("pacote", "").ifBlank { null },
            codbar = obj.optString("codbar", "").ifBlank { null },
            ip_server = obj.optString("ip_server", "").ifBlank { null },
            url = obj.optString("url", "").ifBlank { null },
            timestamp = timestamp,
        )
    }

    private fun httpGet(url: String): String? {
        return runCatching {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 2500
                readTimeout = 2500
            }
            try {
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                if (stream == null) return@runCatching null
                BufferedReader(InputStreamReader(stream)).use { it.readText() }
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }

    private fun shouldProcess(cmd: DeviceCommand): Boolean {
        val lastTimestamp = prefs.getLong(KEY_LAST_TIMESTAMP, 0L)
        if (cmd.timestamp > 0 && cmd.timestamp > lastTimestamp) return true

        val hash = stableHash(cmd)
        val lastHash = prefs.getString(KEY_LAST_HASH, null)
        return hash != lastHash
    }

    private fun persistProcessed(cmd: DeviceCommand) {
        prefs.edit()
            .putLong(KEY_LAST_TIMESTAMP, maxOf(prefs.getLong(KEY_LAST_TIMESTAMP, 0L), cmd.timestamp))
            .putString(KEY_LAST_HASH, stableHash(cmd))
            .apply()
    }

    private fun stableHash(cmd: DeviceCommand): String {
        val obj = JSONObject()
        obj.put("comando", cmd.comando ?: "")
        obj.put("pacote", cmd.pacote ?: "")
        obj.put("codbar", cmd.codbar ?: "")
        obj.put("ip_server", cmd.ip_server ?: "")
        obj.put("url", cmd.url ?: "")
        obj.put("timestamp", cmd.timestamp)
        return obj.toString()
    }

    companion object {
        private const val PREFS_NAME = "mupa_firebase_commands"
        private const val KEY_LAST_TIMESTAMP = "lastTimestamp"
        private const val KEY_LAST_HASH = "lastCommandHash"
    }
}
