package io.androllm.feature.coding.environment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Marketplace catalog contents + dependency chains. */
class MarketplaceCatalogTest {

    @Test
    fun `catalog contains the core runtimes and tools`() {
        val ids = MarketplaceCatalog.packages.map { it.id }.toSet()
        listOf("nodejs", "pnpm", "yarn", "python", "git", "java", "gradle", "go", "rust", "build-tools", "linux-utils")
            .forEach { assertTrue("catalog must contain $it", it in ids) }
    }

    @Test
    fun `find returns the package by id`() {
        val node = MarketplaceCatalog.find("nodejs")
        assertNotNull(node)
        assertEquals("Node.js", node!!.name)
        assertTrue(node.providesCommands.contains("npm"))
        assertNull(MarketplaceCatalog.find("does-not-exist"))
    }

    @Test
    fun `every addon advertises metadata for the marketplace UI`() {
        MarketplaceCatalog.packages.forEach { pkg ->
            assertTrue("${pkg.id} needs a description", pkg.description.isNotBlank())
            assertTrue("${pkg.id} needs a version", pkg.version.isNotBlank())
            assertTrue("${pkg.id} needs a size", pkg.sizeBytes > 0)
            assertTrue("${pkg.id} needs platforms", pkg.platforms.isNotEmpty())
        }
    }

    @Test
    fun `pnpm dependency chain installs nodejs first`() {
        val chain = MarketplaceCatalog.dependencyChain("pnpm").map { it.id }
        assertEquals(listOf("nodejs", "pnpm"), chain)
    }

    @Test
    fun `gradle dependency chain installs java first`() {
        val chain = MarketplaceCatalog.dependencyChain("gradle").map { it.id }
        assertEquals(listOf("java", "gradle"), chain)
    }

    @Test
    fun `nodejs chain is just nodejs`() {
        assertEquals(listOf("nodejs"), MarketplaceCatalog.dependencyChain("nodejs").map { it.id })
    }

    @Test
    fun `grouped by kind covers all packages`() {
        val grouped = MarketplaceCatalog.groupedByKind()
        val total = grouped.values.sumOf { it.size }
        assertEquals(MarketplaceCatalog.packages.size, total)
    }
}
