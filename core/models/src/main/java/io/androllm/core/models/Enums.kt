package io.androllm.core.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Supported model file formats.
 */
@Serializable
enum class ModelFormat {
    @SerialName("gguf")
    GGUF,

    @SerialName("ggml")
    GGML,

    @SerialName("qnn")
    QNN,

    @SerialName("onnx")
    ONNX,

    @SerialName("safetensors")
    SAFETENSORS,

    @SerialName("pt")
    PYTORCH,

    /** LiteRT-LM container: TFLite model + tokenizer + chat template in one file. */
    @SerialName("litertlm")
    LITERTLM,

    /** Plain TensorFlow Lite flatbuffer (e.g. EmbeddingGemma `.tflite`). */
    @SerialName("tflite")
    TFLITE,

    @SerialName("unknown")
    UNKNOWN
}

/**
 * Download status of a model.
 */
@Serializable
enum class DownloadStatus {
    @SerialName("not_downloaded")
    NOT_DOWNLOADED,

    @SerialName("queued")
    QUEUED,

    @SerialName("downloading")
    DOWNLOADING,

    @SerialName("paused")
    PAUSED,

    @SerialName("downloaded")
    DOWNLOADED,

    @SerialName("error")
    ERROR
}

/**
 * Load status of a model.
 */
@Serializable
enum class ModelStatus {
    @SerialName("not_loaded")
    NOT_LOADED,

    @SerialName("loading")
    LOADING,

    @SerialName("loaded")
    LOADED,

    @SerialName("error")
    ERROR
}

/**
 * Theme modes supported by the app.
 */
@Serializable
enum class ThemeMode {
    @SerialName("system")
    SYSTEM,

    @SerialName("light")
    LIGHT,

    @SerialName("dark")
    DARK,

    /** True-black AMOLED mode — pure #000000 backgrounds for OLED displays. */
    @SerialName("amoled")
    AMOLED
}

/**
 * UI density preset for the app shell and chat. Controls spacing between
 * cards, rows and messages (8dp grid multiplied by the preset factor).
 */
@Serializable
enum class UiDensity {
    @SerialName("comfortable")
    COMFORTABLE,

    @SerialName("default")
    DEFAULT,

    @SerialName("compact")
    COMPACT
}

/**
 * Role of a chat message.
 */
@Serializable
enum class MessageRole {
    @SerialName("user")
    USER,

    @SerialName("assistant")
    ASSISTANT,

    @SerialName("system")
    SYSTEM
}
