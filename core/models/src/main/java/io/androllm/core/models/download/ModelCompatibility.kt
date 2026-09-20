package io.androllm.core.models.download

import io.androllm.core.models.catalog.CatalogModel
import io.androllm.core.models.catalog.ModelMetadataRegistry
import io.androllm.core.models.catalog.SupportedArchitectures

/**
 * Pre-download compatibility gate (req §7).
 *
 * Rejects models that can never run on this runtime BEFORE any byte is
 * downloaded: wrong container, unregistered container type, unsupported
 * architecture, empty backend set, or a chat model shipped as raw `.tflite`
 * (the native engine fails those with an opaque INVALID_ARGUMENT after
 * minutes of init — fail fast here instead with a compatible suggestion).
 *
 * Pure JVM — unit-testable, no Android dependencies.
 */
object ModelCompatibility {

    data class GateResult(
        val compatible: Boolean,
        val reason: String = "",
        /** Id of a compatible alternative when known (e.g. a .litertlm twin). */
        val suggestedModelId: String? = null,
    )

    fun check(model: CatalogModel): GateResult {
        val lower = model.fileName.lowercase()
        val isLitertlm = lower.endsWith(".litertlm")
        val isTflite = lower.endsWith(".tflite")

        if (!isLitertlm && !isTflite) {
            val ext = lower.substringAfterLast('.', "<none>")
            return GateResult(
                compatible = false,
                reason = "'${model.fileName}' uses .$ext — this runtime executes " +
                    ".litertlm containers (chat/vision) and .tflite flatbuffers " +
                    "(embedding/speech) only. GGUF/ONNX/MLC/SafeTensors artifacts " +
                    "cannot be loaded. Pick a LiteRT build of the same model.",
            )
        }

        if (isLitertlm) {
            if (model.containerType.isNullOrBlank()) {
                return GateResult(
                    compatible = false,
                    reason = "Missing containerType: the .litertlm container identifier " +
                        "is required to route the model to its engine family.",
                )
            }
            if (!ModelMetadataRegistry.isKnownContainerType(model.containerType)) {
                return GateResult(
                    compatible = false,
                    reason = "containerType '${model.containerType}' is not a registered " +
                        "LlmModelType. The container may predate this runtime — " +
                        "install a model whose container type is registered.",
                )
            }
        }

        if (model.architecture.isNotBlank() && !SupportedArchitectures.isSupported(model.architecture)) {
            return GateResult(
                compatible = false,
                reason = "Architecture '${model.architecture}' is not supported by this runtime.",
            )
        }
        val familySpec = ModelMetadataRegistry.familyFor(model.family)
        if (familySpec == null) {
            return GateResult(
                compatible = false,
                reason = "Family '${model.family}' is unknown to the metadata registry.",
            )
        }

        if (model.supportedBackends.mapNotNull { parseBackend(it) }.isEmpty()) {
            return GateResult(
                compatible = false,
                reason = "No runnable backend: supportedBackends " +
                    "${model.supportedBackends} parses to nothing this runtime " +
                    "executes (CPU / VULKAN).",
            )
        }
        if (!model.supportsCpu && !model.supportsGpu && !model.supportsNpu) {
            return GateResult(
                compatible = false,
                reason = "All backend flags are off — the model declares no engine it can run on.",
            )
        }

        // Chat pipeline needs the .litertlm container; raw .tflite chat entries
        // fail native init. Speech/embedding pipelines accept .tflite.
        if ("CHAT" in model.categories && isTflite) {
            return GateResult(
                compatible = false,
                reason = "Chat models must be .litertlm containers — .tflite is only " +
                    "supported for embedding/speech pipelines. Choose the .litertlm " +
                    "variant of this model.",
            )
        }

        if (model.downloadUrl.isBlank() ||
            !(model.downloadUrl.startsWith("https://") || model.downloadUrl.startsWith("http://"))
        ) {
            return GateResult(
                compatible = false,
                reason = "Download URL is missing or not http(s) — nothing can be fetched.",
            )
        }

        return GateResult(compatible = true)
    }

    /**
     * Parses a backend label the way the runtime does. Accepts the canonical
     * CPU/VULKAN plus the legacy "GPU" alias old catalogs shipped (the LiteRT
     * GPU delegate runs on Vulkan, so GPU ≡ VULKAN).
     */
    fun parseBackend(value: String): String? = when (value.trim().uppercase()) {
        "CPU" -> "CPU"
        "VULKAN", "GPU", "VULKAN GPU" -> "VULKAN"
        else -> null
    }
}
