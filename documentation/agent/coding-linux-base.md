# Coding Agent — Real Linux Base (proot + Debian)

The AI Agent Coding Chat runs commands inside a **real Linux environment** so
`npm`, `python`, `git`, `go`, build tools, etc. genuinely execute — not
placeholders. This document explains the architecture and how to verify it on a
physical device.

## Why this is non-trivial on Android

Android 10+ (this app targets SDK 36) **forbids executing any file in
app-writable storage** (`/data/data/io.androllm.app/files/...`). It is a
SELinux/W^X rule, not a file-permission issue — `chmod +x` does not help. A
downloaded `node` binary placed there fails with `Permission denied` on
`execve()`. The only location an app may execute code from is its **native
library directory** (libraries extracted from the APK).

Two consequences shape the design:

1. **proot itself** must live in the native library dir (it is packaged as
   `libproot.so`).
2. **Guest programs** (node, python, ...) live in a rootfs in app storage, which
   is noexec. They run anyway because **proot's loader maps guest ELFs into
   memory** instead of `execve()`-ing them. This is the same mechanism
   Termux/UserLAnd use to run full Linux on non-rooted Android.

## Architecture

```
feature/coding/src/main/jniLibs/arm64-v8a/
  libproot.so          proot executable (Termux build, DT_NEEDED patched to libtalloc.so)
  libproot-loader.so   guest loader proot execs to load guest ELFs from memory
  libtalloc.so         talloc library proot links against
  libandroid-shmem.so  System V shm emulation proot needs (Bionic lacks shmget)
  libguestshim.so      guest LD_PRELOAD shim (see below), copied into the rootfs
```

The rootfs is **Debian trixie (arm64)** — the official Linux Containers image
(~90 MB `.tar.xz`, SHA-256 pinned in `DebianRootfsDownloader`). It is NOT
bundled in the APK: `LinuxBaseManager` downloads it on first provisioning via
OkHttp and caches it in `filesDir/coding-env/`.

Runtime flow:

- `LinuxBaseManager` extracts the tarball (XZ + tar via Commons Compress) to
  `filesDir/coding-env/rootfs`, then configures it: real `/etc/resolv.conf`
  (the image ships a dead systemd-resolved symlink), `APT::Sandbox::User
  "root"` (no `_apt` user under proot), `/usr/sbin/policy-rc.d` (never start
  daemons — there is no init), and writes the `.base-ready` marker.
- `ProotShellBackend` runs each command as
  `libproot.so --kill-on-exit -0 -r <rootfs> -b /proc -b /dev -b /sys
   -b <workspace>:<workspace> -w <workspace> /bin/sh -c "<cmd>"`,
  with `PROOT_LOADER` and `LD_LIBRARY_PATH` pointing at the native library dir.
  Seccomp acceleration is left ON (forcing pure ptrace via `PROOT_NO_SECCOMP`
  breaks guest networking). The workspace is bound at the **same absolute
  path** so the sandboxed file tools and the shell agree on paths.
- Every guest command is prefixed with `export LD_PRELOAD=/usr/lib/libguestshim.so`
  inside the guest shell (so Bionic never loads it into proot itself).
- `DelegatingShellBackend` routes to proot when the base is provisioned, else to
  the device's native `sh` (basic commands still work pre-provisioning).
- `ApkAddonInstaller` installs marketplace addons with real
  `apt-get install --no-install-recommends` inside the rootfs (prefixed with
  `dpkg --configure -a` to self-heal interrupted states).
  `DelegatingAddonInstaller` auto-provisions the base on first install.

## The guest shim (`tools/guestshim.c`)

A freestanding (`-nostdlib`, no `DT_NEEDED`) shared library preloaded into
every guest process. It works under both musl and glibc guests because it only
references libc symbols the guest already has. It exists because two Android
kernel-level restrictions break stock Linux tooling inside proot:

1. **Name resolution**: libc resolver paths can fail with EACCES under
   proot/Android while raw sockets work fine (observed: musl `getaddrinfo`
   → "Permission denied"; `apk` could never fetch indexes). The shim provides
   a self-contained `getaddrinfo`/`freeaddrinfo`/`gai_strerror`/
   `getifaddrs`/`freeifaddrs`/`res_query`/`res_search`: numeric addresses →
   `/etc/hosts` → minimal UDP DNS A query via `sendto()` (nameservers from
   `/etc/resolv.conf`, public fallbacks). It never touches netlink.
