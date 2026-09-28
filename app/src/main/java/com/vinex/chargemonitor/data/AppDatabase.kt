package com.vinex.chargemonitor.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [PowerSource::class, ChargeSession::class, Sample::class, BankEvent::class],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sourceDao(): PowerSourceDao
    abstract fun sessionDao(): ChargeSessionDao
    abstract fun sampleDao(): SampleDao
    abstract fun bankEventDao(): BankEventDao

    companion object {
        const val NAME = "charge-monitor.db"

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, NAME)
                .addCallback(SeedCallback)
                .addMigrations(MIGRATION_1_2)
                .build()

        /**
         * Adds source confirmation, restart bookkeeping and bank events. Sessions recorded before
         * confirmation existed are treated as confirmed: they were counted until now, and
         * silently dropping them from every bank total would be worse than the risk that one of
         * them was a wall charge.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE charge_session ADD COLUMN sourceConfirmed INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE charge_session ADD COLUMN unpluggedAtMs INTEGER")
                db.execSQL("ALTER TABLE charge_session ADD COLUMN lastCounterUah INTEGER")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS bank_event (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, sourceId INTEGER NOT NULL, " +
                        "tMs INTEGER NOT NULL, kind TEXT NOT NULL, " +
                        "FOREIGN KEY(sourceId) REFERENCES power_source(id) ON UPDATE NO ACTION ON DELETE RESTRICT)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_bank_event_sourceId ON bank_event (sourceId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_bank_event_tMs ON bank_event (tMs)")
            }
        }
    }

    /**
     * Seeds the built-ins with explicit ids in onCreate, in the same transaction that creates the
     * schema, so no code path can ever observe a database without them.
     */
    private object SeedCallback : Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            seed(db, BuiltInSources.CHARGER_ID, "Charger", SourceKind.CHARGER, BuiltInSources.CHARGER_COLOR)
            seed(db, BuiltInSources.LAPTOP_ID, "Laptop (USB)", SourceKind.LAPTOP, BuiltInSources.LAPTOP_COLOR)
            seed(db, BuiltInSources.WIRELESS_ID, "Wireless", SourceKind.WIRELESS, BuiltInSources.WIRELESS_COLOR)
        }

        private fun seed(db: SupportSQLiteDatabase, id: Long, name: String, kind: SourceKind, color: Int) {
            db.execSQL(
                "INSERT INTO power_source (id, name, kind, ratedMah, nominalV, colorArgb, archived, builtIn) " +
                    "VALUES (?, ?, ?, NULL, 3.7, ?, 0, 1)",
                arrayOf<Any>(id, name, kind.name, color),
            )
        }
    }
}

