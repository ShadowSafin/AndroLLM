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
 * candidate whenever initialization fails, so an AUTO load never fails
 * outright. Model compatibility flags ([Model.supportsNpu]/[supportsGpu])
 * prune candidates before any attempt — a model that does not support NPU
 * skips straight to GPU → CPU without user interaction.
 *
 * EXPLICIT selections are exclusive by contract: choosing GPU (or NPU/NPU)
 * builds a SINGLE-candidate chain. A delegate the user explicitly requested
 * is never silently swapped for another — if it cannot initialize or fails
 * its self-test, the load FAILS with a clear error and the user decides what
 * to do next. Only AUTO may traverse the full chain down to CPU.
 *
 * Pure logic (no Android/LiteRT dependencies) — fully unit-testable.
 */
object BackendSelector {

    /**
     * Ordered candidates for [preference] given the probe [caps] and the
     * [model]'s compatibility flags. AUTO chains always end with CPU (the
     * guaranteed-safe fallback); explicit chains contain ONLY the requested
     * backend.
     */
    fun orderedCandidates(
        preference: BackendType,
        caps: BackendCapabilities,
        model: Model
    ): List<InferenceBackend> {
        val chain = when (preference) {
            BackendType.AUTO -> buildList {
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
            // Explicit requests are EXCLUSIVE: exactly one candidate. If it
            // cannot initialize, the failure surfaces to the user instead of
            // a silent delegate swap (spec: never fall back behind their back).
            BackendType.NPU -> buildList {
                val npuVendor = NpuVendor.detect(socManufacturer = caps.npuVendor)
                if (caps.npuUsable && model.supportsNpu) {
                    add(InferenceBackend.NpuBackend(npuVendor))
                }
            }
            BackendType.GPU -> buildList {
                if (model.supportsGpu) {
                    add(InferenceBackend.GpuBackend)
                }
            }
            BackendType.CPU -> buildList {
                if (model.supportsCpu) {
                    add(InferenceBackend.CpuBackend)
                }
            }
            // Legacy llama.cpp-era values are never produced by the LiteRT
            // engine; treat any other request as the safe CPU default.
            else -> buildList {
                if (model.supportsCpu) {
                    add(InferenceBackend.CpuBackend)
                }
            }
        }
        return if (preference == BackendType.AUTO && model.supportsCpu) chain + InferenceBackend.CpuBackend else chain
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
     * Memory-aware backend choice: prefers the backend that fits best in
     * available memory while remaining stable. GPU/NPU delegate overhead
     * (~128–192MB) can tip an 8B model over budget; CPU (~24MB) is the most
     * memory-efficient fallback. Returns the preferred backend that also fits
     * the memory budget when [availableBytes] is provided.
     */
    fun bestAvailableForMemory(
        caps: BackendCapabilities,
        model: Model,
        fileSizeBytes: Long,
        contextLength: Int,
        availableBytes: Long
    ): BackendType {
        if (availableBytes <= 0L) return bestAvailable(caps)
        val candidates = orderedCandidates(BackendType.AUTO, caps, model)
        if (candidates.isEmpty()) return BackendType.CPU
        // Score each candidate by whether it fits + its speed rank (NPU > GPU > CPU)
        val scored = candidates.map { backend ->
            val overhead = io.androllm.engine.utils.MemoryEstimator.estimateBackendOverhead(backend.type, false)
            val footprint = io.androllm.engine.utils.MemoryEstimator.estimateDetailedFootprint(
                fileSizeBytes = fileSizeBytes,
                contextLength = contextLength,
                backend = backend.type,
                scratchFraction = 0.11f
            )
            val budget = (availableBytes * 0.85).toLong()
            val fits = footprint <= budget
            val speedRank = when (backend.type) {
                BackendType.NPU -> 3
                BackendType.GPU -> 2
                else -> 1
            }
            // Fits bonus outweighs speed; among fits, prefer faster.
            val score = (if (fits) 100 else 0) + speedRank - (if (overhead > 100 * 1024 * 1024) 1 else 0)
            Triple(backend, fits, score)
        }
        return scored.maxByOrNull { it.third }?.first?.type ?: BackendType.CPU
    }

    /**
     * Returns the most memory-efficient backend among AUTO candidates.
     * CPU is always the most efficient; GPU next if CPU not allowed.
     */
    fun mostMemoryEfficient(caps: BackendCapabilities, model: Model): BackendType {
        val candidates = orderedCandidates(BackendType.AUTO, caps, model)
        return candidates.minByOrNull {
            io.androllm.engine.utils.MemoryEstimator.estimateBackendOverhead(it.type, false)
        }?.type ?: BackendType.CPU
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
