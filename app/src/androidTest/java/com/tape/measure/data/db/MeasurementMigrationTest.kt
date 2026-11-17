package com.tape.measure.data.db

import android.database.sqlite.SQLiteDatabase
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
 * Proves the schema migrations preserve saved measurements.
 *
 * Why this test exists
 * ────────────────────
 * Saved measurements are the only data in Tape that cannot be regenerated — the
 * wall someone measured last week may not be reachable any more. A migration
 * that silently drops or damages rows looks identical to a working one in
 * manual testing; you'd only notice if you happened to have saved data on the
 * device you upgraded. Hence: automated proof.
 *
 * The v1 table is created with raw DDL matching the v1 entity exactly
 * (id/distanceMeters/unit/label/confidence/createdAt), because schema export was
 * not enabled when v1 shipped, so no v1 JSON exists to feed MigrationTestHelper.
 * From v2 on, the exported schema in `app/schemas/` is the starting point.
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

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        TapeDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    /** A v1 database on disk holding [rows], each a VALUES tuple. */
    private fun createV1(vararg rows: String) {
        context.deleteDatabase(TEST_DB)
        val v1 = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(TEST_DB), null)
        v1.execSQL(V1_DDL)
        v1.execSQL("PRAGMA user_version = 1")
        rows.forEach { v1.execSQL("INSERT INTO measurements VALUES $it") }
        v1.close()
    }

    @Test
    fun migrate1To2_preservesExistingMeasurements() {
        createV1(
            "('id-1', 2.5, 'M', 'Doorway', 0.9, 1000)",
            "('id-2', 0.82, 'CM', NULL, 0.5, 2000)",
        )

        val db = helper.runMigrationsAndValidate(TEST_DB, 2, true, TapeDatabase.MIGRATION_1_2)

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
    fun migrate2To3_keepsEveryLineAndMarksItAsALine() {
        // Before v3 the only thing Tape could save was a line, so that is what
        // every existing row is.
        context.deleteDatabase(TEST_DB)
        helper.createDatabase(TEST_DB, 2).use { v2 ->
            v2.execSQL(
                "INSERT INTO measurements VALUES ('id-1', 2.5, 'M', 'Doorway', 0.03, 'HIGH', 1000)"
            )
            v2.execSQL(
                "INSERT INTO measurements VALUES ('id-2', 0.82, 'CM', NULL, 0, 'UNKNOWN', 2000)"
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 3, true, TapeDatabase.MIGRATION_2_3)

        db.query("SELECT id, distanceMeters, label, toleranceMeters, accuracyGrade, kind, readings FROM measurements ORDER BY createdAt")
            .use { c ->
                assertEquals("migration must not drop saved measurements", 2, c.count)

                assertTrue(c.moveToFirst())
                assertEquals("id-1", c.getString(0))
                assertEquals(2.5f, c.getFloat(1), 1e-6f)
                assertEquals("Doorway", c.getString(2))
                assertEquals("a real tolerance must survive untouched", 0.03f, c.getFloat(3), 1e-6f)
                assertEquals("HIGH", c.getString(4))
                assertEquals("LINE", c.getString(5))
                assertTrue("old rows have no shape readings to invent", c.isNull(6))

                assertTrue(c.moveToNext())
                assertEquals("id-2", c.getString(0))
                assertEquals("UNKNOWN", c.getString(4))
                assertEquals("LINE", c.getString(5))
            }
    }

    @Test
    fun migratedDatabaseAcceptsNewWrites() {
        // A migration that validates but leaves the table unusable for the new
        // entity shape would still pass a row-count check. Verify the DAO works,
        // all the way from v1.
        createV1("('old', 1.0, 'M', 'Old', 0.9, 500)")

        val db = Room.databaseBuilder(context, TapeDatabase::class.java, TEST_DB)
            .addMigrations(TapeDatabase.MIGRATION_1_2, TapeDatabase.MIGRATION_2_3)
            .build()

        runBlocking {
            db.measurementDao().insert(
                MeasurementEntity(
                    id              = "new",
                    distanceMeters  = 0.6f,
                    unit            = "CM",
                    label           = "Crate",
                    toleranceMeters = 0.012f,
                    accuracyGrade   = "FAIR",
                    createdAt       = 9999,
                    kind            = "BOX",
                    readings        = """[{"name":"Volume","quantity":"VOLUME","value":0.084,"tolerance":0.003,"grade":"FAIR"}]""",
                )
            )
            val all = db.measurementDao().getAll().first()
            assertEquals("old row plus new row expected", 2, all.size)

            val fresh = all.first { it.id == "new" }
            assertEquals("BOX", fresh.kind)
            assertEquals(0.012f, fresh.toleranceMeters, 1e-6f)
            assertEquals("Volume", ReadingsJson.decode(fresh.readings).single().name)

            val legacy = all.first { it.id == "old" }
            assertEquals("UNKNOWN", legacy.accuracyGrade)
            assertEquals("LINE", legacy.kind)
            assertTrue(ReadingsJson.decode(legacy.readings).isEmpty())
        }
        db.close()
    }
}
