package io.androllm.engine.backend

import io.androllm.core.models.Model
import io.androllm.engine.models.BackendType

/**
 * Pure backend selection logic — decides, from the probe capabilities and the
 * user's preference, the ordered list of backends to ATTEMPT at model load.
 *
 * Backend priority (when preference is AUTO):
 *   NPU → GPU → CPU
 *
 * The chain is *attempted* in order; the engine falls through to the next
 * candidate whenever initialization fails, so the user never notices the
 * fallback. Model compatibility flags ([Model.supportsNpu]/[supportsGpu])
 * prune candidates before any attempt — a model that does not support NPU
 * skips straight to GPU → CPU without user interaction.
 *
 * Pure logic (no Android/LiteRT dependencies) — fully unit-testable.
 */
object BackendSelector {

    /**
     * Ordered candidates for [preference] given the probe [caps] and the
     * [model]'s compatibility flags. The last element is always CPU (the
     * guaranteed-safe fallback) when the model supports it.
     */
    fun orderedCandidates(
        preference: BackendType,
        caps: BackendCapabilities,
        model: Model
    ): List<InferenceBackend> {
        val chain = when (preference) {
            BackendType.AUTO, BackendType.NPU, BackendType.GPU -> buildList {
                val npuVendor = NpuVendor.detect(
                    socManufacturer = caps.npuVendor
                )
                if (caps.npuUsable && model.supportsNpu) {
                    add(InferenceBackend.NpuBackend(npuVendor))
                }
                if (model.supportsGpu) {
                    add(InferenceBackend.GpuBackend)
                }
            }
            BackendType.CPU -> emptyList()
            // Legacy llama.cpp-era values are never produced by the LiteRT
            // engine; treat any other request as the safe CPU default.
            else -> emptyList()
        }
        return if (model.supportsCpu) chain + InferenceBackend.CpuBackend else chain
    }

    /**
     * What an AUTO request actually resolves to BEFORE attempting anything:
     * NPU when usable + model-supported, else GPU when model-supported, else
     * CPU. Used for capability reporting and status pills.
     */
    fun resolveAuto(caps: BackendCapabilities, model: Model): BackendType = when {
        caps.npuUsable && model.supportsNpu -> BackendType.NPU
        model.supportsGpu -> BackendType.GPU
        else -> BackendType.CPU
    }

    /**
     * The best backend this DEVICE can run (ignoring the model) — NPU when the
     * probe says it is usable, else GPU. Drives the default preference for a
     * fresh install and the \"Auto\" chip label.
     */
    fun bestAvailable(caps: BackendCapabilities): BackendType = when {
        caps.npuUsable -> BackendType.NPU
        caps.gpuAvailable -> BackendType.GPU
        else -> BackendType.CPU
    }

    /**
     * Normalizes a persisted/legacy preference into one of the user-selectable
     * values (AUTO/GPU/CPU/NPU). Legacy llama.cpp-era values collapse to CPU.
     */
    fun normalizePreference(preference: BackendType): BackendType = when (preference) {
        BackendType.AUTO, BackendType.NPU, BackendType.GPU, BackendType.CPU -> preference
        else -> BackendType.CPU
    }
}
