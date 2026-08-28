package io.androllm.feature.coding.environment.proot

import java.io.File

/**
 * Locations of the bundled proot runtime.
 *
 * Android 10+ (targetSdk 29+) forbids executing files in app-writable storage,
 * so the proot binaries are packaged as *native libraries* in the APK and live
 * at runtime in `applicationInfo.nativeLibraryDir` — the one location an app is
 * allowed to `execve()` from:
 *
 *  - `libproot.so`        — the proot executable (Termux build, patched so its
 *                           NEEDED entry is `libtalloc.so`).
 *  - `libproot-loader.so` — proot's guest loader: the tiny ELF proot execs to
 *                           load guest binaries FROM MEMORY, which is how guest
 *                           programs in the (noexec) rootfs can run at all.
 *  - `libtalloc.so`       — the talloc library proot links against.
 *  - `libandroid-shmem.so`— System V shared-memory emulation proot needs
 *                           (Android's libc has no shmget/shmat).
 *
 * The guest rootfs (Debian) itself lives in app storage — it only needs to be
 * READ, because the loader maps guest ELFs into memory instead of exec'ing them.
 */
class ProotFiles(
    val nativeLibDir: File,
    val rootfsDir: File,
    val tmpDir: File
) {
    val prootBinary: File get() = File(nativeLibDir, "libproot.so")
    val loader: File get() = File(nativeLibDir, "libproot-loader.so")
    val talloc: File get() = File(nativeLibDir, "libtalloc.so")
    val shmem: File get() = File(nativeLibDir, "libandroid-shmem.so")

    /**
     * The guest name-resolution shim (see tools/guestshim.c). Packaged as a
     * native library so it lands in [nativeLibDir]; it is copied into the rootfs
     * and LD_PRELOADed into guest processes so guest tools (apt/npm/pip/git) get
     * a self-contained getaddrinfo/getifaddrs — stock libc resolver paths can
     * fail with EACCES under proot on Android while raw sockets work fine.
     */
    val shim: File get() = File(nativeLibDir, "libguestshim.so")

    /** Where the shim lives inside the guest (absolute guest path for LD_PRELOAD). */
    val shimInRootfs: File get() = File(rootfsDir, "usr/lib/libguestshim.so")

    /** Guest-absolute path used in the LD_PRELOAD assignment. */
    val shimGuestPath: String get() = "/usr/lib/libguestshim.so"

    /**
     * Ensures the shim is present inside the rootfs (copies it from
     * [nativeLibDir] when missing or stale). Idempotent; safe to call per run.
     * Returns true when the shim is available to the guest.
     */
    fun ensureShimInRootfs(): Boolean {
        val src = shim
        if (!src.exists()) return false
        val dst = shimInRootfs
        runCatching {
            if (!dst.exists() || dst.length() != src.length()) {
                dst.parentFile?.mkdirs()
                src.copyTo(dst, overwrite = true)
            }
        }.onFailure { return false }
        return dst.exists()
    }

    /** True when the executable side is present (rootfs readiness is tracked separately). */
    fun binariesPresent(): Boolean = prootBinary.exists() && loader.exists()
}
