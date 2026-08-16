package io.androllm.engine.backend

/**
 * NPU vendor detected from the device SoC. Pure detection logic (no Android
 * dependencies) so the probe is unit-testable with plain property maps.
 *
 * The LiteRT-LM NPU backend is vendor-dispatched: the runtime reaches the NPU
 * through a per-vendor dispatch library (`libLiteRtDispatch_*.so`) plus the
 * vendor driver (QNN/QAIRT for Qualcomm, NeuroPilot for MediaTek, the Google
 * Tensor driver for Pixel). The [vendorId] maps to the dispatch library name.
 */
enum class NpuVendor(
    val displayName: String,
    val acceleratorName: String,
    /** Base name of the LiteRT dispatch library for this vendor. */
    val vendorId: String
) {
    QUALCOMM("Qualcomm", "Hexagon HTP", "qualcomm"),
    MEDIATEK("MediaTek", "NeuroPilot APU", "mediatek"),
    GOOGLE_TENSOR("Google", "Tensor TPU", "google_tensor"),
    SAMSUNG("Samsung", "Exynos NPU", "samsung"),
    UNKNOWN("", "", "");

    /** True when a concrete vendor was identified. */
    val detected: Boolean get() = this != UNKNOWN

    /**
     * Name of the dispatch `.so` LiteRT-LM loads for this vendor, e.g.
     * `libLiteRtDispatch_qualcomm.so`. Matches the upstream Bazel targets
     * `@litert//litert/vendors/<vendor>/dispatch`.
     */
    val dispatchLibraryName: String get() = "libLiteRtDispatch_$vendorId.so"

    companion object {

        /**
         * Identifies the NPU vendor from the standard Android SoC properties.
         * Every input is lower-cased; unknown/blank inputs are skipped.
         * Detection is conservative — an ambiguous or unrecognized SoC maps to
         * [UNKNOWN] and NPU stays hidden (the app behaves exactly as today).
         */
        fun detect(
            socManufacturer: String? = null,
            socModel: String? = null,
            hardware: String? = null,
            boardPlatform: String? = null,
            productBoard: String? = null,
            chipName: String? = null
        ): NpuVendor {
            val manufacturer = socManufacturer?.lowercase() ?: ""
            val model = socModel?.lowercase() ?: ""
            val hw = hardware?.lowercase() ?: ""
            val platform = boardPlatform?.lowercase() ?: ""
            val board = productBoard?.lowercase() ?: ""
            val chip = chipName?.lowercase() ?: ""

            // Most authoritative first: explicit manufacturer strings.
            if (manufacturer.contains("qualcomm") || hw.contains("qcom")) return QUALCOMM
            if (manufacturer.contains("mediatek") || platform.contains("mt")) return MEDIATEK
            if (manufacturer.contains("google")) return GOOGLE_TENSOR
            if (manufacturer.contains("samsung")) return SAMSUNG

            // SoC model codes: sm8xxx = Snapdragon, mtxxxx = Dimensity/Helio,
            // gsxxxx = Google Tensor (Pixel), exynos = Samsung.
            if (model.startsWith("sm") || chip.contains("sm")) return QUALCOMM
            if (model.startsWith("mt") || chip.startsWith("mt")) return MEDIATEK
            if (model.startsWith("gs") || model.contains("tensor")) return GOOGLE_TENSOR
            if (model.startsWith("ex") || model.contains("exynos")) return SAMSUNG

            // Board/hardware platform fallbacks.
            if (platform.contains("qcom") || platform.startsWith("sm") || hw.contains("lahaina") ||
                hw.contains("kalama") || hw.contains("pineapple") || hw.contains("taro")) {
                return QUALCOMM
            }
            if (platform.contains("mediatek") || platform.startsWith("mt") || hw.startsWith("mt")) {
                return MEDIATEK
            }
            if (platform.contains("exynos") || hw.contains("exynos")) return SAMSUNG
            if (hw.contains("tensor") || chip.contains("tensor")) return GOOGLE_TENSOR

            return UNKNOWN
        }

        /**
         * Maps the vendor back to a LiteRT-LM backend. Google Tensor uses the
         * dedicated [com.google.ai.edge.litertlm.Backend.GOOGLE_TENSOR] type;
         * every other vendor uses the generic NPU dispatch backend pointed at
         * the directory holding the vendor libraries.
         */
        fun isGoogleTensor(vendor: NpuVendor): Boolean = vendor == GOOGLE_TENSOR
    }
}
