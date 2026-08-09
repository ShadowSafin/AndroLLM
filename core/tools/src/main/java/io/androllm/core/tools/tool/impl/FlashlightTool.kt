package io.androllm.core.tools.tool.impl

import android.Manifest
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import dagger.hilt.android.qualifiers.ApplicationContext
import io.androllm.core.tools.api.Tool
import io.androllm.core.tools.api.ToolCategory
import io.androllm.core.tools.api.ToolPermission
import io.androllm.core.tools.api.ToolResult
import io.androllm.core.tools.api.ToolSpec
import io.androllm.core.utils.PermissionUtils
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Toggles the camera flashlight ("turn on the torch"). */
@Singleton
class FlashlightTool @Inject constructor(
    @ApplicationContext private val context: Context
) : Tool {

    override val spec = ToolSpec(
        name = "set_flashlight",
        description = "Turn the flashlight on or off.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("on") {
                    put("type", "boolean")
                    put("description", "true to turn on, false to turn off")
                }
            }
            putJsonArray("required") { add("on") }
        },
        permission = ToolPermission.FLASHLIGHT,
        category = ToolCategory.DEVICE
    )

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val on = ToolArgs.bool(arguments, "on")
        if (!PermissionUtils.hasPermission(context, Manifest.permission.CAMERA)) {
            return ToolResult.Failure("The camera permission is required for the flashlight — enable it in Android settings.")
        }
        return runCatching {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = firstFlashCamera(cm)
                ?: return ToolResult.Failure("This device has no flashlight.")
            cm.setTorchMode(cameraId, on)
            ToolResult.Success(
                summary = if (on) "Flashlight turned on." else "Flashlight turned off.",
                data = buildJsonObject {
                    put("on", on)
                    put("status", if (on) "on" else "off")
                }
            )
        }.getOrElse {
            ToolResult.Failure("Could not toggle the flashlight: ${it.message ?: it.javaClass.simpleName}")
        }
    }

    private fun firstFlashCamera(cm: CameraManager): String? =
        cm.cameraIdList.firstOrNull { id ->
            runCatching {
                cm.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }.getOrDefault(false)
        }
}
