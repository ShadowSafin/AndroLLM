package io.androllm.feature.coding.environment

/**
 * The coding marketplace catalog: every runtime / toolchain addon the user can
 * install into the Linux CLI environment. Ids match [DependencyDetector]'s
 * requirements so a detected missing dependency maps 1:1 to a catalog entry.
 *
 * Sizes are realistic archive estimates so the UI can show meaningful numbers;
 * `downloadUrl` is empty for addons provisioned from the bundled/simulated
 * installer (the on-device installer resolves the real artifact per ABI).
 */
object MarketplaceCatalog {

    val packages: List<RuntimePackage> = listOf(
        RuntimePackage(
            id = "nodejs",
            name = "Node.js",
            description = "JavaScript runtime. Provides node, npm and npx for JS/TS projects.",
            version = "20.18.0",
            sizeBytes = 28_000_000,
            kind = PackageKind.RUNTIME,
            providesCommands = listOf("node", "npm", "npx")
        ),
        RuntimePackage(
            id = "pnpm",
            name = "pnpm",
            description = "Fast, disk-efficient package manager for Node.js projects.",
            version = "9.12.0",
            sizeBytes = 3_500_000,
            kind = PackageKind.PACKAGE_MANAGER,
            providesCommands = listOf("pnpm"),
            dependsOn = listOf("nodejs")
        ),
        RuntimePackage(
            id = "yarn",
            name = "Yarn",
            description = "Dependency manager and build orchestrator for Node.js.",
            version = "1.22.22",
            sizeBytes = 1_800_000,
            kind = PackageKind.PACKAGE_MANAGER,
            providesCommands = listOf("yarn"),
            dependsOn = listOf("nodejs")
        ),
        RuntimePackage(
            id = "python",
            name = "Python",
            description = "Python 3 interpreter with pip. Runs scripts, tests and tooling.",
            version = "3.12.7",
            sizeBytes = 42_000_000,
            kind = PackageKind.RUNTIME,
            providesCommands = listOf("python", "python3", "pip", "pip3")
        ),
        RuntimePackage(
            id = "git",
            name = "Git",
            description = "Distributed version control. Enables status, diff, commit, branch.",
            version = "2.47.0",
            sizeBytes = 12_000_000,
            kind = PackageKind.VERSION_CONTROL,
            providesCommands = listOf("git")
        ),
        RuntimePackage(
            id = "java",
            name = "Java (OpenJDK)",
            description = "OpenJDK 17 runtime + compiler. Required by Gradle and JVM builds.",
            version = "17.0.13",
            sizeBytes = 180_000_000,
            kind = PackageKind.RUNTIME,
            providesCommands = listOf("java", "javac")
        ),
        RuntimePackage(
            id = "gradle",
            name = "Gradle",
            description = "Build automation for Android/JVM projects (gradle wrapper still needs Java).",
            version = "8.10.2",
            sizeBytes = 60_000_000,
            kind = PackageKind.BUILD_TOOL,
            providesCommands = listOf("gradle"),
            dependsOn = listOf("java")
        ),
        RuntimePackage(
            id = "go",
            name = "Go",
            description = "Go toolchain: build, test and run Go modules.",
            version = "1.23.3",
            sizeBytes = 95_000_000,
            kind = PackageKind.RUNTIME,
            providesCommands = listOf("go", "gofmt")
        ),
        RuntimePackage(
            id = "rust",
            name = "Rust (Cargo)",
            description = "Rust compiler and Cargo package manager for systems projects.",
            version = "1.82.0",
            sizeBytes = 220_000_000,
            kind = PackageKind.RUNTIME,
            providesCommands = listOf("cargo", "rustc", "rustup")
        ),
        RuntimePackage(
            id = "build-tools",
            name = "Build Tools",
            description = "make, cmake and ninja for native and C/C++ builds.",
            version = "1.0.0",
            sizeBytes = 18_000_000,
            kind = PackageKind.BUILD_TOOL,
            providesCommands = listOf("make", "cmake", "ninja")
        ),
        RuntimePackage(
            id = "linux-utils",
            name = "Linux Utilities",
            description = "Extended userland: awk, sed, curl, wget, jq, tree and friends.",
            version = "1.0.0",
            sizeBytes = 9_000_000,
            kind = PackageKind.UTILITY,
            providesCommands = listOf("awk", "curl", "wget", "jq", "tree", "less", "vim")
        )
    )

    private val byId = packages.associateBy { it.id }

    /** Looks up an addon by id (null when unknown). */
    fun find(id: String): RuntimePackage? = byId[id]

    /** Returns the full dependency chain for [id] in install order (deps first). */
    fun dependencyChain(id: String): List<RuntimePackage> {
        val seen = LinkedHashSet<String>()
        fun visit(pkgId: String) {
            if (pkgId in seen) return
            val pkg = byId[pkgId] ?: return
            pkg.dependsOn.forEach { visit(it) }
            seen += pkgId
        }
        visit(id)
        return seen.mapNotNull { byId[it] }
    }

    /** Groups the catalog by [PackageKind] for the marketplace UI. */
    fun groupedByKind(): Map<PackageKind, List<RuntimePackage>> = packages.groupBy { it.kind }
}
