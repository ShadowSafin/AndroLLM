package io.androllm.feature.prompts

/**
 * Categories shown as filter chips in the prompt library.
 */
enum class PromptCategory(val label: String) {
    ALL("All"),
    GENERAL("General"),
    PROGRAMMING("Programming"),
    ANDROID("Android"),
    WRITING("Writing"),
    REASONING("Reasoning"),
    MATH("Math"),
    TRANSLATION("Translation")
}

/**
 * A single prompt library entry.
 */
data class PromptTemplate(
    val id: String,
    val title: String,
    val description: String,
    val category: PromptCategory,
    val text: String
)

/**
 * Curated on-device prompt library. Every prompt is a plain-text template the
 * user can send to the local model with one tap.
 */
object PromptLibrary {

    val prompts: List<PromptTemplate> = listOf(
        PromptTemplate(
            id = "code_review",
            title = "Review my code",
            description = "Find bugs, security issues, and readability improvements.",
            category = PromptCategory.PROGRAMMING,
            text = "Review the following code carefully. Identify bugs, security issues, performance problems, and readability improvements. Be specific and suggest concrete fixes.\n\n```\n{PASTE CODE HERE}\n```"
        ),
        PromptTemplate(
            id = "explain_code",
            title = "Explain this code",
            description = "Walk through what a snippet does, line by line.",
            category = PromptCategory.PROGRAMMING,
            text = "Explain what this code does step by step, in plain language. Then summarize its purpose in one sentence.\n\n```\n{PASTE CODE HERE}\n```"
        ),
        PromptTemplate(
            id = "write_tests",
            title = "Write unit tests",
            description = "Generate thorough unit tests for a function or class.",
            category = PromptCategory.PROGRAMMING,
            text = "Write comprehensive unit tests for the following code. Cover edge cases, error paths, and normal behavior. Use the framework implied by the language.\n\n```\n{PASTE CODE HERE}\n```"
        ),
        PromptTemplate(
            id = "debug_error",
            title = "Debug this error",
            description = "Diagnose a stack trace or error message.",
            category = PromptCategory.PROGRAMMING,
            text = "Here is an error. Explain the root cause and provide a step-by-step fix.\n\nError:\n{PASTE ERROR}\n\nRelevant code:\n{PASTE CODE}"
        ),
        PromptTemplate(
            id = "kotlin_refactor",
            title = "Refactor to idiomatic Kotlin",
            description = "Modernize legacy Kotlin/Java with best practices.",
            category = PromptCategory.ANDROID,
            text = "Refactor this Kotlin/Android code to be idiomatic: use coroutines and Flow where appropriate, prefer immutability, apply scoping functions correctly, and follow Android best practices. Explain each change.\n\n```\n{PASTE CODE HERE}\n```"
        ),
        PromptTemplate(
            id = "compose_screen",
            title = "Design a Compose screen",
            description = "Draft a Jetpack Compose UI with Material 3.",
            category = PromptCategory.ANDROID,
            text = "Design a Jetpack Compose screen for: {DESCRIBE THE SCREEN}. Use Material 3 components, sensible state hoisting, previews, and good accessibility. Provide the full Kotlin code."
        ),
        PromptTemplate(
            id = "android_architecture",
            title = "Advise on architecture",
            description = "MVVM, Hilt, Room, and clean-architecture guidance.",
            category = PromptCategory.ANDROID,
            text = "I'm building {DESCRIBE THE FEATURE} in an Android app. Recommend an architecture using MVVM, Hilt, Room, and coroutines. Describe the layers, key classes, and data flow."
        ),
        PromptTemplate(
            id = "perf_tuning",
            title = "Android performance tuning",
            description = "Reduce jank, memory, and battery usage.",
            category = PromptCategory.ANDROID,
            text = "Here is an Android performance problem: {DESCRIBE}. Suggest concrete fixes for UI jank, memory leaks, unnecessary recomposition, or battery drain, with code examples."
        ),
        PromptTemplate(
            id = "draft_email",
            title = "Draft an email",
            description = "Professional, friendly, or persuasive email copy.",
            category = PromptCategory.WRITING,
            text = "Draft a {TONE} email about {SUBJECT} to {AUDIENCE}. Keep it concise, clear, and actionable. Provide two versions: one short and one detailed."
        ),
        PromptTemplate(
            id = "summarize",
            title = "Summarize a document",
            description = "Distill long text into key points.",
            category = PromptCategory.WRITING,
            text = "Summarize the following text in 3–5 bullet points. Preserve important numbers, names, and conclusions.\n\n{PASTE TEXT HERE}"
        ),
        PromptTemplate(
            id = "blog_post",
            title = "Write a blog post",
            description = "Outline and draft a compelling article.",
            category = PromptCategory.WRITING,
            text = "Write a blog post about {TOPIC} for {AUDIENCE}. Start with an outline, then write the full post with an engaging hook, clear structure, and a strong conclusion."
        ),
        PromptTemplate(
            id = "brainstorm",
            title = "Brainstorm ideas",
            description = "Generate varied creative directions.",
            category = PromptCategory.WRITING,
            text = "Brainstorm 10 creative ideas about {TOPIC}. Vary the approaches — practical, bold, playful, and unexpected. For each idea, add one sentence on how it could work."
        ),
        PromptTemplate(
            id = "pros_cons",
            title = "Weigh pros & cons",
            description = "Structured decision-making analysis.",
            category = PromptCategory.REASONING,
            text = "Analyze the pros and cons of {DECISION OR OPTION}. Consider short-term and long-term consequences, risks, and trade-offs. End with a clear recommendation."
        ),
        PromptTemplate(
            id = "step_plan",
            title = "Make a step-by-step plan",
            description = "Break a goal into actionable steps.",
            category = PromptCategory.REASONING,
            text = "Create a detailed step-by-step plan to achieve: {GOAL}. Include prerequisites, estimated effort per step, potential obstacles, and how to measure success."
        ),
        PromptTemplate(
            id = "socratic",
            title = "Teach me Socratic-style",
            description = "Learn through guided questions.",
            category = PromptCategory.REASONING,
            text = "Teach me {TOPIC} using the Socratic method. Ask me one question at a time, react to my answers, and guide me toward understanding without dumping the answer."
        ),
        PromptTemplate(
            id = "solve_math",
            title = "Solve & explain a problem",
            description = "Step-by-step math with reasoning.",
            category = PromptCategory.MATH,
            text = "Solve this problem step by step, explaining each step and the reasoning behind it. Then state the final answer clearly.\n\n{PASTE PROBLEM HERE}"
        ),
        PromptTemplate(
            id = "verify_proof",
            title = "Check a proof",
            description = "Verify mathematical reasoning for gaps.",
            category = PromptCategory.MATH,
            text = "Check this mathematical argument for correctness. Identify any unjustified steps, hidden assumptions, or errors, and explain how to fix them.\n\n{PASTE ARGUMENT HERE}"
        ),
        PromptTemplate(
            id = "translate",
            title = "Translate text",
            description = "Accurate translation with nuance preserved.",
            category = PromptCategory.TRANSLATION,
            text = "Translate the following text from {SOURCE LANGUAGE} to {TARGET LANGUAGE}. Preserve tone and nuance. If an idiom has no direct equivalent, explain the closest alternative.\n\n{PASTE TEXT HERE}"
        ),
        PromptTemplate(
            id = "rewrite_clear",
            title = "Rewrite for clarity",
            description = "Simplify confusing or wordy passages.",
            category = PromptCategory.WRITING,
            text = "Rewrite this passage to be clearer and more concise while keeping the original meaning and tone.\n\n{PASTE TEXT HERE}"
        ),
        PromptTemplate(
            id = "sql_query",
            title = "Write an SQL query",
            description = "Query design for a described data need.",
            category = PromptCategory.PROGRAMMING,
            text = "Write an SQL query that: {DESCRIBE THE QUERY NEED}. Assume this schema: {SCHEMA IF KNOWN}. Optimize for readability and correctness."
        ),
        PromptTemplate(
            id = "learn_concept",
            title = "Explain like I'm new",
            description = "Friendly introduction to any concept.",
            category = PromptCategory.GENERAL,
            text = "Explain {CONCEPT} to a complete beginner. Use a simple analogy first, then build up to the precise definition, with a short example."
        ),
        PromptTemplate(
            id = "cold_email",
            title = "Write a cold message",
            description = "Concise outreach that gets replies.",
            category = PromptCategory.WRITING,
            text = "Write a short cold message to {RECIPIENT} about {PURPOSE}. Make it personal, specific, and easy to reply to. Max 120 words."
        )
    )
}
