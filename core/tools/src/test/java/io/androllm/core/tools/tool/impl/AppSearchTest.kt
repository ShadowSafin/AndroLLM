package io.androllm.core.tools.tool.impl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSearchTest {

    private fun entry(label: String, pkg: String, hasLauncher: Boolean = true) =
        AppEntry(
            label = label,
            packageName = pkg,
            hasLauncher = hasLauncher,
            launchIntent = null
        )

    private val apps = listOf(
        entry("Discord", "com.discord"),
        entry("Discord Canary", "com.discord.canary"),
        entry("YouTube", "com.google.android.youtube"),
        entry("Instagram", "com.instagram.android"),
        entry("Spotify", "com.spotify.music"),
        entry("Google Chrome", "com.android.chrome"),
        entry("Settings", "com.android.settings", hasLauncher = false)
    )

    @Test
    fun `exact label match wins over partial`() {
        val result = AppSearch.search(apps, "Spotify")
        assertEquals(listOf("Spotify"), result.matches.map { it.label })
        assertFalse(result.ambiguous)
    }

    @Test
    fun `package name match wins`() {
        val result = AppSearch.search(apps, "com.discord")
        assertEquals("com.discord", result.matches.first().packageName)
        assertFalse(result.ambiguous)
    }

    @Test
    fun `case insensitive partial match`() {
        val result = AppSearch.search(apps, "SPOT")
        assertEquals(listOf("Spotify"), result.matches.map { it.label })
    }

    @Test
    fun `discord query resolves to Discord not Canary`() {
        val result = AppSearch.search(apps, "Discord")
        assertEquals(listOf("Discord"), result.matches.map { it.label })
        assertFalse(result.ambiguous)
    }

    @Test
    fun `alias yt resolves to YouTube`() {
        val result = AppSearch.search(apps, "yt")
        assertEquals(listOf("YouTube"), result.matches.map { it.label })
    }

    @Test
    fun `fuzzy insta resolves to Instagram`() {
        val result = AppSearch.search(apps, "insta")
        assertEquals(listOf("Instagram"), result.matches.map { it.label })
    }

    @Test
    fun `ambiguous when two apps tie exactly`() {
        val ties = apps + entry("Discord", "com.discord.beta")
        val result = AppSearch.search(ties, "Discord")
        // Two different packages with the exact same label → ask the user.
        assertEquals(2, result.matches.size)
        assertTrue(result.matches.all { it.label == "Discord" })
        assertTrue(result.ambiguous)
    }

    @Test
    fun `settings matched even without launcher activity`() {
        val result = AppSearch.search(apps, "Settings")
        assertEquals(listOf("Settings"), result.matches.map { it.label })
        assertFalse(result.matches.first().hasLauncher)
    }

    @Test
    fun `unknown query returns no matches`() {
        val result = AppSearch.search(apps, "zzzz-not-an-app")
        assertTrue(result.matches.isEmpty())
        assertFalse(result.ambiguous)
    }

    @Test
    fun `blank query returns no matches`() {
        assertTrue(AppSearch.search(apps, "   ").matches.isEmpty())
    }
}
