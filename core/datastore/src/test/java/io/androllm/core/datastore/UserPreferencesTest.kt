package io.androllm.core.datastore

import io.androllm.core.models.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the user preferences snapshot.
 */
class UserPreferencesTest {

    @Test
    fun `defaults are applied`() {
        val preferences = UserPreferences()
        assertEquals(ThemeMode.SYSTEM, preferences.theme)
        assertEquals("en", preferences.language)
        assertTrue(preferences.firstLaunch)
    }
}
