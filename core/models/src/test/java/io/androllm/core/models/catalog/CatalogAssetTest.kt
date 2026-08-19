package io.androllm.core.models.catalog

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogAssetTest {

    @Test
    fun bundledCatalogParsesAndValidates() {
        val file = File("src/main/assets/catalog_v1.json")
        assertTrue("asset not found: ${file.absolutePath}", file.exists())
        val result = CatalogParser.parse(file.readText())
        assertTrue("parse warnings: ${result.warnings}", result.warnings.isEmpty())
        val report = CatalogValidator.validate(result.catalog.models)
        assertTrue(
            "validation errors:\n${report.errors.joinToString("\n")}\nwarnings:\n${report.warnings.joinToString("\n")}",
            report.errors.isEmpty()
        )
        assertEquals(2, result.catalog.schemaVersion)
        // LiteRT catalog: 32 curated, downloadable models — chat containers,
        // and speech/ASR flatbuffers (audio pipeline). Gated models are not
        // shipped (they cannot be downloaded or validated without credentials).
        assertEquals(32, result.catalog.models.size)
        // No gated model may ship: the app cannot download or verify them.
        val gated = result.catalog.models.filter { it.isGated }
        assertTrue("gated models must be removed from the catalog: ${gated.map { it.id }}", gated.isEmpty())
        // Every entry must live in at least one known section.
        val knownSections = CatalogSections.ALL.toSet()
        val unknownSections = result.catalog.models.flatMap { it.sections }.filterNot { it in knownSections }.toSet()
        assertTrue("unknown section names: $unknownSections", unknownSections.isEmpty())
        // Only loadable modalities remain: chat, embedding/RAG, or audio.
        val nonChat = result.catalog.models.filter {
            "CHAT" !in it.categories && "EMBEDDING" !in it.categories && "RAG" !in it.categories &&
                "AUDIO" !in it.categories
        }
        assertTrue("non-loadable entries: ${nonChat.map { it.id }}", nonChat.isEmpty())
        // The Featured section must be populated with exactly the curated picks.
        val featured = result.catalog.models.filter { CatalogSections.FEATURED in it.sections }
        assertEquals(8, featured.size)
        // Every entry must be a LiteRT artifact: .litertlm containers for
        // chat, .tflite flatbuffers for embedding/speech pipelines.
        val nonLiteRt = result.catalog.models.filter {
            !it.fileName.endsWith(".litertlm", ignoreCase = true) &&
                !it.fileName.endsWith(".tflite", ignoreCase = true)
        }
        assertTrue(
            "entries with non-LiteRT artifacts: ${nonLiteRt.map { it.id }}",
            nonLiteRt.isEmpty()
        )
        // Guard against the class of bug that broke native loading: the
        // LiteRT-LM chat Engine only loads .litertlm containers. A raw
        // .tflite chat entry fails native init with "Unsupported file
        // format" — reject it at catalog level.
        val tfliteChat = result.catalog.models.filter {
            "CHAT" in it.categories && it.fileName.endsWith(".tflite", ignoreCase = true)
        }
        assertTrue(
            "chat entries must be .litertlm containers: ${tfliteChat.map { it.id }}",
            tfliteChat.isEmpty()
        )
        // Downloads are verified against sha256 when present; every non-gated
        // model must ship a real checksum so downloads are integrity-checked.
        val missingSha = result.catalog.models.filter {
            !it.isGated && it.sha256.isNullOrBlank()
        }
        assertTrue(
            "non-gated models must declare sha256: ${missingSha.map { it.id }}",
            missingSha.isEmpty()
        )
        val badSha = result.catalog.models.filter { s ->
            !s.sha256.isNullOrBlank() && !s.sha256.matches(Regex("^[a-fA-F0-9]{64}$"))
        }
        assertTrue("sha256 must be 64 hex chars: ${badSha.map { it.id }}", badSha.isEmpty())
        // Registry-driven metadata: every entry's family / architecture /
        // runtimeFormat must be consistent with the model metadata registry —
        // this is the indexing-time guarantee that resolution at load time
        // (container type → registry → engine family) can never miss.
        val unknownFamilies = result.catalog.models.filter { ModelMetadataRegistry.familyFor(it.family) == null }
        assertTrue(
            "families not in registry: ${unknownFamilies.map { it.id }}",
            unknownFamilies.isEmpty()
        )
        val archMismatch = result.catalog.models.filter { m ->
            val spec = ModelMetadataRegistry.familyFor(m.family)
            spec != null && m.architecture !in spec.architectures
        }
        assertTrue(
            "family/architecture mismatches: ${archMismatch.map { it.id }}",
            archMismatch.isEmpty()
        )
        val formatMismatch = result.catalog.models.filter { m ->
            val spec = ModelMetadataRegistry.familyFor(m.family)
            val format = ModelMetadataRegistry.ContainerFormat.entries
                .firstOrNull { it.name == m.runtimeFormat.uppercase() }
            spec != null && (format == null || format !in spec.containerFormats)
        }
        assertTrue(
            "family/format mismatches: ${formatMismatch.map { it.id }}",
            formatMismatch.isEmpty()
        )
        // Every .litertlm entry declares a registered container type;
        // .tflite entries (speech/embedding) declare none.
        val containerProblems = result.catalog.models.filter { m ->
            if (m.fileName.endsWith(".litertlm", ignoreCase = true)) {
                !ModelMetadataRegistry.isKnownContainerType(m.containerType)
            } else {
                !m.containerType.isNullOrBlank()
            }
        }
        assertTrue(
            "containerType problems: ${containerProblems.map { it.id }}",
            containerProblems.isEmpty()
        )
        val ids = result.catalog.models.map { it.id }.toSet()
        assertEquals(ids.size, result.catalog.models.size)
    }

    @Test
    fun bundledCatalogIsCleanUtf8() {
        val file = File("src/main/assets/catalog_v1.json")
        val raw = file.readText()
        assertTrue("replacement chars (corrupt UTF-8) in $file", !raw.contains('\uFFFD'))
        // Everything that survives an ANSI round-trip (the mojibake that once
        // garbled badge labels like "🔥 Trending" into "Ã°Å¸..."): reject it.
        val mojibake = listOf("\u00C3\u0192", "\u00C3\u00B0", "\u00C3\u00A2\u00E2", "\u00C3\u00A5", "\u00C3\u00A6", "\u00C3\u00B8")
        assertTrue("mojibake markers found in $file", mojibake.none { raw.contains(it) })
        val result = CatalogParser.parse(raw)
        assertTrue(result.warnings.isEmpty())
        // Badge labels must end in a readable ASCII word (e.g. "🔥 Trending"),
        // with no control or replacement characters anywhere.
        for (badge in result.catalog.models.flatMap { it.badges }) {
            assertTrue("badge is not a clean label: '$badge'", badge.none { it.isControl() || it == '\uFFFD' })
            val words = badge.split(" ")
            assertTrue("badge must end in an ASCII label: '$badge'", words.last().all { it.isAsciiPrintable() })
            assertTrue("badge label must be non-blank: '$badge'", words.last().isNotBlank())
        }
    }
}

private fun Char.isAsciiPrintable(): Boolean = this in '\u0020'..'\u007E'
private fun Char.isControl(): Boolean = this in '\u0000'..'\u001F' || this in '\u007F'..'\u009F'
