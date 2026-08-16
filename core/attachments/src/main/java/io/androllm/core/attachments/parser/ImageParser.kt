package io.androllm.core.attachments.parser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import io.androllm.core.attachments.model.AttachmentType
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** OCR backend abstraction so parsers stay testable. */
interface OcrEngine {
    /** OCRs one rendered PDF page; returns text or null on failure. */
    fun ocrPdfPage(file: File, pageIndex: Int): String?
}

/**
 * On-device OCR via ML Kit's bundled Latin text recognizer (no Google Play
 * Services required — the model ships inside the AAR). Used for image
 * attachments and scanned PDF pages.
 */
class MlKitOcrEngine(private val context: Context) : OcrEngine {

    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    /** OCRs a bitmap; returns the recognized text or null on failure. */
    suspend fun ocrBitmap(bitmap: Bitmap): String? = withContext(Dispatchers.Default) {
        suspendCancellableCoroutine { cont ->
            val image = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(image)
                .addOnSuccessListener { result ->
                    val text = result.text?.trim().orEmpty()
                    if (text.isEmpty()) cont.resume(null) else cont.resume(text)
                }
                .addOnFailureListener { cont.resumeWithException(it) }
        }
    }

    /** OCRs an image file (decoded at a memory-safe sample size). */
    suspend fun ocrImageFile(file: File): String? = withContext(Dispatchers.IO) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val sample = computeSampleSize(bounds.outWidth, bounds.outHeight, 2048)
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = BitmapFactory.decodeFile(file.absolutePath, options) ?: return@withContext null
        try {
            ocrBitmap(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    /** OCRs a content URI (HEIC/webp decode handled by the system codec). */
    suspend fun ocrUri(uri: Uri): String? = withContext(Dispatchers.IO) {
        val bitmap = runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream)
            }
        }.getOrNull() ?: return@withContext null
        try {
            ocrBitmap(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    /** Implements [OcrEngine]: renders the PDF page and OCRs it. */
    override fun ocrPdfPage(file: File, pageIndex: Int): String? = try {
        val pdf = PdfParser(context, this)
        val bitmap = pdf.renderPage(file, pageIndex) ?: return null
        try {
            kotlinx.coroutines.runBlocking { ocrBitmap(bitmap) }
        } finally {
            bitmap.recycle()
        }
    } catch (_: Exception) {
        null
    }

    private fun computeSampleSize(width: Int, height: Int, maxDim: Int): Int {
        if (width <= 0 || height <= 0) return 1
        var sample = 1
        while (width / sample > maxDim || height / sample > maxDim) sample *= 2
        return sample
    }
}

/**
 * Image attachment parser: decodes the image and runs it through the OCR
 * engine so non-vision providers still get readable text from pictures.
 */
class ImageParser(
    private val ocr: MlKitOcrEngine
) : DocumentParser {

    override val format = AttachmentType.IMAGE
    override fun supports(file: File): Boolean =
        file.extension.lowercase() in setOf("png", "jpg", "jpeg", "heic", "webp", "bmp", "gif")

    override fun parse(file: File, ocrLanguage: String): ParsedDocument {
        val text = kotlinx.coroutines.runBlocking { ocr.ocrImageFile(file) }
            ?: throw IllegalArgumentException("OCR produced no text for image")
        val normalized = TextParsers.normalize(text)
        if (normalized.isEmpty()) throw IllegalArgumentException("OCR produced no text for image")
        return ParsedDocument(
            text = normalized,
            pages = listOf(normalized),
            pageCount = 1,
            fromOcr = true
        )
    }
}
