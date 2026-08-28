package io.androllm.feature.coding.agent

/**
 * Coding task modes. Selecting a mode tailors the agent's working method for a
 * specific kind of job (OpenCode-style specialization). The guidance is folded
 * into the system prompt; GENERAL adds nothing.
 */
enum class CodingTaskMode(val label: String, val emoji: String, val guidance: String) {

    GENERAL("General", "🛠️", ""),

    BUILD_WEBSITE(
        "Build website", "🌐",
        """You are building a WEBSITE / web UI. Hold yourself to a production bar:
        - First run workspace_summary to detect the stack; scaffold with the right tooling (Vite for new SPAs,
          plain index.html + CSS + JS only when the user wants zero tooling).
        - Responsive layout first: mobile-friendly, flex/grid, sensible breakpoints, viewport meta tag.
        - Semantic, accessible HTML (landmarks, alt text, labels, focus states, contrast).
        - Modern styling: consistent spacing scale, typography hierarchy, clean color palette, dark-mode friendly.
        - Reusable components; no copy-pasted blocks when a component/function will do.
        - Start the dev server in the background when done so the user can preview it, and give them the URL."""
    ),

    FIX_BUG(
        "Fix a bug", "🐛",
        """You are fixing a BUG. Method:
        1. Reproduce first: run the failing command/test and read the FULL raw error.
        2. Form a hypothesis; locate the responsible code with grep/read_file (do not guess-fix).
        3. Make the MINIMAL correct fix — no drive-by refactors.
        4. Re-run the reproduction to prove the fix; run related tests/build when they exist.
        5. Explain the root cause and the fix in your final message."""
    ),

    REFACTOR(
        "Refactor", "♻️",
        """You are REFACTORING. Rules:
        - Behavior must not change. Identify the public surface first (grep for usages) and keep it stable.
        - Small, reviewable steps; verify with build/tests after each meaningful step.
        - Improve naming, structure, duplication and consistency with the existing style.
        - Summarize every changed file and why in your final message."""
    ),

    ADD_FEATURE(
        "Add feature", "✨",
        """You are ADDING A FEATURE. Method:
        1. Understand the existing architecture first (workspace_summary, entry points, related files).
        2. Plan with update_plan before writing code; follow the plan and keep it updated.
        3. Match existing conventions (style, structure, naming, error handling).
        4. Implement, then verify with the project's build/tests; start the dev server when it helps.
        5. Summarize what was added, where, and how it was verified."""
    ),

    DEBUG_BUILD(
        "Debug build", "🔧",
        """You are debugging a BUILD failure. Method:
        1. Run the build, read the FULL raw log, and find the FIRST real error (later errors are often cascades).
        2. Fix the root cause; re-run the build. Repeat until it passes.
        3. If a dependency/tool is missing, say so — the app can install it.
        4. Report what was broken and what you changed."""
    ),

    POLISH_UI(
        "Polish UI", "🎨",
        """You are polishing UI/styling. Focus on:
        - Visual hierarchy, spacing consistency, alignment, contrast and readability.
        - Responsive behavior on small screens; touch-friendly targets.
        - Micro-interactions and states (hover/focus/active/disabled/loading/empty/error).
        - Accessibility (labels, alt text, focus order).
        - Start the dev server in the background when done so the user can see the result."""
    ),

    INSPECT(
        "Inspect / explain", "🔍",
        """You are INSPECTING and explaining a project. Read-only mindset:
        - Use workspace_summary, file_tree, list_dir, grep and read_file; avoid mutating anything.
        - Explain the architecture: stack, entry points, main modules, data flow, build/run commands.
        - Point out notable patterns, risks and improvement opportunities.
        - Structure the explanation clearly with headings."""
    ),

    WRITE_TESTS(
        "Write tests", "🧪",
        """You are writing TESTS:
        - Detect the existing test framework first; follow its conventions and file layout.
        - Cover the important behaviors (happy path + edge cases + failures), not line-count.
        - Keep tests deterministic and independent; no network or device dependencies unless mocked.
        - Run the tests and make them pass before finishing."""
    );

    companion object {
        fun fromId(id: String?): CodingTaskMode =
            entries.firstOrNull { it.name.equals(id, ignoreCase = true) } ?: GENERAL
    }
}
