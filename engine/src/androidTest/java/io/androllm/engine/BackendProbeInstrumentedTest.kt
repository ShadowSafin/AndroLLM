package io.androllm.engine

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.androllm.engine.backend.HardwareBackendProbe
import io.androllm.engine.backend.NpuVendor
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Diagnostic: dumps exactly what the startup probe sees on this device —
 * native library dir contents, SoC properties and the resulting
 * [io.androllm.engine.backend.BackendCapabilities]. Answers \"why is NPU not
 * active\" with device evidence.
 */
@RunWith(AndroidJUnit4::class)
class BackendProbeInstrumentedTest {

    private val context get() =
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext

    @Test
    fun dumpProbeEvidence() {
        val nativeDir = runCatching { context.applicationInfo.nativeLibraryDir }.getOrNull()
        val files = runCatching { File(nativeDir ?: "").list()?.toList() ?: emptyList() }.getOrDefault(emptyList())
        Log.i(TAG, "nativeLibraryDir=$nativeDir")
        Log.i(TAG, "native libs (${files.size}): ${files.sorted().joinToString(", ")}")
        Log.i(TAG, "dispatch libs present: ${files.filter { it.contains("Dispatch") || it.contains("dispatch") }}")
        Log.i(TAG, "QNN/QAIRT libs present: ${files.filter { it.contains("Qnn", ignoreCase = true) }}")

        val props = listOf("ro.soc.manufacturer", "ro.soc.model", "ro.hardware", "ro.board.platform", "ro.product.board", "ro.hardware.chipname", "ro.hardware.egl")
        for (p in props) {
            Log.i(TAG, "prop $p=${prop(p)}")
        }
        // Read the same properties the probe reads.
        Log.i(TAG, "vendor detected=${NpuVendor.detect(
            socManufacturer = prop("ro.soc.manufacturer"),
            socModel = prop("ro.soc.model"),
            hardware = prop("ro.hardware"),
            boardPlatform = prop("ro.board.platform"),
            productBoard = prop("ro.product.board"),
            chipName = prop("ro.hardware.chipname")
        )}")

        val caps = HardwareBackendProbe.probe(context)
        Log.i(
            TAG,
            "PROBE RESULT: cpuAvailable=${caps.cpuAvailable} gpuAvailable=${caps.gpuAvailable} " +
                "npuAvailable=${caps.npuAvailable} npuUsable=${caps.npuUsable} nnApi=${caps.nnApiAvailable} " +
                "gpu=${caps.gpuName}/${caps.gpuVendor} npu=${caps.npuVendor}/${caps.npuAccelerator} " +
                "selected=${caps.selectedBackend}"
        )
    }

    private fun prop(key: String): String? = runCatching {
        val clazz = Class.forName("android.os.SystemProperties")
        clazz.getMethod("get", String::class.java).invoke(null, key) as? String
    }.getOrNull()?.takeIf { it.isNotBlank() }

    companion object {
        private const val TAG = "BackendProbeTest"
    }
}
