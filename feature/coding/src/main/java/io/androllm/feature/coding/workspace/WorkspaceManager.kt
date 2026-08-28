package io.androllm.feature.coding.workspace

import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Supplies the app-owned root directory that holds workspaces created in-app.
 * The root lives in PUBLIC storage (`/storage/emulated/0/AndroLLM/workspaces`)
 * so users can browse created workspaces with any file manager. [legacyRootDir]
 * points at the old app-private root (if any) so previously created workspaces
 * stay visible after the move.
 */
fun interface WorkspaceRootProvider {
    fun rootDir(): File

    /** Old app-private root to keep scanning for pre-existing workspaces. */
    fun legacyRootDir(): File? = null
}

/**
 * Persistence boundary for the coding feature. Production backs this with
 * DataStore; unit tests use an in-memory fake. Kept as an interface so
 * [WorkspaceManager] stays JVM-testable.
 */
interface WorkspaceStore {
    suspend fun saveActiveWorkspaceId(id: String)
    suspend fun loadActiveWorkspaceId(): String
    suspend fun saveSession(state: CodingSessionState)
    suspend fun loadSession(): CodingSessionState

    /**
     * Registry of workspaces that live OUTSIDE the app-owned root (folders the
     * user opened in place). Created workspaces are discovered by scanning the
     * root instead.
     */
    suspend fun loadRegistry(): List<CodingWorkspace>
    suspend fun saveRegistry(workspaces: List<CodingWorkspace>)

    suspend fun clear()
}

/**
 * Manages coding workspaces: creation in public storage, opening user folders
 * IN PLACE, listing, active selection, validation and session-state
 * persistence.
 *
 * Two kinds of workspaces:
 *  - **Created** — a fresh directory under the app's public workspace root,
 *    carrying a `.coding-workspace.json` metadata file so the list can be
 *    rebuilt from the filesystem alone.
 *  - **Opened** — one of the user's own folders, used directly (the agent
 *    reads/writes files right there; nothing is copied). These are tracked in
 *    the [WorkspaceStore] registry since they live outside the root.
 *
 * All collaborators are injected interfaces so this class runs on the JVM in
 * unit tests (temp root + in-memory store).
 */
