package io.androllm.core.tools.tool.impl

import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
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
 * Best-effort Wi-Fi toggle. `setWifiEnabled` is deprecated and blocked on
 * modern Android (API 29+); the tool then opens the Wi-Fi settings panel and
 * reports the state honestly, just like [BluetoothTool].
 */
@Singleton
class WifiTool @Inject constructor(
    @ApplicationContext private val context: Context
) : Tool {

    override val spec = ToolSpec(
        name = "set_wifi",
        description = "Turn Wi-Fi on or off. On newer Android versions the OS may require opening the settings panel instead.",
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
        permission = ToolPermission.WIFI,
        category = ToolCategory.DEVICE
    )

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val enabled = ToolArgs.bool(arguments, "enabled")
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val result = runCatching {
            if (wifi.isWifiEnabled != enabled) wifi.setWifiEnabled(enabled) else true
        }.getOrDefault(false)

        if (result) {
            return ToolResult.Success(
                summary = if (enabled) "Wi-Fi enabled." else "Wi-Fi disabled.",
                data = buildJsonObject {
                    put("enabled", enabled)
                    put("status", if (enabled) "on" else "off")
                }
            )
        }
        val opened = ToolIntents.launch(context, Intent(android.provider.Settings.ACTION_WIFI_SETTINGS))
        return if (opened) {
            ToolResult.Success(
                summary = "This Android version blocks apps from toggling Wi-Fi — I opened the Wi-Fi settings for you.",
                data = buildJsonObject {
                    put("enabled", enabled)
                    put("status", "settings-opened")
                }
            )
        } else {
            ToolResult.Failure("Could not change Wi-Fi — toggle it manually in Android settings.")
        }
    }
}
