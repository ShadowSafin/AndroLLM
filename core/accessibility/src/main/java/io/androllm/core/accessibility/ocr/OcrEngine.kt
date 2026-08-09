package io.androllm.core.accessibility.ocr

import android.graphics.Bitmap

/**
 * On-device OCR, used by the screen analyzer as a vision fallback when the
 * accessibility tree is missing or unhelpful (custom-drawn UIs, games,
 * images). The engine is pluggable: the app currently ships the
 * [NoopOcrEngine]; future releases can bind a real model (e.g. a GGML/ONNX
 * OCR build) without touching the rest of the framework.
 */
interface OcrEngine {

    /** True when a real model is available and initialized. */
    val isAvailable: Boolean

    /** Recognized text lines, top to bottom. */
    fun recognize(bitmap: Bitmap): List<String>
}

/** Placeholder engine — OCR is wired but disabled until a model ships. */
object NoopOcrEngine : OcrEngine {
    override val isAvailable: Boolean = false
    override fun recognize(bitmap: Bitmap): List<String> = emptyList()
}
