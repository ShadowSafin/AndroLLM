package io.androllm.feature.coding.workspace

import kotlinx.serialization.Serializable

/**
 * A coding workspace: a real directory on the device that the coding agent is
 * allowed to inspect and modify.
 *
 * Workspaces are either CREATED under the app's public workspace root
 * (`/storage/emulated/0/AndroLLM/workspaces`, visible in any file manager) or
 * OPENED in place: the user picks one of their own folders and the agent works
 * directly inside it — files are written to the folder itself, never copied
 * into app-private storage.
 *
 * @param id stable unique id. Created workspaces use a uuid (dir name under
 *           the root); opened folders use a stable hash of their path so
 *           re-opening the same folder restores the same session.
 * @param name human readable label shown in the UI.
 * @param absolutePath absolute path of the workspace directory. Kept in the
 *                     form the user sees (`/storage/emulated/0/...`) — never
 *                     canonicalized, since canonical paths resolve the
 *                     /storage symlink to /data/media which apps cannot open.
 * @param source where the workspace came from.
 * @param createdAtEpochMs creation timestamp.
 */
@Serializable
data class CodingWorkspace(
    val id: String,
    val name: String,
    val absolutePath: String,
    val source: WorkspaceSource = WorkspaceSource.CREATED,
    val createdAtEpochMs: Long = 0L
) {
    /** Short display path (last two segments) for compact UI chips. */
    val shortPath: String
        get() = absolutePath.trimEnd('/').substringAfterLast('/').ifBlank { absolutePath }
}

/** Origin of a workspace. */
@Serializable
enum class WorkspaceSource {
    /** Created fresh inside the app's public workspace root. */
    CREATED,

    /** One of the user's own folders, opened and used directly in place. */
    OPENED,

    /** Legacy: copied in from device storage by older app versions. */
    IMPORTED
}
