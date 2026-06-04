package com.mupa.player.enterprise.storage.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ManifestEntity::class,
        MediaEntity::class,
        AudienceSessionEntity::class,
        PriceCacheEntity::class,
        PriceQueryEventEntity::class,
    ],
    version = 4,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun manifestDao(): ManifestDao
    abstract fun mediaDao(): MediaDao
    abstract fun audienceSessionDao(): AudienceSessionDao
    abstract fun priceCacheDao(): PriceCacheDao
    abstract fun priceQueryEventDao(): PriceQueryEventDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "mplayer.db",
                )
                    .addMigrations(MIGRATION_1_2)
                    .addMigrations(MIGRATION_2_3)
                    .addMigrations(MIGRATION_3_4)
                    .build()
                    .also { instance = it }
            }
        }

        private val MIGRATION_1_2 =
            object : Migration(1, 2) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS audience_sessions (
                          id TEXT NOT NULL PRIMARY KEY,
                          deviceId TEXT NOT NULL,
                          faceHash TEXT NOT NULL,
                          firstSeenEpochMs INTEGER NOT NULL,
                          lastSeenEpochMs INTEGER NOT NULL,
                          viewDurationSeconds INTEGER NOT NULL,
                          lookCount INTEGER NOT NULL,
                          estimatedAge INTEGER,
                          ageRange TEXT,
                          gender TEXT,
                          confidence REAL,
                          hour INTEGER NOT NULL,
                          weekday TEXT NOT NULL,
                          contentPlaying TEXT,
                          playlist TEXT,
                          uploadedAtEpochMs INTEGER
                        )
                        """.trimIndent(),
                    )
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_audience_sessions_deviceId ON audience_sessions(deviceId)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_audience_sessions_faceHash ON audience_sessions(faceHash)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_audience_sessions_firstSeenEpochMs ON audience_sessions(firstSeenEpochMs)")
                }
            }

        private val MIGRATION_2_3 =
            object : Migration(2, 3) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS price_cache (
                          ean TEXT NOT NULL PRIMARY KEY,
                          productJson TEXT NOT NULL,
                          updatedAtEpochMs INTEGER NOT NULL
                        )
                        """.trimIndent(),
                    )
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS price_query_events (
                          id TEXT NOT NULL PRIMARY KEY,
                          deviceId TEXT NOT NULL,
                          ean TEXT NOT NULL,
                          createdAtEpochMs INTEGER NOT NULL,
                          responseTimeMs INTEGER NOT NULL,
                          fromCache INTEGER NOT NULL,
                          success INTEGER NOT NULL
                        )
                        """.trimIndent(),
                    )
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_price_query_events_deviceId ON price_query_events(deviceId)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_price_query_events_ean ON price_query_events(ean)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_price_query_events_createdAtEpochMs ON price_query_events(createdAtEpochMs)")
                }
            }

        private val MIGRATION_3_4 =
            object : Migration(3, 4) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE price_query_events ADD COLUMN uploadedAtEpochMs INTEGER")
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_price_query_events_uploadedAtEpochMs ON price_query_events(uploadedAtEpochMs)")
                }
            }
    }
}
