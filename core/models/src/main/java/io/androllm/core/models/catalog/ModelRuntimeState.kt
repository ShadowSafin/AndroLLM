package io.androllm.core.models.catalog

/**
 * Lifecycle state of an installed model in the storage-streaming runtime.
 *
 * "Use Model" is only offered in [READY]; every earlier state means the
 * button is disabled with an explanatory label. State lives with the
 * installed model (model manager / datastore), not inside catalog JSON.
 */
enum class ModelRuntimeState(val label: String) {
    AVAILABLE("Available"),
    DOWNLOADING("Downloading"),
    PAUSED("Paused"),
    VERIFYING("Verifying checksum"),
    INDEXING("Indexing tensors"),
    READY("Ready"),
    INCOMPATIBLE("Incompatible"),
    CORRUPTED("Corrupted"),
    DELETING("Deleting"),
    ACTIVE("In use"),
    ;

    val isReady: Boolean get() = this == READY || this == ACTIVE
    val isBusy: Boolean get() = this in setOf(DOWNLOADING, PAUSED, VERIFYING, INDEXING, DELETING)

    companion object {
        fun fromValue(value: String): ModelRuntimeState =
            entries.firstOrNull { it.name == value || it.label == value } ?: AVAILABLE
    }
}

/**
 * Inference backends the local runtime exposes. **Only CPU and Vulkan exist** —
 * QNN/NPU/Hexagon/HTP/NNAPI are intentionally absent from this catalog.
 */
enum class RuntimeBackend(val displayName: String) {
    CPU("CPU"),
    VULKAN("Vulkan GPU");

    companion object {
        fun fromValue(value: String): RuntimeBackend? =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) || it.displayName.equals(value, ignoreCase = true) }
    }
}
