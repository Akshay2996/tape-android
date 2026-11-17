package com.tape.measure.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [MeasurementEntity::class],
    version = 3,
    exportSchema = true,
)
abstract class TapeDatabase : RoomDatabase() {
    abstract fun measurementDao(): MeasurementDao

    companion object {
        /**
         * v1 → v2: replace the fabricated `confidence` column with real
         * honest-accuracy columns.
         *
         * Written as a real migration rather than relying on destructive fallback
         * because users' saved measurements are the one thing in this app that
         * can't be regenerated — a room they measured last week may no longer be
         * accessible to them.
         *
         * Uses the create/copy/drop/rename dance rather than ALTER TABLE ...
         * DROP COLUMN: that syntax needs SQLite 3.35+, which ships from Android 14
         * (API 34), and Tape's minSdk is 24. On an Android 7–13 device the direct
         * form would throw at upgrade time.
         *
         * Existing rows get grade UNKNOWN and tolerance 0. That renders as
         * "Not recorded" in the UI rather than a fabricated figure. The old
         * `confidence` value (a 0.9/0.5/0.1 relabelling of TrackingState) is
         * dropped rather than migrated forward — it carried no real information.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE measurements_new (
                        id TEXT NOT NULL PRIMARY KEY,
                        distanceMeters REAL NOT NULL,
                        unit TEXT NOT NULL,
                        label TEXT,
                        toleranceMeters REAL NOT NULL DEFAULT 0,
                        accuracyGrade TEXT NOT NULL DEFAULT 'UNKNOWN',
                        createdAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO measurements_new
                        (id, distanceMeters, unit, label, toleranceMeters, accuracyGrade, createdAt)
                    SELECT id, distanceMeters, unit, label, 0, 'UNKNOWN', createdAt
                    FROM measurements
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE measurements")
                db.execSQL("ALTER TABLE measurements_new RENAME TO measurements")
            }
        }

        /**
         * v2 → v3: saved measurements can be shapes, not only lines.
         *
         * `kind` names the tool; `readings` holds every figure the shape
         * reported. Existing rows are all lines: they get kind LINE and no
         * readings, and keep displaying from their length columns.
         *
         * Plain ADD COLUMN works on every SQLite Android has shipped, so no
         * table rebuild is needed here.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE measurements ADD COLUMN kind TEXT NOT NULL DEFAULT 'LINE'")
                db.execSQL("ALTER TABLE measurements ADD COLUMN readings TEXT")
            }
        }
    }
}
