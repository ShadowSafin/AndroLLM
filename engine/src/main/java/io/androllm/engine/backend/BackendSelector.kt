package io.androllm.engine.backend

import io.androllm.core.models.ModelFormat
import io.androllm.engine.models.BackendType

/**
 * Intelligent hardware-aware selector that determines the fastest compatible backend.
 */
object BackendSelector {

    /**
     * Selects the optimal backend based on target device hardware and model format.
     */
    fun selectOptimalBackend(
        hardwareInfo: EngineHardwareInfo,
        modelFormat: ModelFormat = ModelFormat.GGUF,
        userPreferredBackend: BackendType? = null
    ): BackendSelectionResult {
        if (userPreferredBackend != null) {
            return BackendSelectionResult(
                selectedBackend = userPreferredBackend,
                reason = "User explicitly requested ${userPreferredBackend.name}",
                acceleratorName = getAcceleratorName(userPreferredBackend)
            )
        }

        // Priority 1: Qualcomm QNN on Snapdragon devices with QNN/Hexagon compatible models
        val isSnapdragon = hardwareInfo.cpuCores >= 8 && hardwareInfo.abi.contains("arm64", ignoreCase = true)
        if (isSnapdragon && modelFormat == ModelFormat.QNN) {
            return BackendSelectionResult(
                selectedBackend = BackendType.QUALCOMM_QNN,
                reason = "Qualcomm Snapdragon NPU detected with QNN model",
                acceleratorName = "Qualcomm Hexagon NPU & HTP Vector eXtensions"
            )
        }

        // Priority 2: GGUF Format + Vulkan GPU Acceleration (FORCE PREFERRED)
        // Vulkan is ALWAYS preferred on supported devices. CPU is ONLY used if Vulkan genuinely fails.
        if ((modelFormat == ModelFormat.GGUF || modelFormat == ModelFormat.GGML)) {
            if (hardwareInfo.isVulkanSupported) {
                return BackendSelectionResult(
                    selectedBackend = BackendType.LLAMA_CPP_VULKAN,
                    reason = "GGUF format detected with Vulkan compute shader acceleration",
                    acceleratorName = "Qualcomm Adreno GPU (Vulkan 1.3)"
                )
            }
            // Even if Vulkan not yet confirmed, try Vulkan first on ARM64 Snapdragon devices
            val isSnapdragon = hardwareInfo.cpuCores >= 8 && hardwareInfo.abi.contains("arm64", ignoreCase = true)
            if (isSnapdragon) {
                return BackendSelectionResult(
                    selectedBackend = BackendType.LLAMA_CPP_VULKAN,
                    reason = "Snapdragon device detected - attempting Vulkan GPU acceleration",
                    acceleratorName = "Qualcomm Adreno GPU (Vulkan 1.3)"
                )
            }
        }

        // Priority 3: ONNX Runtime
        if (modelFormat == ModelFormat.ONNX) {
            return BackendSelectionResult(
                selectedBackend = BackendType.ONNX_RUNTIME,
                reason = "ONNX model format detected with NNAPI/QNN Execution Provider",
                acceleratorName = "ONNX Runtime NNAPI Execution Provider"
            )
        }

        // Priority 4: CPU Fallback
        return BackendSelectionResult(
            selectedBackend = BackendType.CPU,
            reason = "Standard ARM64 NEON CPU inference backend",
            acceleratorName = "ARM Cortex Big-Core SIMD Vector Execution Engine"
        )
    }

    private fun getAcceleratorName(backend: BackendType): String {
        return when (backend) {
            BackendType.QUALCOMM_QNN -> "Qualcomm Hexagon NPU & HTP"
            BackendType.LLAMA_CPP_VULKAN, BackendType.VULKAN -> "Qualcomm Adreno GPU (Vulkan Compute)"
            BackendType.ONNX_RUNTIME -> "ONNX Runtime (NNAPI/QNN)"
            BackendType.CPU -> "ARM64 NEON CPU"
        }
    }
}

data class BackendSelectionResult(
    val selectedBackend: BackendType,
    val reason: String,
    val acceleratorName: String
)
