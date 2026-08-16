package io.androllm.engine.backend

import android.content.Context
import android.content.pm.PackageManager
import java.io.File

/**
 * Startup hardware probe — runs ONCE at engine initialization and produces the
 * [BackendCapabilities] that drive automatic backend selection and the
 * adaptive settings UI.
 *
 * Detects, in order of authority:
 *  1. SoC vendor (Qualcomm / MediaTek / Google Tensor / Samsung) from the
 *     standard `ro.soc.*` / `ro.board.*` / `ro.hardware*` system properties.
 *  2. Android Neural Networks API availability (package-manager feature).
 *  3. GPU identity from `ro.hardware.egl` (+ board-platform heuristics).
 *  4. LiteRT vendor dispatch libraries (`libLiteRtDispatch_*.so`) reachable
 *     from the app's native library dir — the ONLY gate that makes the NPU
 *     delegate actually selectable.
 *
 * Every step is best-effort: a failing step degrades to \"unknown\", never
 * throws, and never breaks the app. On devices without an NPU driver the
 * probe reports `npuUsable = false` and the app behaves exactly as it does
 * today.
 */
object HardwareBackendProbe {

    private const val PROP_SOC_MANUFACTURER = "ro.soc.manufacturer"
    private const val PROP_SOC_MODEL = "ro.soc.model"
    private const val PROP_HARDWARE = "ro.hardware"
    private const val PROP_BOARD_PLATFORM = "ro.board.platform"
    private const val PROP_PRODUCT_BOARD = "ro.product.board"
    private const val PROP_CHIPNAME = "ro.hardware.chipname"
    private const val PROP_EGL = "ro.hardware.egl"

    /** Android feature flag for Neural Networks API presence. */
    private const val FEATURE_NEURAL_NETWORKS = "android.hardware.neuralnetworks"

    /**
     * Runs the full probe against the real device.
     */
    fun probe(context: Context): BackendCapabilities {
        val props = readSystemProperties()
        val nnApi = runCatching {
            context.packageManager.hasSystemFeature(FEATURE_NEURAL_NETWORKS)
        }.getOrDefault(false)
        val libDir = runCatching { context.applicationInfo.nativeLibraryDir }.getOrNull()
        val dispatchLibs = runCatching { File(libDir ?: "").list() ?: emptyArray() }
            .getOrDefault(emptyArray())
            .filter { it.startsWith("libLiteRtDispatch_") && it.endsWith(".so") }
        return fromProperties(props, nnApiAvailable = nnApi, dispatchLibraries = dispatchLibs)
    }

    /**
     * Pure probe implementation (unit-testable). [dispatchLibraries] is the
     * list of `.so` files found in the native library dir.
     */
    fun fromProperties(
        props: Map<String, String>,
        nnApiAvailable: Boolean = false,
        dispatchLibraries: List<String> = emptyList()
    ): BackendCapabilities {
        val vendor = NpuVendor.detect(
            socManufacturer = props[PROP_SOC_MANUFACTURER],
            socModel = props[PROP_SOC_MODEL],
            hardware = props[PROP_HARDWARE],
            boardPlatform = props[PROP_BOARD_PLATFORM],
            productBoard = props[PROP_PRODUCT_BOARD],
            chipName = props[PROP_CHIPNAME]
        )

        val (gpuName, gpuVendor) = detectGpu(props[PROP_EGL], props[PROP_BOARD_PLATFORM], props[PROP_PRODUCT_BOARD])

        // "The SoC has an NPU block" — every modern Qualcomm/MediaTek/Google/
        // Samsung SoC does. The NNAPI feature flag is deliberately NOT part of
        // this decision: many OEM builds (ColorOS/OneUI/etc.) omit it even on
        // NPU-equipped devices, so gating on it would lock NPU out forever.
        // NNAPI stays in the probe as informational diagnostics only.
        val npuAvailable = vendor.detected
        // The vendor dispatch library must be physically reachable for the
        // LiteRT NPU delegate to initialize — hardware presence alone is not
        // enough. An UNKNOWN vendor with a generic dispatch lib present still
        // counts as usable (the runtime resolves it).
        // Case-insensitive vendor match: Google's prebuilt dispatch libraries
        // are `libLiteRtDispatch_Qualcomm.so` (capital Q) while the vendor id
        // is lowercase — a case-sensitive compare would lock NPU out forever.
        val dispatchFound = dispatchLibraries.any { lib ->
            if (vendor.detected) lib.contains(vendor.vendorId, ignoreCase = true) else true
        }
        val npuUsable = npuAvailable && dispatchFound

        return BackendCapabilities(
            cpuAvailable = true,
            gpuAvailable = true,
            npuAvailable = npuAvailable,
            npuUsable = npuUsable,
            gpuName = gpuName,
            gpuVendor = gpuVendor,
            npuName = vendor.displayName.ifBlank { null },
            npuVendor = vendor.displayName.ifBlank { null },
            npuAccelerator = vendor.acceleratorName.ifBlank { null },
            nnApiAvailable = nnApiAvailable,
            selectedBackend = io.androllm.engine.models.BackendType.CPU,
            probedAtMs = System.currentTimeMillis()
        )
    }

    /**
     * Best-effort GPU identity from the EGL implementation string and board
     * platform heuristics. Returns (name, vendor) — both nullable.
     */
    private fun detectGpu(egl: String?, boardPlatform: String?, productBoard: String?): Pair<String?, String?> {
        val eglLower = egl?.lowercase() ?: ""
        val platformLower = (boardPlatform ?: "").lowercase() + " " + (productBoard ?: "").lowercase()
        return when {
            eglLower.contains("adreno") || platformLower.contains("adreno") -> "Adreno" to "Qualcomm"
            eglLower.contains("mali") || platformLower.contains("mali") -> "Mali" to "ARM"
            eglLower.contains("xclipse") || platformLower.contains("xclipse") -> "Xclipse" to "Samsung"
            eglLower.contains("powervr") -> "PowerVR" to "Imagination"
            eglLower.contains("apple") -> "Apple GPU" to "Apple"
            eglLower.isNotBlank() -> egl to null
            // Snapdragon platforms almost always pair with Adreno.
            platformLower.contains("qcom") || platformLower.startsWith("sm") -> "Adreno" to "Qualcomm"
            // MediaTek platforms pair with Mali (or PowerVR on older MTK).
            platformLower.startsWith("mt") -> "Mali" to "ARM"
            else -> null to null
        }
    }

    /**
     * Reads the SoC/GPU system properties via reflection on the hidden
     * `android.os.SystemProperties` class. Best-effort: every read is guarded
     * and a failure contributes nothing (all-unknown capabilities).
     */
    private fun readSystemProperties(): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val keys = listOf(
            PROP_SOC_MANUFACTURER, PROP_SOC_MODEL, PROP_HARDWARE,
            PROP_BOARD_PLATFORM, PROP_PRODUCT_BOARD, PROP_CHIPNAME, PROP_EGL
        )
        runCatching {
            val systemProperties = Class.forName("android.os.SystemProperties")
            val get = systemProperties.getMethod("get", String::class.java)
            for (key in keys) {
                val value = runCatching { get.invoke(null, key) as? String }.getOrNull()
                if (!value.isNullOrBlank()) result[key] = value
            }
        }
        return result
    }
}
