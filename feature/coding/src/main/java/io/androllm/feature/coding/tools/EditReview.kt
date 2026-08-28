package io.androllm.feature.coding.tools

/** What kind of file change is awaiting review. */
enum class ChangeKind { CREATE, OVERWRITE, EDIT }

/**
 * A file change large enough to be shown to the user for approval BEFORE it is
 * applied (OpenCode-style diff review). The diff is unified-style text.
 */
data class PendingFileChange(
    val path: String,
    val kind: ChangeKind,
    val unifiedDiff: String,
    val added: Int,
    val removed: Int
)

/**
 * Asks the user to approve a major file change. Production wires this to the
 * diff-review sheet in the coding chat; tests use scripted handlers. Returning
 * false rejects the change (the tool then reports the rejection to the model).
 */
fun interface EditReviewGate {
    suspend fun review(change: PendingFileChange): Boolean
}

/** Thresholds deciding when a change is "major" and needs review. */
object EditReviewThresholds {
    /** New file larger than this (lines) requires review. */
    const val NEW_FILE_LINES = 120

    /** Edit/overwrite touching more than this many lines (added+removed) requires review. */
    const val EDIT_LINES = 40
}
