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
        MediaPlayLogEntity::class,
        MissingProductImageEntity::class,
        DeviceEventEntity::class,
    ],
    version = 9,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun manifestDao(): ManifestDao
    abstract fun mediaDao(): MediaDao
    abstract fun audienceSessionDao(): AudienceSessionDao
    abstract fun priceCacheDao(): PriceCacheDao
    abstract fun priceQueryEventDao(): PriceQueryEventDao
    abstract fun mediaPlayLogDao(): MediaPlayLogDao
    abstract fun missingProductImageDao(): MissingProductImageDao
    abstract fun deviceEventDao(): DeviceEventDao

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
                    .addMigrations(MIGRATION_4_5)
                    .addMigrations(MIGRATION_5_6)
                    .addMigrations(MIGRATION_6_7)
                    .addMigrations(MIGRATION_7_8)
                    // Só há dados regeneráveis aqui (cache de preço, analytics, sessões de
                    // audiência). Se o schema divergir do banco em disco — ex.: device antigo
                    // subindo direto para esta versão — recria o banco em vez de crashar.
                    .fallbackToDestructiveMigration()
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

        private val MIGRATION_4_5 =
            object : Migration(4, 5) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE price_query_events ADD COLUMN filial TEXT NOT NULL DEFAULT ''")
                }
            }

        private val MIGRATION_5_6 =
            object : Migration(5, 6) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS media_play_logs (
                          id TEXT NOT NULL PRIMARY KEY,
                          deviceDbId INTEGER NOT NULL,
                          deviceId TEXT NOT NULL,
                          mediaId TEXT NOT NULL,
                          mediaName TEXT NOT NULL,
                          mediaType TEXT NOT NULL,
                          playedAtEpochMs INTEGER NOT NULL,
                          durationSeconds INTEGER NOT NULL,
                          uploadedAtEpochMs INTEGER
                        )
                        """.trimIndent(),
                    )
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_media_play_logs_deviceId ON media_play_logs(deviceId)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_media_play_logs_mediaId ON media_play_logs(mediaId)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_media_play_logs_playedAtEpochMs ON media_play_logs(playedAtEpochMs)")
                }
            }

        private val MIGRATION_6_7 =
            object : Migration(6, 7) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS missing_product_images (
                          ean TEXT NOT NULL PRIMARY KEY,
                          companyId TEXT,
                          deviceId TEXT NOT NULL,
                          firstReportedAtEpochMs INTEGER NOT NULL,
                          uploadedAtEpochMs INTEGER
                        )
                        """.trimIndent(),
                    )
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_missing_product_images_uploadedAtEpochMs ON missing_product_images(uploadedAtEpochMs)")
                }
            }

        private val MIGRATION_7_8 =
            object : Migration(7, 8) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS device_events (
                          id TEXT NOT NULL PRIMARY KEY,
                          deviceId TEXT NOT NULL,
                          companyId TEXT,
                          filial TEXT,
                          eventType TEXT NOT NULL,
                          reason TEXT,
                          ean TEXT,
                          failCount INTEGER,
                          offlineDurationSeconds INTEGER,
                          createdAtEpochMs INTEGER NOT NULL,
                          uploadedAtEpochMs INTEGER
                        )
                        """.trimIndent(),
                    )
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_device_events_uploadedAtEpochMs ON device_events(uploadedAtEpochMs)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_device_events_createdAtEpochMs ON device_events(createdAtEpochMs)")
                }
            }
    }
}
