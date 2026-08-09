package io.androllm.core.accessibility.tools

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import io.androllm.core.accessibility.controller.AccessibilityController
import io.androllm.core.tools.api.Tool
import io.androllm.core.tools.api.ToolCategory
import io.androllm.core.tools.api.ToolPermission
import io.androllm.core.tools.api.ToolResult
import io.androllm.core.tools.api.ToolSpec
import io.androllm.core.tools.tool.impl.ToolArgs
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Scans for QR codes. Two sources:
 *
 * 1. The current screen (accessibility screenshot, API 30+): point the
 *    camera at a code is not needed — if the code is ON SCREEN (WhatsApp Web,
 *    a website, an image), it is read directly.
 * 2. [image_path]: an image file path the user already has.
 *
 * Decoding runs on a background dispatcher (CPU-heavy pixel work).
 */
@Singleton
class QrScanTool @Inject constructor(
    private val controller: AccessibilityController
) : Tool {

    override val spec = ToolSpec(
        name = "scan_qr",
        description = "Read a QR code currently visible on the screen (e.g. a login code on a website), or from an image file path. Returns the decoded text.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("image_path") { put("type", "string"); put("description", "Optional: decode from this image file instead of the screen" ) }
            }
        },
        permission = ToolPermission.QR,
        category = ToolCategory.DEVICE
    )

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val imagePath = ToolArgs.str(arguments, "image_path", "path", "image")
        val bitmap = if (imagePath != null) {
            // Heavy decode off the main thread (tools run in the chat's scope).
            val file = File(imagePath)
            if (!file.exists()) return ToolResult.Failure("Image file not found: $imagePath")
            withContext(Dispatchers.IO) {
                runCatching { BitmapFactory.decodeFile(imagePath) }.getOrNull()
            } ?: return ToolResult.Failure("Could not decode the image at $imagePath.")
        } else {
            if (!controller.isConnected) {
                return ToolResult.Failure(notConnected())
            }
            controller.captureScreenshot()
                ?: return ToolResult.Failure("Could not capture the screen (needs Android 11+ and the UI Automation service).")
        }

        val text = decode(bitmap)
        if (text == null) {
            return ToolResult.Success("No QR code found in the ${if (imagePath != null) "image" else "screen"}.")
        }
        return ToolResult.Success(
            "QR code: $text",
            buildJsonObject {
                put("text", text)
                putJsonArray("content_types") { add(contentType(text)) }
            }
        )
    }

    private suspend fun decode(bitmap: Bitmap): String? = withContext(Dispatchers.IO) {
        runCatching {
            val w = bitmap.width
            val h = bitmap.height
            val pixels = IntArray(w * h)
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
            val source = RGBLuminanceSource(w, h, pixels)
            val binaryBitmap = BinaryBitmap(HybridBinarizer(source))
            MultiFormatReader().decode(binaryBitmap)?.text
        }.getOrNull()
    }

    private fun contentType(text: String): String = when {
        text.startsWith("http://") || text.startsWith("https://") -> "url"
        text.startsWith("mailto:") -> "email"
        text.startsWith("tel:") -> "phone"
        text.startsWith("WIFI:") -> "wifi"
        else -> "text"
    }

    private fun notConnected(): String =
        "The UI Automation service is not enabled. Open Settings → Accessibility → AndroLLM UI Automation and turn it on, then try again."
}
