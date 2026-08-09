package io.androllm.core.tools.tool.impl

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import io.androllm.core.tools.api.Tool
import io.androllm.core.tools.api.ToolCategory
import io.androllm.core.tools.api.ToolPermission
import io.androllm.core.tools.api.ToolResult
import io.androllm.core.tools.api.ToolSpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Best-effort Bluetooth toggle. Programmatic enable/disable only works on
 * older Android (the classic API is deprecated/blocked on modern releases);
 * on blocked devices the tool falls back to opening the Bluetooth settings
 * panel and reports the state honestly.
 */
@Singleton
class BluetoothTool @Inject constructor(
    @ApplicationContext private val context: Context
) : Tool {

    override val spec = ToolSpec(
        name = "set_bluetooth",
        description = "Turn Bluetooth on or off. On newer Android versions the OS may require opening the settings panel instead.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("enabled") {
                    put("type", "boolean")
                    put("description", "true to enable, false to disable")
                }
            }
            putJsonArray("required") { add("enabled") }
        },
        permission = ToolPermission.BLUETOOTH,
        category = ToolCategory.DEVICE
    )

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val enabled = ToolArgs.bool(arguments, "enabled")
        val adapter = runCatching { BluetoothAdapter.getDefaultAdapter() }.getOrNull()
        if (adapter == null) {
            return ToolResult.Failure("This device has no Bluetooth.")
        }
        val result = runCatching {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                when {
                    enabled && !adapter.isEnabled -> adapter.enable()
                    !enabled && adapter.isEnabled -> adapter.disable()
                    else -> true
                }
            } else {
                false // blocked by the OS on API 33+
            }
        }.getOrDefault(false)

        if (result) {
            return ToolResult.Success(
                summary = if (enabled) "Bluetooth enabled." else "Bluetooth disabled.",
                data = buildJsonObject {
                    put("enabled", enabled)
                    put("status", if (enabled) "on" else "off")
                }
            )
        }
        // Modern Android blocks programmatic toggling → open the settings panel.
        val opened = ToolIntents.launch(context, Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS))
        return if (opened) {
            ToolResult.Success(
                summary = "This Android version blocks apps from toggling Bluetooth — I opened the Bluetooth settings for you.",
                data = buildJsonObject {
                    put("enabled", enabled)
                    put("status", "settings-opened")
                }
            )
        } else {
            ToolResult.Failure("Could not change Bluetooth — toggle it manually in Android settings.")
        }
    }
}
