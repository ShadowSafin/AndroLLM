package io.androllm.engine.backend

import io.androllm.core.models.Model
import io.androllm.engine.models.BackendType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the automatic backend selection chain (NPU → GPU → CPU) with
 * silent fallback and model-compatibility gating.
 */
class BackendSelectorTest {

    private val model = Model(id = "m", name = "M")

    /** A model that explicitly supports NPU execution (catalog opt-in). */
    private val npuModel = model.copy(supportsNpu = true)

    private fun caps(npuUsable: Boolean = false, npuAvailable: Boolean = npuUsable) =
        BackendCapabilities(
            cpuAvailable = true,
            gpuAvailable = true,
            npuAvailable = npuAvailable,
            npuUsable = npuUsable,
            npuVendor = if (npuUsable) "Qualcomm" else null,
            npuAccelerator = if (npuUsable) "Hexagon HTP" else null
        )

    private fun types(list: List<InferenceBackend>): List<BackendType> = list.map { it.type }

    // ── AUTO priority: NPU → GPU → CPU ──────────────────────────────────────

    @Test
    fun `AUTO with usable NPU and NPU-capable model tries NPU GPU CPU`() {
        val candidates = BackendSelector.orderedCandidates(BackendType.AUTO, caps(npuUsable = true), npuModel)
        assertEquals(listOf(BackendType.NPU, BackendType.GPU, BackendType.CPU), types(candidates))
    }

    @Test
    fun `AUTO with usable NPU but NPU-incapable model skips straight to GPU`() {
        val candidates = BackendSelector.orderedCandidates(BackendType.AUTO, caps(npuUsable = true), model)
        assertEquals(listOf(BackendType.GPU, BackendType.CPU), types(candidates))
    }

    @Test
    fun `AUTO without NPU tries GPU CPU`() {
        val candidates = BackendSelector.orderedCandidates(BackendType.AUTO, caps(npuUsable = false), model)
        assertEquals(listOf(BackendType.GPU, BackendType.CPU), types(candidates))
    }

    @Test
    fun `AUTO skips NPU when the model does not support it`() {
        val modelNoNpu = model.copy(supportsNpu = false)
        val candidates = BackendSelector.orderedCandidates(BackendType.AUTO, caps(npuUsable = true), modelNoNpu)
        assertEquals(listOf(BackendType.GPU, BackendType.CPU), types(candidates))
    }

    @Test
    fun `AUTO with model that supports only CPU falls back to CPU`() {
        val modelCpuOnly = model.copy(supportsNpu = false, supportsGpu = false)
        val candidates = BackendSelector.orderedCandidates(BackendType.AUTO, caps(npuUsable = true), modelCpuOnly)
        assertEquals(listOf(BackendType.CPU), types(candidates))
    }

    // ── Explicit selection is EXCLUSIVE — never a silent delegate swap ──────

    @Test
    fun `explicit NPU when usable and model supports it tries only NPU`() {
        val candidates = BackendSelector.orderedCandidates(BackendType.NPU, caps(npuUsable = true), npuModel)
        assertEquals(listOf(BackendType.NPU), types(candidates))
    }

    @Test
    fun `explicit NPU when unusable produces no candidates and fails the load visibly`() {
        val candidates = BackendSelector.orderedCandidates(BackendType.NPU, caps(npuUsable = false), npuModel)
        assertTrue(candidates.isEmpty())
    }

    @Test
    fun `explicit GPU tries only GPU`() {
        val candidates = BackendSelector.orderedCandidates(BackendType.GPU, caps(npuUsable = true), model)
        assertEquals(listOf(BackendType.GPU), types(candidates))
    }

    @Test
    fun `explicit CPU tries only CPU`() {
        val candidates = BackendSelector.orderedCandidates(BackendType.CPU, caps(npuUsable = true), model)
        assertEquals(listOf(BackendType.CPU), types(candidates))
    }

    @Test
    fun `explicit GPU with GPU-unsupported model produces no candidates`() {
        val modelCpuOnly = model.copy(supportsGpu = false)
        val candidates = BackendSelector.orderedCandidates(BackendType.GPU, caps(), modelCpuOnly)
        assertTrue(candidates.isEmpty())
    }

    // ── resolveAuto / bestAvailable / normalizePreference ───────────────────

    @Test
    fun `resolveAuto prefers NPU when usable and model supports it`() {
        assertEquals(BackendType.NPU, BackendSelector.resolveAuto(caps(npuUsable = true), npuModel))
    }

    @Test
    fun `resolveAuto prefers GPU when NPU unusable`() {
        assertEquals(BackendType.GPU, BackendSelector.resolveAuto(caps(), model))
    }

    @Test
    fun `resolveAuto respects model support flags`() {
        val modelNoNpu = model.copy(supportsNpu = false)
        assertEquals(BackendType.GPU, BackendSelector.resolveAuto(caps(npuUsable = true), modelNoNpu))
        val modelCpuOnly = model.copy(supportsNpu = false, supportsGpu = false)
        assertEquals(BackendType.CPU, BackendSelector.resolveAuto(caps(npuUsable = true), modelCpuOnly))
    }

    @Test
    fun `bestAvailable prefers NPU then GPU`() {
        assertEquals(BackendType.NPU, BackendSelector.bestAvailable(caps(npuUsable = true)))
        assertEquals(BackendType.GPU, BackendSelector.bestAvailable(caps()))
        assertEquals(BackendType.CPU, BackendSelector.bestAvailable(BackendCapabilities(gpuAvailable = false)))
    }

    @Test
    fun `normalizePreference maps legacy values to CPU`() {
        assertEquals(BackendType.CPU, BackendSelector.normalizePreference(BackendType.VULKAN))
        assertEquals(BackendType.CPU, BackendSelector.normalizePreference(BackendType.QUALCOMM_QNN))
        assertEquals(BackendType.NPU, BackendSelector.normalizePreference(BackendType.NPU))
        assertEquals(BackendType.AUTO, BackendSelector.normalizePreference(BackendType.AUTO))
    }

    @Test
    fun `AUTO chains always end in CPU while explicit chains never contain it`() {
        val auto = BackendSelector.orderedCandidates(BackendType.AUTO, caps(npuUsable = true), npuModel)
        assertTrue("AUTO chain must end with CPU: ${types(auto)}", auto.last().type == BackendType.CPU)
        for (pref in listOf(BackendType.NPU, BackendType.GPU, BackendType.CPU)) {
            val chain = BackendSelector.orderedCandidates(pref, caps(npuUsable = true), npuModel)
            assertTrue(
                "explicit $pref chain must not contain CPU: ${types(chain)}",
                chain.none { it.type == BackendType.CPU } || pref == BackendType.CPU
            )
        }
    }
}
