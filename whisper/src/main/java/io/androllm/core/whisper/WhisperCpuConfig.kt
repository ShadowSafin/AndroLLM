package io.androllm.core.whisper

import android.util.Log
import java.io.BufferedReader
import java.io.FileReader

/**
 * Picks a sensible default thread count for whisper inference based on the
 * device's high-performance CPU cores (mirrors the official whisper.android
 * example).
 */
object WhisperCpuConfig {
    private const val TAG = "WhisperCpuConfig"

    /** Picks a sensible thread count for whisper inference (max 8). */
    val preferredThreadCount: Int
        get() {
            val cores = Runtime.getRuntime().availableProcessors()
            // The frequency/variant binning below is unreliable on many SoCs
            // (all cores report the same freq) and returns 0; prefer a robust
            // value based on core count so whisper isn't glued to 2 threads.
            val fromCores = ((cores - 2).coerceAtLeast(2))
            val detected = runCatching { highPerfCpuCount() }.getOrDefault(0)
            val choice = if (detected >= 4) detected else fromCores
            return choice.coerceIn(2, 8)
        }

    private fun highPerfCpuCount(): Int {
        return try {
            CpuInfo(readCpuInfo()).highPerfCount()
        } catch (e: Exception) {
            Log.d(TAG, "Couldn't read CPU info", e)
            // Best guess: total cores minus 4.
            (Runtime.getRuntime().availableProcessors() - 4).coerceAtLeast(0).coerceAtLeast(1)
        }
    }

    private fun readCpuInfo(): List<String> {
        return BufferedReader(FileReader("/proc/cpuinfo")).useLines { it.toList() }
    }

    private class CpuInfo(private val lines: List<String>) {
        fun highPerfCount(): Int = try {
            byFrequency()
        } catch (e: Exception) {
            byVariant()
        }

        private fun byFrequency(): Int {
            val freqMap = getCpuValues("processor") { getMaxCpuFrequency(it.toInt()) }
            val min = freqMap.minOrNull() ?: return 0
            Log.d(TAG, "CPU freq bins: ${freqMap.groupingBy { it }.eachCount()}")
            return freqMap.count { it > min }
        }

        private fun byVariant(): Int {
            val variants = getCpuValues("CPU variant") { it.substringAfter("0x").toInt(radix = 16) }
            val min = variants.minOrNull() ?: return 0
            Log.d(TAG, "CPU variant bins: ${variants.groupingBy { it }.eachCount()}")
            return variants.count { it == min }
        }

        private fun getCpuValues(property: String, mapper: (String) -> Int): List<Int> =
            lines.asSequence()
                .filter { it.startsWith(property) }
                .map { mapper(it.substringAfter(':').trim()) }
                .sorted()
                .toList()

        private fun getMaxCpuFrequency(cpuIndex: Int): Int {
            val path = "/sys/devices/system/cpu/cpu${cpuIndex}/cpufreq/cpuinfo_max_freq"
            return BufferedReader(FileReader(path)).use { it.readLine().trim().toInt() }
        }
    }
}