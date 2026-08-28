package io.androllm.feature.coding.environment

import kotlinx.serialization.Serializable

/** Broad category of a marketplace addon (drives grouping + icons). */
@Serializable
enum class PackageKind {
    RUNTIME,          // node, python, java, go, rust
    PACKAGE_MANAGER,  // pnpm, yarn, pip, gradle
    VERSION_CONTROL,  // git
    BUILD_TOOL,       // make, cmake, ninja
    UTILITY           // common linux utilities
}

/**
 * An installable runtime / toolchain addon in the coding marketplace.
 *
 * @param id stable identifier (matches [MissingDependency.addonId]).
 * @param name display name.
 * @param description one-line marketplace description.
 * @param version advertised version string.
 * @param sizeBytes approximate download size.
 * @param kind category for grouping.
 * @param providesCommands executables this addon puts on PATH.
 * @param requiresInternet whether installation needs network access.
 * @param platforms supported ABIs / environments.
 * @param dependsOn addon ids that must be installed first (e.g. pnpm → nodejs).
 * @param downloadUrl where the toolchain archive lives (empty = bundled/simulated).
 */
@Serializable
data class RuntimePackage(
    val id: String,
    val name: String,
    val description: String,
    val version: String,
    val sizeBytes: Long,
    val kind: PackageKind,
    val providesCommands: List<String> = emptyList(),
    val requiresInternet: Boolean = true,
    val platforms: List<String> = listOf("arm64-v8a", "linux"),
    val dependsOn: List<String> = emptyList(),
    val downloadUrl: String = ""
)

/** Install lifecycle for one addon. */
@Serializable
enum class InstallStatus {
    NOT_INSTALLED,
    DOWNLOADING,
    EXTRACTING,
    INSTALLED,
    FAILED
}

/** Progress snapshot for an in-flight install. */
@Serializable
data class InstallProgress(
    val addonId: String,
    val status: InstallStatus = InstallStatus.DOWNLOADING,
    val percent: Int = 0,
    val bytesDownloaded: Long = 0,
    val message: String = ""
)
