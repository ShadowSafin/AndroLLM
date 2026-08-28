package io.androllm.feature.coding.environment.proot

/**
 * Pure builder for the proot invocation. Kept free of Android/process types so
 * the exact command line + environment are unit-testable on the JVM.
 *
 * How a command runs:
 * ```
 * libproot.so --kill-on-death -0 -r <rootfs>
 *             -b /proc -b /dev -b /sys
 *             -b <workspace>:<workspace>
 *             -w <workspace>
 *             /bin/sh -c "<command>"
 * ```
 *
 *  - `--kill-on-exit` (Termux patch): guest processes are killed when the main
 *    command exits — cancellation cannot leak orphaned servers.
 *  - `-0`: the guest sees itself as root, so `apt-get install`, `npm i -g`, pip work.
 *  - `-b <workspace>:<workspace>`: the workspace is visible at the SAME absolute
 *    path inside the guest, so the sandboxed file tools (host java.io) and the
 *    shell (guest) always agree on paths.
 *  - The guest loader is pointed at via `PROOT_LOADER` (overrides the Termux
 *    prefix path compiled into the binary). Seccomp acceleration is left ON
 *    (default) — forcing pure ptrace via PROOT_NO_SECCOMP breaks guest network.
 */
object ProotCommandLine {

    /** argv for running [command] inside the rootfs with [workspacePath] attached. */
    fun argv(
        prootBinary: String,
        rootfs: String,
        workspacePath: String,
        command: String
    ): List<String> = listOf(
        prootBinary,
        "--kill-on-exit",
        "-0",
        "-r", rootfs,
        "-b", "/proc",
        "-b", "/dev",
        "-b", "/sys",
        "-b", "$workspacePath:$workspacePath",
        "-w", workspacePath,
        "/bin/sh", "-c", command
    )

    /**
     * Environment for the proot process itself. [nativeLibDir] must contain
     * `libproot-loader.so` and `libtalloc.so`.
     */
    fun env(
        nativeLibDir: String,
        loaderPath: String,
        tmpDir: String
    ): Map<String, String> = mapOf(
        // proot finds its guest loader here (not the compiled-in Termux path).
        "PROOT_LOADER" to loaderPath,
        // NOTE: we deliberately do NOT set PROOT_NO_SECCOMP. With it set, proot
        // falls back to pure ptrace and intercepts EVERY guest syscall, which on
        // Android breaks guest networking (socket/connect/sendto get mangled ->
        // `apk`/`npm` fail with "Permission denied"). In the default seccomp mode
        // proot only stops on syscalls it must translate, so network syscalls pass
        // through natively and guest networking works (same as Termux's proot). If
        // seccomp is unavailable proot logs a warning and degrades on its own.
        // Lets proot's dynamic linker find the bundled libtalloc.so and
        // libandroid-shmem.so that live next to it in nativeLibraryDir.
        "LD_LIBRARY_PATH" to nativeLibDir,
        "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
        "HOME" to "/root",
        "USER" to "root",
        "TMPDIR" to "/tmp",
        "LANG" to "C.UTF-8",
        "TERM" to "dumb",
        // Keep debconf/apt non-interactive for every guest command.
        "DEBIAN_FRONTEND" to "noninteractive",
        // proot keeps scratch files here (must be writable by the app).
        "PROOT_TMP_DIR" to tmpDir
    )
}
