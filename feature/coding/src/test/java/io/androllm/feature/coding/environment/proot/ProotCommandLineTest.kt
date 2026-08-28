package io.androllm.feature.coding.environment.proot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The proot invocation must be exactly right or nothing runs — pin it down. */
class ProotCommandLineTest {

    @Test
    fun `argv runs sh -c inside the rootfs with workspace bound at the same path`() {
        val argv = ProotCommandLine.argv(
            prootBinary = "/lib/libproot.so",
            rootfs = "/data/rootfs",
            workspacePath = "/data/ws/abc",
            command = "npm run dev"
        )
        assertEquals("/lib/libproot.so", argv.first())
        assertTrue("--kill-on-exit must be set so cancellation kills guests", "--kill-on-exit" in argv)
        assertTrue("-0 (fake root) required for apk/npm -g", "-0" in argv)
        // rootfs
        val rIdx = argv.indexOf("-r")
        assertEquals("/data/rootfs", argv[rIdx + 1])
        // workspace bound at the SAME absolute path so file tools and shell agree
        assertTrue("/data/ws/abc:/data/ws/abc" in argv)
        // working dir
        val wIdx = argv.indexOf("-w")
        assertEquals("/data/ws/abc", argv[wIdx + 1])
        // command tail
        assertEquals("/bin/sh", argv[argv.size - 3])
        assertEquals("-c", argv[argv.size - 2])
        assertEquals("npm run dev", argv.last())
    }

    @Test
    fun `argv binds proc dev sys`() {
        val argv = ProotCommandLine.argv("/p", "/r", "/w", "ls")
        val binds = argv.withIndex()
            .filter { it.value == "-b" }
            .map { argv[it.index + 1] }
        assertTrue("/proc" in binds)
        assertTrue("/dev" in binds)
        assertTrue("/sys" in binds)
        assertTrue(binds.any { it.startsWith("/w:/w") })
    }

    @Test
    fun `env points loader at the bundled binary and keeps seccomp on`() {
        val env = ProotCommandLine.env(
            nativeLibDir = "/data/app/lib",
            loaderPath = "/data/app/lib/libproot-loader.so",
            tmpDir = "/data/tmp"
        )
        assertEquals("/data/app/lib/libproot-loader.so", env["PROOT_LOADER"])
        // PROOT_NO_SECCOMP must NOT be set: pure-ptrace mode breaks guest network.
        assertTrue("PROOT_NO_SECCOMP must be absent (seccomp left on)", !env.containsKey("PROOT_NO_SECCOMP"))
        assertEquals("/data/app/lib", env["LD_LIBRARY_PATH"])
        assertEquals("/root", env["HOME"])
        assertEquals("/tmp", env["TMPDIR"])
        assertTrue((env["PATH"] ?: "").contains("/usr/bin"))
    }
}