class WorkspaceManager(
    private val rootProvider: WorkspaceRootProvider,
    private val store: WorkspaceStore
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    /** Ensures the root exists and returns it. */
    fun root(): File = rootProvider.rootDir().apply { mkdirs() }

    // ── Listing ──────────────────────────────────────────────────────────────

    /**
     * Lists all known workspaces (newest first): registry entries (opened
     * folders, validated to still exist) merged with directories found under
     * the current and legacy roots.
     */
    suspend fun listWorkspaces(): List<CodingWorkspace> = withContext(Dispatchers.IO) {
        val byPath = linkedMapOf<String, CodingWorkspace>()

        for (ws in store.loadRegistry()) {
            val dir = File(ws.absolutePath)
            if (dir.exists() && dir.isDirectory) byPath[dir.absolutePath] = ws
        }

        val scanRoots = listOfNotNull(rootProvider.rootDir(), rootProvider.legacyRootDir())
            .distinctBy { it.absolutePath }
        for (scanDir in scanRoots) {
            if (!scanDir.exists()) continue
            scanDir.listFiles()?.toList().orEmpty()
                .filter { it.isDirectory }
                .mapNotNull { dir -> readMeta(dir) }
                .forEach { ws -> byPath.putIfAbsent(File(ws.absolutePath).absolutePath, ws) }
        }

        byPath.values.sortedByDescending { it.createdAtEpochMs }
    }

    private fun readMeta(dir: File): CodingWorkspace? {
        val metaFile = File(dir, META_FILE)
        return if (metaFile.exists()) {
            runCatching { json.decodeFromString<CodingWorkspace>(metaFile.readText()) }
                .getOrNull()?.copy(absolutePath = dir.absolutePath)
        } else {
            // A plain directory under the root with no metadata — surface it so
            // the user can still open it (treated as created-in-app).
            CodingWorkspace(
                id = dir.name,
                name = dir.name,
                absolutePath = dir.absolutePath,
                source = WorkspaceSource.CREATED,
                createdAtEpochMs = dir.lastModified()
            )
        }
    }

    private fun writeMeta(workspace: CodingWorkspace) {
        val dir = File(workspace.absolutePath)
        dir.mkdirs()
        File(dir, META_FILE).writeText(json.encodeToString(workspace))
    }

    // ── Creation / opening ───────────────────────────────────────────────────

    /**
     * Creates a new empty workspace directory under the public root and
     * returns it. The folder is visible to the user in any file manager.
     */
    suspend fun createWorkspace(name: String): CodingWorkspace = withContext(Dispatchers.IO) {
        val clean = name.trim().ifBlank { "workspace" }
        val id = UUID.randomUUID().toString()
        val dir = File(root(), id)
        dir.mkdirs()
        val ws = CodingWorkspace(
            id = id,
            name = clean,
            absolutePath = dir.absolutePath,
            source = WorkspaceSource.CREATED,
            createdAtEpochMs = System.currentTimeMillis()
        )
        writeMeta(ws)
        ws
    }

    /**
     * Opens one of the user's own folders IN PLACE as a workspace — the agent
     * reads and writes files directly inside it; nothing is copied anywhere.
     *
     * The id is a stable hash of the path, so opening the same folder again
     * (even after clearing app data) maps back to the same workspace and its
     * saved chat transcript. Opening an already-known folder returns the
     * existing entry.
     */
    suspend fun openFolder(absolutePath: String, displayName: String? = null): CodingWorkspace =
        withContext(Dispatchers.IO) {
            val dir = File(absolutePath).absoluteFile
            check(dir.exists() && dir.isDirectory) { "Folder not found: $absolutePath" }

            val existing = listWorkspaces().firstOrNull { it.absolutePath == dir.absolutePath }
            if (existing != null) return@withContext existing

            val ws = CodingWorkspace(
                id = "ext-" + sha1Hex(dir.absolutePath).take(16),
                name = displayName?.trim()?.takeIf { it.isNotBlank() } ?: dir.name,
                absolutePath = dir.absolutePath,
                source = WorkspaceSource.OPENED,
                createdAtEpochMs = System.currentTimeMillis()
            )
            store.saveRegistry(store.loadRegistry() + ws)
            ws
        }

    // ── Active selection ─────────────────────────────────────────────────────

    /** Sets the active workspace and records it in the session state. */
    suspend fun setActive(workspace: CodingWorkspace) {
        store.saveActiveWorkspaceId(workspace.id)
        val session = store.loadSession()
        store.saveSession(session.copy(workspaceId = workspace.id, updatedAtEpochMs = System.currentTimeMillis()))
    }

    /** Returns the active workspace, or null when none is set / it vanished. */
    suspend fun getActive(): CodingWorkspace? {
        val id = store.loadActiveWorkspaceId()
        if (id.isBlank()) return null
        return listWorkspaces().firstOrNull { it.id == id }
    }

    /** Clears the active workspace selection (keeps the folder on disk). */
    suspend fun clearActive() {
        store.saveActiveWorkspaceId("")
        val session = store.loadSession()
        store.saveSession(session.copy(workspaceId = "", updatedAtEpochMs = System.currentTimeMillis()))
    }

    /**
     * Validates that the currently active workspace still exists on disk. Used
     * on launch to avoid attaching the CLI to a deleted folder. Returns the
     * active workspace when valid, null otherwise (and clears the stale ref).
     */
    suspend fun validateCurrent(): CodingWorkspace? {
        val id = store.loadActiveWorkspaceId()
        if (id.isBlank()) return null
        val active = getActive()
        return if (active != null && File(active.absolutePath).let { it.exists() && it.isDirectory }) {
            active
        } else {
            clearActive()
            null
        }
    }

    /**
     * Removes a workspace. Created (app-owned) directories are deleted from
     * disk; OPENED folders are the user's own projects and are only forgotten
     * — their files are never touched.
     */
    suspend fun deleteWorkspace(workspace: CodingWorkspace) = withContext(Dispatchers.IO) {
        if (getActive()?.id == workspace.id) clearActive()
        if (workspace.source != WorkspaceSource.OPENED) {
            File(workspace.absolutePath).deleteRecursively()
        }
        store.saveRegistry(store.loadRegistry().filterNot { it.id == workspace.id })
        Unit
    }

    // ── Session state ────────────────────────────────────────────────────────

    suspend fun loadSession(): CodingSessionState = store.loadSession()

    suspend fun saveSession(state: CodingSessionState) =
        store.saveSession(state.copy(updatedAtEpochMs = System.currentTimeMillis()))

    /** Builds the sandboxed file-ops handle for a workspace. */
    fun fileOps(workspace: CodingWorkspace): WorkspaceFileOps =
        WorkspaceFileOps(File(workspace.absolutePath))

    private fun sha1Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val META_FILE = ".coding-workspace.json"
    }
}
