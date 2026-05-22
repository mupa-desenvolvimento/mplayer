package com.mupa.player.enterprise.argos

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject

data class LocalQueuedCommand(
    val commandId: String,
    val command: String,
    val paramsJson: String,
    val priority: Int,
    val status: String,
    val attempts: Int,
    val nextAttemptAt: Long,
    val createdAt: Long,
)

class LocalCommandStore(context: Context) : SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE (
              command_id TEXT PRIMARY KEY,
              command TEXT NOT NULL,
              params_json TEXT NOT NULL,
              priority INTEGER NOT NULL,
              status TEXT NOT NULL,
              attempts INTEGER NOT NULL,
              last_error TEXT,
              next_attempt_at INTEGER NOT NULL,
              created_at INTEGER NOT NULL,
              updated_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_${TABLE}_status_next ON $TABLE(status, next_attempt_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_${TABLE}_prio_created ON $TABLE(priority DESC, created_at ASC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 1) {
            onCreate(db)
        }
    }

    fun upsertPending(commands: List<ArgosPendingCommand>, now: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (c in commands) {
                val existingStatus = queryStatus(db, c.commandId)
                if (existingStatus == STATUS_SUCCESS || existingStatus == STATUS_FAILED) continue

                val values = ContentValues().apply {
                    put("command_id", c.commandId)
                    put("command", c.command)
                    put("params_json", c.params.toString())
                    put("priority", c.priority)
                    put("status", STATUS_PENDING)
                    put("attempts", 0)
                    put("last_error", null as String?)
                    put("next_attempt_at", now)
                    put("created_at", if (existingStatus == null) now else null as Long?)
                    put("updated_at", now)
                }

                db.insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_IGNORE)
                db.update(
                    TABLE,
                    ContentValues().apply {
                        put("command", c.command)
                        put("params_json", c.params.toString())
                        put("priority", c.priority)
                        put("updated_at", now)
                        if (existingStatus == null) put("created_at", now)
                    },
                    "command_id=?",
                    arrayOf(c.commandId),
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun listRunnable(now: Long, limit: Int): List<LocalQueuedCommand> {
        val db = readableDatabase
        val cur = db.query(
            TABLE,
            arrayOf(
                "command_id",
                "command",
                "params_json",
                "priority",
                "status",
                "attempts",
                "next_attempt_at",
                "created_at",
            ),
            "status IN (?,?,?) AND next_attempt_at<=?",
            arrayOf(STATUS_PENDING, STATUS_TIMEOUT, STATUS_PROCESSING, now.toString()),
            null,
            null,
            "priority DESC, created_at ASC",
            limit.toString(),
        )
        return cur.use { c ->
            val out = ArrayList<LocalQueuedCommand>(c.count)
            while (c.moveToNext()) out.add(mapRow(c))
            out
        }
    }

    fun markProcessing(commandId: String, now: Long) {
        writableDatabase.update(
            TABLE,
            ContentValues().apply {
                put("status", STATUS_PROCESSING)
                put("updated_at", now)
            },
            "command_id=?",
            arrayOf(commandId),
        )
    }

    fun markSuccess(commandId: String, now: Long) {
        writableDatabase.update(
            TABLE,
            ContentValues().apply {
                put("status", STATUS_SUCCESS)
                put("updated_at", now)
                put("last_error", null as String?)
            },
            "command_id=?",
            arrayOf(commandId),
        )
    }

    fun markFailed(commandId: String, now: Long, message: String, final: Boolean) {
        val status = if (final) STATUS_FAILED else STATUS_TIMEOUT
        writableDatabase.update(
            TABLE,
            ContentValues().apply {
                put("status", status)
                put("updated_at", now)
                put("last_error", message)
            },
            "command_id=?",
            arrayOf(commandId),
        )
    }

    fun bumpRetry(commandId: String, now: Long, error: String) {
        val db = writableDatabase
        val cur = db.query(
            TABLE,
            arrayOf("attempts"),
            "command_id=?",
            arrayOf(commandId),
            null,
            null,
            null,
            "1",
        )
        val attempts = cur.use { if (it.moveToFirst()) it.getInt(0) else 0 } + 1
        val backoff = computeBackoffMs(attempts)
        db.update(
            TABLE,
            ContentValues().apply {
                put("status", STATUS_TIMEOUT)
                put("attempts", attempts)
                put("last_error", error)
                put("updated_at", now)
                put("next_attempt_at", now + backoff)
            },
            "command_id=?",
            arrayOf(commandId),
        )
    }

    fun parseParams(command: LocalQueuedCommand): JSONObject {
        return runCatching { JSONObject(command.paramsJson) }.getOrDefault(JSONObject())
    }

    private fun queryStatus(db: SQLiteDatabase, commandId: String): String? {
        val cur = db.query(
            TABLE,
            arrayOf("status"),
            "command_id=?",
            arrayOf(commandId),
            null,
            null,
            null,
            "1",
        )
        return cur.use { if (it.moveToFirst()) it.getString(0) else null }
    }

    private fun mapRow(c: Cursor): LocalQueuedCommand {
        return LocalQueuedCommand(
            commandId = c.getString(0),
            command = c.getString(1),
            paramsJson = c.getString(2),
            priority = c.getInt(3),
            status = c.getString(4),
            attempts = c.getInt(5),
            nextAttemptAt = c.getLong(6),
            createdAt = c.getLong(7),
        )
    }

    private fun computeBackoffMs(attempts: Int): Long {
        val base = 2_000L
        val max = 5 * 60_000L
        val pow = (1 shl attempts.coerceAtMost(10))
        return (base * pow).coerceAtMost(max)
    }

    companion object {
        private const val DB_NAME = "argos_commands.db"
        private const val DB_VERSION = 1
        private const val TABLE = "argos_commands"

        const val STATUS_PENDING = "pending"
        const val STATUS_PROCESSING = "processing"
        const val STATUS_SUCCESS = "success"
        const val STATUS_FAILED = "failed"
        const val STATUS_TIMEOUT = "timeout"
    }
}