2. **Hard links**: Android denies `link()`/`linkat()` in app storage with
   EACCES (verified natively, without proot, on Android 16). dpkg cannot
   install anything without hardlinks (it backs up `/var/lib/dpkg/status` via
   `link()`). The shim emulates `link`/`linkat` as byte-for-byte copies with
   the mode preserved — sufficient for dpkg/tar/git usage in a single-user
   rootfs.

Rebuild it with the NDK (see the comment header in `guestshim.c`), then copy
to `feature/coding/src/main/jniLibs/arm64-v8a/libguestshim.so`.
`tools/guestshim-trace.c` is a diagnostic variant that logs every resolver
call and traces connect/open/IO syscalls to `/tmp/shim.log` in the guest —
use it when guest networking misbehaves.

## Background services (dev servers)

`npm run dev`, `npm start`, watchers and other long-running commands run as
**background services** instead of blocking until the command timeout:

- `run_command` accepts `background: true`; server-like commands
  (`ServerCommands.looksLikeServer`) are auto-backgrounded even without it.
- `BackgroundServiceManager` (app-scoped singleton) spawns the command detached
  through the same shell backend (proot when the base is provisioned), drains
  its output into a bounded buffer + `filesDir/coding-env/services/<id>.log`,
  and scans it for an announced port (`ServerPortDetector`).
- The tool result reports the service id, the port, and two access URLs:
  `http://localhost:<port>` (on the device) and `http://<device-ip>:<port>`
  (from the LAN — proot shares the host network stack, so a guest server bound
  to `0.0.0.0` is genuinely reachable over Wi-Fi). The system prompt instructs
  the model to start servers with host binding (e.g. `npm run dev -- --host 0.0.0.0`).
- `list_background_services` / `stop_background_service` manage them; the chat
  screen shows a persistent services strip with a tappable URL + stop button.
- Services are children of the app process: they survive navigation and
  workspace switches, and die with the app process.

## Verifying on a physical device

Build/install the debug APK, then in **AI Coding Agent**:

1. Open the **Environment** panel → tap **Install Linux base**. First use
   downloads ~90 MB, then extracts; wait until it reads `ready (Debian)`.
2. From the marketplace (or by asking the agent), install **Node.js**. You
   should see real `apt-get install nodejs npm` output.
3. Ask the agent to run:
   - `node --version` → prints a real version string.
   - `npm --version` → prints a real version string.
   - `npm init -y && npm install left-pad` (or similar) → real package install
     from the npm registry.
   - "create a small vite app and run npm run dev" → the server starts as a
     background service; the tool card and the services strip show the port and
     the `http://<device-ip>:<port>` URL, which opens in a browser (from the
     device or another machine on the same Wi-Fi). Stop it with the strip's ✕
     button or `stop_background_service`.

If step 1 fails, read the terminal/status message — it distinguishes missing
proot binaries, download/checksum failures, and extraction errors.

## Device-specific facts learned the hard way

- `run-as io.androllm.app` is a **false negative for network tests**: even
  without proot, `nc`/`ping` as the app uid via run-as fail. Test networking
  through the app process itself (or the guest shim's log).
- The guest shim's DNS fixed Alpine's `apk` name resolution, but Alpine's
  fetch path still hit a permission wall (musl/apk-specific); Debian + glibc
  + apt is the combination that works end-to-end, and it is what proot-distro
  ships for the same reason.
- Hard links are impossible in app storage on modern Android — any tool that
  needs them must go through the shim's copy emulation.

## Reproducing the proot binaries

The binaries are committed, but to re-fetch/patch them (e.g. to upgrade proot
or get 16 KB-aligned builds):

```
pwsh ./tools/fetch_proot.ps1
```

It downloads Termux's `proot` + `libtalloc` aarch64 packages, extracts them,
patches proot's `DT_NEEDED` to `libtalloc.so`, and stages the files into
`feature/coding/src/main/jniLibs/arm64-v8a/`.
