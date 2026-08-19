package io.androllm.core.models.catalog

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for [ModelMetadataRegistry] — the single source of truth
 * mapping container identifiers, catalog families and architectures onto the
 * metadata the engine needs. Every supported container identifier must be
 * registered (none is ever "rejected"), and every catalog entry must be
 * consistent with the registry.
 */
class ModelMetadataRegistryTest {

    @Test
    fun allTenContainerIdentifiersAreRegistered() {
        assertEquals(
            setOf("generic_model", "qwen3", "qwen2p5", "gemma3", "gemma3n", "gemma4",
                "function_gemma", "fast_vlm", "lfm2", "minicpm5"),
            ModelMetadataRegistry.allContainerTypes
        )
    }

    @Test
    fun genericModelCarriesNoEngineFamily() {
        val spec = ModelMetadataRegistry.containerTypeFor("generic_model")
        assertNotNull(spec)
        assertNull(spec!!.engineFamilyKey)
    }

    @Test
    fun containerTypesMapToTheirEngineFamilies() {
        assertEquals("QWEN3", ModelMetadataRegistry.engineFamilyKeyForContainer("qwen3"))
        assertEquals("QWEN2P5", ModelMetadataRegistry.engineFamilyKeyForContainer("qwen2p5"))
        assertEquals("GEMMA", ModelMetadataRegistry.engineFamilyKeyForContainer("gemma3"))
        assertEquals("GEMMA", ModelMetadataRegistry.engineFamilyKeyForContainer("gemma3n"))
        assertEquals("GEMMA", ModelMetadataRegistry.engineFamilyKeyForContainer("gemma4"))
        assertEquals("GEMMA", ModelMetadataRegistry.engineFamilyKeyForContainer("function_gemma"))
        assertEquals("LLAMA3", ModelMetadataRegistry.engineFamilyKeyForContainer("lfm2"))
        assertNull(ModelMetadataRegistry.engineFamilyKeyForContainer("generic_model"))
    }

    @Test
    fun visionAndUnmappedContainersUseGenericEngineMode() {
        // Registered identifiers WITHOUT a bespoke engine family — the engine
        // runs them in GENERIC mode with the container's own embedded template.
        assertEquals("GENERIC", ModelMetadataRegistry.engineFamilyKeyForContainer("fast_vlm"))
        assertEquals("GENERIC", ModelMetadataRegistry.engineFamilyKeyForContainer("minicpm5"))
        assertTrue(ModelMetadataRegistry.isKnownContainerType("fast_vlm"))
        assertTrue(ModelMetadataRegistry.isKnownContainerType("minicpm5"))
    }

    @Test
    fun unknownIdentifiersAreNotRegisteredButNeverRejected() {
        assertFalse(ModelMetadataRegistry.isKnownContainerType("llama4"))
        assertNull(ModelMetadataRegistry.containerTypeFor("llama4"))
        // Not registered → returns null (engine auto-resolves instead of throwing).
        assertNull(ModelMetadataRegistry.engineFamilyKeyForContainer("llama4"))
    }

    @Test
    fun familyLookupWorksByKeyAndDisplayName() {
        assertEquals("QWEN", ModelMetadataRegistry.familyFor("QWEN")?.familyKey)
        assertEquals("QWEN", ModelMetadataRegistry.familyFor("Qwen")?.familyKey)
        assertEquals("QWEN", ModelMetadataRegistry.familyFor("qwen")?.familyKey)
        assertNull(ModelMetadataRegistry.familyFor("MadeUp"))
        assertNull(ModelMetadataRegistry.familyFor(null))
    }

    @Test
    fun qwenFamilySpansSeveralEngineFamilies() {
        // Qwen covers QWEN2/QWEN2P5/QWEN3 — no single engine family; the
        // container type disambiguates at load time.
        assertNull(ModelMetadataRegistry.familyFor("Qwen")?.engineFamilyKey)
        assertEquals(setOf("qwen2", "qwen2.5-coder", "qwen3", "qwen3-asr"),
            ModelMetadataRegistry.familyFor("Qwen")?.architectures)
    }

