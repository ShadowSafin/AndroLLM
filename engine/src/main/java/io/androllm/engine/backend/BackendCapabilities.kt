package io.androllm.engine.backend

import io.androllm.engine.models.BackendType
import kotlinx.serialization.Serializable

/**
 * Result of the startup hardware probe — what this device can actually run
 * inference on, with the detected accelerator identities.
 *
 * The probe runs ONCE at engine initialization ([HardwareBackendProbe]) and is
 * exposed through [io.androllm.engine.models.EngineCapabilities.backendCapabilities].
 * Every field is a *capability*, never a runtime decision:
 *
 *  - [npuAvailable] — the SoC carries an NPU block (vendor detected) and the
 *    Android Neural Networks API is present. Hardware exists; the delegate may
 *    still fail to initialize for a given model.
 *  - [npuUsable] — the LiteRT vendor dispatch library (`libLiteRtDispatch_*.so`)
 *    is reachable from the app's native library dir. This is the ONLY gate for
 *    the UI: the NPU option is hidden unless [npuUsable] is true, so a device
 *    without the vendor driver behaves exactly as it does today.
 *
 * The UI reads [npuUsable] (adaptive settings) and [selectedBackend] (engine
 * status). [BackendSelector] turns these capabilities into an ordered backend
 * chain with silent fallback.
 */
@Serializable
data class BackendCapabilities(
    /** CPU (XNNPACK) is always available. */
    val cpuAvailable: Boolean = true,
    /** The LiteRT GPU delegate is available (OpenCL present). */
    val gpuAvailable: Boolean = true,
    /**
     * The SoC has an NPU block (vendor identified). Deliberately independent
     * of the NNAPI feature flag, which many OEM builds omit on NPU devices.
     */
    val npuAvailable: Boolean = false,
    /**
     * The LiteRT NPU delegate can actually be reached: the vendor dispatch
     * library for the detected SoC was found. False on every device that does
     * not bundle the vendor NPU driver — the app then behaves exactly as it
     * does today (NPU option hidden, no NPU attempts).
     */
    val npuUsable: Boolean = false,
    val gpuName: String? = null,
    val gpuVendor: String? = null,
    val npuName: String? = null,
    val npuVendor: String? = null,
    val npuAccelerator: String? = null,
    /** Android Neural Networks API availability (package manager feature). */
    val nnApiAvailable: Boolean = false,
    /** Backend chosen by [BackendSelector] for the NEXT load (or last load). */
    val selectedBackend: BackendType = BackendType.CPU,
    /** Non-fatal probe failure detail ("" when the probe ran clean). */
    val probeError: String? = null,
    val probedAtMs: Long = 0L
) {
    /** The NPU option is user-visible only when the delegate is reachable. */
    val npuOptionVisible: Boolean get() = npuUsable

    /** Display label for the detected NPU accelerator ("—" when none). */
    val npuDisplayName: String
        get() = listOfNotNull(npuName, npuAccelerator).filter { it.isNotBlank() }.joinToString(" ") { it }
            .ifBlank { "NPU" }

    companion object {
        /** All-unknown probe (engine not yet initialized / probe failed). */
        val UNKNOWN = BackendCapabilities()
    }
}
