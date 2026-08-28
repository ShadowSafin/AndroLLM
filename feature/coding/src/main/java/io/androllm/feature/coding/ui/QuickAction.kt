package io.androllm.feature.coding.ui

/**
 * One-tap quick actions above the input bar. Each sends a templated prompt so
 * the AGENT decides the right concrete command for the detected stack (instead
 * of hardcoding `npm run build` for a Python project).
 */
enum class QuickAction(val label: String, val emoji: String, val prompt: String) {

    BUILD(
        "Build", "🔨",
        "Run this project's build (detect the right command for the stack). If it fails, read the full " +
            "error output, fix the problems, and rerun until the build passes."
    ),

    TEST(
        "Test", "🧪",
        "Run this project's test suite (detect the right command for the stack). If tests fail, diagnose " +
            "each failure from the raw output, fix the code or the test (whichever is wrong), and rerun until green."
    ),

    LINT(
        "Lint", "🧹",
        "Run this project's lint/format checks if they exist (detect the tooling). Fix the reported issues " +
            "that are safe to fix automatically, then rerun to confirm."
    ),

    RUN(
        "Run", "▶️",
        "Start this project's dev server / main entry point in the background (detect the right command, " +
            "bind to 0.0.0.0 when it is a web server). Then tell me the access URLs."
    ),

    INSPECT(
        "Inspect", "🔍",
        "Analyze this project: run workspace_summary, map the structure, and explain the architecture, " +
            "entry points, and how to build/run it."
    );
}
