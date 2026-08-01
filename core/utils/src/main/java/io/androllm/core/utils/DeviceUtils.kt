package io.androllm.core.utils

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import io.androllm.core.common.AppConstants

/**
 * Helpers for device capability checks used to recommend compatible models.
 */
object DeviceUtils {

    /**
     * Returns the total device RAM in GB.
     */
    fun getTotalRamGb(context: Context): Int {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        return (memoryInfo.totalMem / (1024.0 * 1024.0 * 1024.0)).toInt()
    }

    /**
     * Returns the number of CPU cores.
     */
    fun getCpuCoreCount(): Int = Runtime.getRuntime().availableProcessors()

    /**
     * Returns the device Android API level.
     */
    fun getApiLevel(): Int = Build.VERSION.SDK_INT

    /**
     * Returns the device manufacturer and model.
     */
    fun getDeviceModel(): String = "${Build.MANUFACTURER} ${Build.MODEL}".trim()

    /**
     * Checks whether the device meets the minimum RAM requirement for running models.
     */
    fun isCompatibleDevice(context: Context): Boolean =
        getTotalRamGb(context) >= AppConstants.Model.MIN_RAM_GB

    /**
     * Checks whether the device is recommended for running larger models.
     */
    fun isRecommendedDevice(context: Context): Boolean =
        getTotalRamGb(context) >= AppConstants.Model.RECOMMENDED_RAM_GB

    /**
     * Whether GPU acceleration is likely available (Vulkan-capable devices, Phase 2 will detect properly).
     */
    fun isGpuAccelerationLikelyAvailable(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
}
