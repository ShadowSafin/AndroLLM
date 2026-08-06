package io.androllm.core.memory.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies the memory database migration path (v1 -> v2) against the exported
 * Room schemas. Requires a device/emulator to run (instrumentation test).
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        MemoryDatabase::class.java
    )

    @Test
    fun migrate1To2_addsArchivedColumn() {
        helper.createDatabase(TEST_DB, 1).apply {
            execSQL(
                "INSERT INTO memory_entity (id, category, content, importance, created_at, updated_at) " +
                    "VALUES ('m1', 'CUSTOM', 'hello', 1, 100, 100)"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 2, true, MemoryDatabase.MIGRATION_1_2)

        db.query("SELECT is_archived FROM memory_entity WHERE id = 'm1'").use { cursor ->
            assertTrue("expected the migrated row", cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
        db.close()
    }

    companion object {
        private const val TEST_DB = "migration-test"
    }
}
