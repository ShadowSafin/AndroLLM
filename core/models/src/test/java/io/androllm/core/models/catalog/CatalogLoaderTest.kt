package io.androllm.core.models.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogLoaderTest {

    private fun catalogJson(model: String) = """{"schemaVersion":1,"models":[$model]}"""

    private fun model(id: String = "test-1", arch: String = "llama", quant: String = "Q4_K_M") =
        """{"id":"$id","name":"Test Model","family":"Test","architecture":"$arch",""" +
            """"categories":["CHAT"],"tags":["fast"],"license":"Apache-2.0","author":"T",""" +
            """"repoId":"t/test-litertlm","fileName":"test-$quant.litertlm",""" +
            """"downloadUrl":"https://huggingface.co/t/test-litertlm/resolve/main/test-$quant.litertlm",""" +
            """"sizeBytes":1000000000,"parameters":"1.5B","quantization":"$quant",""" +
            """"contextLength":8192,"minRamGb":2.0,"recommendedRamGb":4.0,"downloads":100,"likes":5}"""

    @Test
    fun bundledCatalogLoadsWhenNothingSaved() {
        val state = CatalogLoader.load(null, catalogJson(model()))
        assertTrue(state is CatalogState.Ready)
        state as CatalogState.Ready
        assertEquals(CatalogSource.BUNDLED, state.source)
        assertEquals(1, state.catalog.models.size)
    }

    @Test
    fun savedCatalogWinsOverBundled() {
        val state = CatalogLoader.load(catalogJson(model("saved-1")), catalogJson(model("bundled-1")))
        assertTrue(state is CatalogState.Ready)
        state as CatalogState.Ready
        assertEquals(CatalogSource.SAVED, state.source)
        assertEquals("saved-1", state.catalog.models.first().id)
    }

    @Test
    fun invalidSavedCatalogFallsBackToBundled() {
        val state = CatalogLoader.load("not json at all", catalogJson(model("bundled-1")))
        assertTrue(state is CatalogState.Ready)
        state as CatalogState.Ready
        assertEquals(CatalogSource.BUNDLED, state.source)
        assertEquals("bundled-1", state.catalog.models.first().id)
    }

    @Test
    fun missingBundledAssetBecomesFailed() {
        val state = CatalogLoader.load(null, "")
        assertTrue(state is CatalogState.Failed)
    }

    @Test
    fun applyAcceptsValidRemoteCatalog() {
        val state = CatalogLoader.apply(catalogJson(model("remote-1")), CatalogSource.REMOTE)
        assertTrue(state is CatalogState.Ready)
        state as CatalogState.Ready
        assertEquals(CatalogSource.REMOTE, state.source)
        assertEquals("remote-1", state.catalog.models.first().id)
    }

    @Test
    fun applyRejectsCatalogWithUnsupportedArchitecture() {
        val state = CatalogLoader.apply(catalogJson(model("bad", arch = "not-a-real-arch")), CatalogSource.REMOTE)
        assertTrue(state is CatalogState.Failed)
        assertTrue((state as CatalogState.Failed).message.contains("architecture"))
    }

    @Test
    fun applyRejectsGarbageJson() {
        val state = CatalogLoader.apply("{broken", CatalogSource.REMOTE)
        assertTrue(state is CatalogState.Failed)
    }
}
