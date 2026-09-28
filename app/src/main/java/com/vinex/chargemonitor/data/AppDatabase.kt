package com.vinex.chargemonitor.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [PowerSource::class, ChargeSession::class, Sample::class],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sourceDao(): PowerSourceDao
    abstract fun sessionDao(): ChargeSessionDao
    abstract fun sampleDao(): SampleDao

    companion object {
        const val NAME = "charge-monitor.db"

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, NAME)
                .addCallback(SeedCallback)
                .build()
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