    @Test
    fun singleFamilyMappingsResolveToEngineFamilies() {
        assertEquals("GEMMA", ModelMetadataRegistry.familyFor("Gemma")?.engineFamilyKey)
        assertEquals("GEMMA", ModelMetadataRegistry.familyFor("FunctionGemma")?.engineFamilyKey)
        assertEquals("DEEPSEEK", ModelMetadataRegistry.familyFor("DeepSeek")?.engineFamilyKey)
        assertEquals("PHI", ModelMetadataRegistry.familyFor("Phi")?.engineFamilyKey)
        assertEquals("SMOL", ModelMetadataRegistry.familyFor("SmolLM")?.engineFamilyKey)
        assertEquals("LLAMA3", ModelMetadataRegistry.familyFor("Llama")?.engineFamilyKey)
        assertEquals("TINYLLAMA", ModelMetadataRegistry.familyFor("TinyLlama")?.engineFamilyKey)
        assertEquals("QWEN2P5", ModelMetadataRegistry.familyFor("TinySwallow")?.engineFamilyKey)
        assertEquals("QWEN2P5", ModelMetadataRegistry.familyFor("VibeThinker")?.engineFamilyKey)
        // Vision/speech families have no bespoke engine family.
        assertNull(ModelMetadataRegistry.familyFor("FastVLM")?.engineFamilyKey)
        assertNull(ModelMetadataRegistry.familyFor("Whisper")?.engineFamilyKey)
    }

    @Test
    fun architectureLookupWorks() {
        assertEquals("GEMMA", ModelMetadataRegistry.familyForArchitecture("gemma3")?.familyKey)
        assertEquals("LLAMA", ModelMetadataRegistry.familyForArchitecture("lfm2")?.familyKey)
        assertEquals("SMOLLM", ModelMetadataRegistry.familyForArchitecture("smollm3")?.familyKey)
        assertNull(ModelMetadataRegistry.familyForArchitecture("madeup"))
        // Overlapping architectures (qwen2 appears under several families) are
        // resolved through the family/architecture PAIR, never by arch alone.
        assertNotNull(ModelMetadataRegistry.familyForArchitecture("qwen2"))
    }

    @Test
    fun mimeTypesAreDerivedFromFormats() {
        assertEquals("application/x-litertlm", ModelMetadataRegistry.mimeTypeFor("LITERTLM"))
        assertEquals("application/x-tflite", ModelMetadataRegistry.mimeTypeFor("tflite"))
        assertNull(ModelMetadataRegistry.mimeTypeFor("GGUF"))
        assertNull(ModelMetadataRegistry.mimeTypeFor(null))
    }

    @Test
    fun everyBundledCatalogEntryIsRegistryConsistent() {
        val file = File("src/main/assets/catalog_v1.json")
        assertTrue("asset not found: ${file.absolutePath}", file.exists())
        val catalog = CatalogParser.parse(file.readText()).catalog
        for (m in catalog.models) {
            val spec = ModelMetadataRegistry.familyFor(m.family)
            assertTrue("${m.id}: family '${m.family}' not in registry", spec != null)
            assertTrue("${m.id}: architecture '${m.architecture}' not in family '${m.family}'",
                spec!!.architectures.contains(m.architecture))
            val format = ModelMetadataRegistry.ContainerFormat.entries
                .firstOrNull { it.name == m.runtimeFormat.uppercase() }
            assertTrue("${m.id}: format '${m.runtimeFormat}' not supported by family '${m.family}'",
                format != null && format in spec.containerFormats)
            if (m.fileName.endsWith(".litertlm", ignoreCase = true)) {
                assertTrue("${m.id}: containerType '${m.containerType}' not registered",
                    ModelMetadataRegistry.isKnownContainerType(m.containerType))
            } else {
                assertTrue("${m.id}: tflite must not declare containerType", m.containerType.isNullOrBlank())
            }
        }
    }
}