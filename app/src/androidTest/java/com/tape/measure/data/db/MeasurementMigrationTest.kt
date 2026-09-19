package com.tape.measure.data.db

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves MIGRATION_1_2 preserves saved measurements.
 *
 * Why this test exists
 * ────────────────────
 * Saved measurements are the only data in Tape that cannot be regenerated — the
 * wall someone measured last week may not be reachable any more. The v2 schema
 * change swapped `confidence` for `toleranceMeters` + `accuracyGrade`, and the
 * migration rebuilds the table to do it (SQLite < 3.35 has no DROP COLUMN, and
 * minSdk 24 means Android 7 devices are in scope).
 *
 * A table rebuild that silently drops rows would look identical to a working one
 * in manual testing — you'd only notice if you happened to have saved data on the
 * device you upgraded. Hence: automated proof.
 *
 * The v1 table is created with raw DDL matching the v1 entity exactly
 * (id/distanceMeters/unit/label/confidence/createdAt), because schema export was
 * not enabled when v1 shipped, so no v1 JSON exists to feed MigrationTestHelper.
 */
@RunWith(AndroidJUnit4::class)
class MeasurementMigrationTest {

    private companion object {
        const val TEST_DB = "migration-test-db"

        /** Exactly the DDL Room generated for schema v1. */
        const val V1_DDL = """
            CREATE TABLE IF NOT EXISTS `measurements` (
                `id` TEXT NOT NULL,
                `distanceMeters` REAL NOT NULL,
                `unit` TEXT NOT NULL,
                `label` TEXT,
                `confidence` REAL NOT NULL,
                `createdAt` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
        """
    }

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        TapeDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate1To2_preservesExistingMeasurements() {
        // ── Arrange: a v1 database with real saved rows.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(TEST_DB)

        val v1 = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(
            context.getDatabasePath(TEST_DB), null,
        )
        v1.execSQL(V1_DDL)
        v1.execSQL("PRAGMA user_version = 1")
        v1.execSQL(
            "INSERT INTO measurements VALUES ('id-1', 2.5, 'M', 'Doorway', 0.9, 1000)"
        )
        v1.execSQL(
            "INSERT INTO measurements VALUES ('id-2', 0.82, 'CM', NULL, 0.5, 2000)"
        )
        v1.close()

        // ── Act: run the real migration.
        val db = helper.runMigrationsAndValidate(
            TEST_DB,
            2,
            true,
            TapeDatabase.MIGRATION_1_2,
        )

        // ── Assert: both rows survived, with values intact.
        db.query("SELECT id, distanceMeters, unit, label, toleranceMeters, accuracyGrade, createdAt FROM measurements ORDER BY createdAt")
            .use { c ->
                assertEquals("migration must not drop saved measurements", 2, c.count)

                assertTrue(c.moveToFirst())
                assertEquals("id-1", c.getString(0))
                assertEquals(2.5f, c.getFloat(1), 1e-6f)
                assertEquals("M", c.getString(2))
                assertEquals("Doorway", c.getString(3))
                assertEquals(
                    "legacy rows must get a zero tolerance, not an invented one",
                    0f, c.getFloat(4), 1e-6f,
                )
                assertEquals(
                    "legacy rows must be marked UNKNOWN, not back-filled with a fake grade",
                    "UNKNOWN", c.getString(5),
                )
                assertEquals(1000L, c.getLong(6))

                assertTrue(c.moveToNext())
                assertEquals("id-2", c.getString(0))
                assertEquals(0.82f, c.getFloat(1), 1e-6f)
                assertTrue("null label must stay null", c.isNull(3))
                assertEquals("UNKNOWN", c.getString(5))
            }
    }

    @Test
    fun migratedDatabaseAcceptsNewWrites() {
        // A migration that validates but leaves the table unusable for the new
        // entity shape would still pass a row-count check. Verify the DAO works.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(TEST_DB)

        val v1 = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(
            context.getDatabasePath(TEST_DB), null,
        )
        v1.execSQL(V1_DDL)
        v1.execSQL("PRAGMA user_version = 1")
        v1.execSQL("INSERT INTO measurements VALUES ('old', 1.0, 'M', 'Old', 0.9, 500)")
        v1.close()

        helper.runMigrationsAndValidate(TEST_DB, 2, true, TapeDatabase.MIGRATION_1_2)

        val db = Room.databaseBuilder(context, TapeDatabase::class.java, TEST_DB)
            .addMigrations(TapeDatabase.MIGRATION_1_2)
            .build()

        kotlinx.coroutines.runBlocking {
            db.measurementDao().insert(
                MeasurementEntity(
                    id              = "new",
                    distanceMeters  = 3.0f,
                    unit            = "M",
                    label           = "New",
                    toleranceMeters = 0.06f,
                    accuracyGrade   = "HIGH",
                    createdAt       = 9999,
                )
            )
            val all = db.measurementDao().getAll().first()
            assertEquals("old row plus new row expected", 2, all.size)

            val fresh = all.first { it.id == "new" }
            assertEquals(0.06f, fresh.toleranceMeters, 1e-6f)
            assertEquals("HIGH", fresh.accuracyGrade)

            val legacy = all.first { it.id == "old" }
            assertEquals("UNKNOWN", legacy.accuracyGrade)
        }
        db.close()
    }
}
