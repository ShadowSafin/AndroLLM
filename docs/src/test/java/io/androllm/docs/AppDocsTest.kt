package io.androllm.docs

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for the documentation module.
 */
class AppDocsTest {

    @Test
    fun `app metadata is branded as AndroLLM`() {
        assertEquals("AndroLLM", AppDocs.App.NAME)
        assertEquals("Private AI. Offline. On your device.", AppDocs.App.TAGLINE)
        assertEquals("Run powerful AI models locally on Android.", AppDocs.App.DESCRIPTION)
    }

    @Test
    fun `architecture lists all layers`() {
        assertEquals("app, core (common, ui, database, datastore, navigation, models, network, utils), feature (home, chat, models, settings, splash), engine, docs", AppDocs.Architecture.LAYERS)
    }
}
