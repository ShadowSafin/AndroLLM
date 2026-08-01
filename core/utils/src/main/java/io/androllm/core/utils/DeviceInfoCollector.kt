package io.androllm.core.utils

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs

data class DeviceHardwareInfo(
    val deviceName: String,
    val manufacturer: String,
    val androidVersion: String,
    val apiLevel: Int,
    val abi: String,
    val cpuCores: Int,
    val totalRamBytes: Long,
    val availableRamBytes: Long,
    val totalRamGb: Float,
    val freeStorageBytes: Long,
    val isVulkanSupported: Boolean
)

object DeviceInfoCollector {

    fun collectDeviceInfo(context: Context): DeviceHardwareInfo {
        val memoryInfo = ActivityManager.MemoryInfo()
        val activityManager = runCatching {
            context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        }.getOrNull()

        activityManager?.getMemoryInfo(memoryInfo)

        val totalRam = if (memoryInfo.totalMem > 0) memoryInfo.totalMem else 8L * 1024 * 1024 * 1024
        val availRam = if (memoryInfo.availMem > 0) memoryInfo.availMem else 4L * 1024 * 1024 * 1024
        val ramGb = totalRam.toFloat() / (1024f * 1024f * 1024f)

        val stat = runCatching { StatFs(Environment.getDataDirectory().path) }.getOrNull()
        val freeStorage = stat?.let { it.availableBlocksLong * it.blockSizeLong } ?: (32L * 1024 * 1024 * 1024)

        val vulkanSupported = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                context.packageManager?.hasSystemFeature("android.hardware.vulkan.level") == true
            } else false
        }.getOrNull() ?: false

        val primaryAbi = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                Build.SUPPORTED_ABIS?.firstOrNull() ?: "arm64-v8a"
            } else {
                @Suppress("DEPRECATION")
                Build.CPU_ABI ?: "arm64-v8a"
            }
        }.getOrNull() ?: "arm64-v8a"

        val deviceName = runCatching { Build.MODEL }.getOrNull()?.ifBlank { "Generic Device" } ?: "Generic Device"
        val manufacturer = runCatching { Build.MANUFACTURER }.getOrNull()?.ifBlank { "Android" } ?: "Android"
        val androidVersion = runCatching { Build.VERSION.RELEASE }.getOrNull()?.ifBlank { "14" } ?: "14"
        val apiLevel = runCatching { Build.VERSION.SDK_INT }.getOrNull() ?: 34
        val cores = runCatching { Runtime.getRuntime().availableProcessors() }.getOrNull() ?: 8

        return DeviceHardwareInfo(
            deviceName = deviceName,
            manufacturer = manufacturer,
            androidVersion = androidVersion,
            apiLevel = apiLevel,
            abi = primaryAbi,
            cpuCores = cores,
            totalRamBytes = totalRam,
            availableRamBytes = availRam,
            totalRamGb = ramGb,
            freeStorageBytes = freeStorage,
            isVulkanSupported = vulkanSupported
        )
    }
}
