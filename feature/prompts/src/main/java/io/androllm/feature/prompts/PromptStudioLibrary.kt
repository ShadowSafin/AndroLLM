package io.androllm.feature.prompts

/**
 * Curated Studio templates — each has its own internal prompt structure,
 * variables, and dynamic fields. Covers all required categories.
 */
object PromptStudioLibrary {

    val templates: List<StudioTemplate> = listOf(
        StudioTemplate(
            id = "refactor_code",
            title = "Refactor code",
            description = "Improve readability, performance and maintainability.",
            exampleUseCase = "Paste your Kotlin code, select Refactor, and get a clean, documented version.",
            category = StudioCategory.CODE,
            qualityScore = 96,
            usefulnessTag = "Top pick",
            fields = listOf(
                PromptField(id = "code", label = "Paste your code here", placeholder = "fun example() { ... }", type = PromptFieldType.CODE, required = true, helperText = "Required — the code to refactor"),
                PromptField(id = "language", label = "What language is this?", placeholder = "Kotlin", type = PromptFieldType.SELECT, required = true, options = listOf("Kotlin", "Java", "Python", "JavaScript", "TypeScript", "Go", "Swift", "C++", "C#", "Dart")),
                PromptField(id = "goal", label = "What do you want the AI to do?", placeholder = "Improve architecture and reduce bugs", type = PromptFieldType.SELECT, required = true, options = listOf("Improve readability", "Improve performance", "Reduce bugs", "Modernize", "Improve architecture")),
                PromptField(id = "error_logs", label = "Any error logs or extra context?", placeholder = "Optional stack trace or logs", type = PromptFieldType.TEXT_AREA, required = false, isAdvanced = true),
                PromptField(id = "context", label = "Optional context or notes", placeholder = "e.g. This runs on Android 14, must keep backward compatibility", type = PromptFieldType.TEXT_AREA, required = false, isAdvanced = true)
            ),
            promptTemplate = """You are a senior software engineer.
Refactor the following code for readability, performance, and maintainability.

Language: {{language}}
Goal: {{goal}}

Code:
```{{language}}
{{code}}
```

{{#context}}Context: {{context}}{{/context}}
{{#error_logs}}Error logs:
{{error_logs}}{{/error_logs}}

Requirements:
- Preserve functionality
- Explain major changes
- Return clean code
- Mention any trade-offs
""",
            structure = PromptStructure(
                role = "senior software engineer",
                task = "refactor code",
                constraints = listOf("Preserve functionality", "Explain major changes", "Return clean code", "Mention trade-offs"),
                outputFormat = "Clean code block + explanations"
            ),
            exampleFilledPrompt = "You are a senior software engineer.\nRefactor the following code for readability...\nLanguage: Kotlin\nGoal: Improve architecture\nCode:\n```kotlin\nfun old() {}\n```"
        ),
        StudioTemplate(
            id = "explain_code",
            title = "Explain code",
            description = "Get a clear, line-by-line explanation.",
            exampleUseCase = "Paste confusing legacy code and get a beginner-friendly walkthrough.",
            category = StudioCategory.EXPLAIN,
            qualityScore = 92,
            fields = listOf(
                PromptField(id = "code", label = "Paste your code here", placeholder = "Paste code to explain", type = PromptFieldType.CODE, required = true),
                PromptField(id = "language", label = "Language", placeholder = "Kotlin", type = PromptFieldType.SELECT, required = true, options = listOf("Kotlin", "Java", "Python", "JavaScript", "TypeScript", "Go", "Swift", "C++")),
                PromptField(id = "audience", label = "Audience", placeholder = "Beginner", type = PromptFieldType.SELECT, required = false, options = listOf("Beginner", "Intermediate", "Expert"), isAdvanced = false),
                PromptField(id = "context", label = "Extra context", placeholder = "Optional", type = PromptFieldType.TEXT_AREA, required = false, isAdvanced = true)
            ),
            promptTemplate = """You are a patient senior engineer and teacher.
Explain the following code step by step in plain language.

Language: {{language}}
Audience: {{audience}}

Code:
```{{language}}
{{code}}
```

{{#context}}Context: {{context}}{{/context}}

Requirements:
- Walk through line by line
- Summarize purpose in one sentence
- Highlight tricky parts
""",
            exampleFilledPrompt = "Explain this Kotlin code for a beginner..."
        ),
        StudioTemplate(
            id = "find_bugs",
            title = "Find bugs in code",
            description = "Hunt for bugs, security issues and edge cases.",
            exampleUseCase = "Paste code with suspected bug and get diagnostics.",
            category = StudioCategory.CODE,
            qualityScore = 94,
            fields = listOf(
                PromptField(id = "code", label = "Paste your code here", placeholder = "Paste code", type = PromptFieldType.CODE, required = true),
                PromptField(id = "language", label = "Language", placeholder = "Kotlin", type = PromptFieldType.SELECT, required = true, options = listOf("Kotlin", "Java", "Python", "JavaScript", "TypeScript", "Go")),
                PromptField(id = "error_logs", label = "Error message / logs", placeholder = "Optional error", type = PromptFieldType.TEXT_AREA, required = false),
                PromptField(id = "goal", label = "Focus", placeholder = "Find bugs and security issues", type = PromptFieldType.TEXT, required = false, isAdvanced = true)
            ),
            promptTemplate = """You are a senior code reviewer and security analyst.
Find bugs, security issues, and edge cases in the following code.

Language: {{language}}

Code:
```{{language}}
{{code}}
```

{{#error_logs}}Error/logs:
{{error_logs}}{{/error_logs}}

Goal: {{goal}}

Return:
- List of issues with severity
- Suggested fixes
- Corrected code where applicable
""",
            structure = PromptStructure(role = "code reviewer", task = "find bugs")
        ),
        StudioTemplate(
            id = "write_docs",
            title = "Write documentation",
            description = "Generate README, KDocs or inline docs.",
            exampleUseCase = "Paste a function and get polished documentation.",
            category = StudioCategory.CODE,
            qualityScore = 88,
            fields = listOf(
                PromptField(id = "code", label = "Paste your code here", placeholder = "Paste code", type = PromptFieldType.CODE, required = true),
                PromptField(id = "language", label = "Language", placeholder = "Kotlin", type = PromptFieldType.SELECT, required = true, options = listOf("Kotlin", "Java", "Python", "JavaScript")),
                PromptField(id = "tone", label = "Tone", placeholder = "Professional", type = PromptFieldType.SELECT, required = false, options = listOf("Professional", "Friendly", "Concise", "Detailed")),
                PromptField(id = "context", label = "Context", placeholder = "Optional", type = PromptFieldType.TEXT_AREA, required = false, isAdvanced = true)
            ),
            promptTemplate = """You are a technical writer.
Write clear documentation for the following code.

Language: {{language}}
Tone: {{tone}}

Code:
```{{language}}
{{code}}
```

{{#context}}Context: {{context}}{{/context}}

Output: README-style docs with usage examples.
""",
        ),
        StudioTemplate(
            id = "summarize_text",
            title = "Summarize text",
            description = "Distill long text into key points.",
            exampleUseCase = "Paste an article and get a 3-bullet summary.",
            category = StudioCategory.SUMMARIZE,
            qualityScore = 90,
            fields = listOf(
                PromptField(id = "text", label = "Text to summarize", placeholder = "Paste long text here", type = PromptFieldType.TEXT_AREA, required = true),
                PromptField(id = "length", label = "Summary length", placeholder = "3 bullet points", type = PromptFieldType.SELECT, required = true, options = listOf("1 sentence", "3 bullet points", "5 bullet points", "One paragraph", "Half length")),
                PromptField(id = "style", label = "Style", placeholder = "Concise", type = PromptFieldType.SELECT, required = false, options = listOf("Concise", "Detailed", "Bullet points", "Paragraph", "Executive summary")),
                PromptField(id = "audience", label = "Audience", placeholder = "General", type = PromptFieldType.SELECT, required = false, options = listOf("General", "Beginner", "Expert", "Executive"), isAdvanced = true)
            ),
            promptTemplate = """Summarize the following text.

Source text:
{{text}}

Goal:
- Length: {{length}}
- Style: {{style}}
- Audience: {{audience}}

Requirements:
- Preserve important numbers, names, conclusions
- No hallucinations
""",
        ),
        StudioTemplate(
            id = "rewrite_simple",
            title = "Rewrite in simple English",
            description = "Make any text clearer and more concise.",
            exampleUseCase = "Paste a complex paragraph and get a simple version.",
            category = StudioCategory.REWRITE,
            qualityScore = 89,
            fields = listOf(
                PromptField(id = "text", label = "Original text", placeholder = "Paste text to rewrite", type = PromptFieldType.TEXT_AREA, required = true),
                PromptField(id = "tone", label = "Target tone", placeholder = "Simple English", type = PromptFieldType.SELECT, required = true, options = listOf("Simple English", "Formal", "Friendly", "Professional", "Casual")),
                PromptField(id = "audience", label = "Audience", placeholder = "General", type = PromptFieldType.TEXT, required = false, isAdvanced = true)
            ),
            promptTemplate = """Rewrite the following passage to be clearer and more concise while keeping the original meaning.

Original:
{{text}}

Target tone: {{tone}}
Audience: {{audience}}

Return only the rewritten text.
""",
        ),
        StudioTemplate(
            id = "threads_post",
            title = "Generate Threads post",
            description = "Create engaging social content.",
            exampleUseCase = "Topic: AI launch, Audience: devs, get a Threads-ready post.",
            category = StudioCategory.SOCIAL_POST,
            qualityScore = 87,
            fields = listOf(
                PromptField(id = "topic", label = "Topic", placeholder = "e.g. Launching AndroLLM 3.0", type = PromptFieldType.TEXT, required = true),
                PromptField(id = "audience", label = "Audience", placeholder = "Tech enthusiasts", type = PromptFieldType.TEXT, required = true),
                PromptField(id = "tone", label = "Tone", placeholder = "Exciting", type = PromptFieldType.SELECT, required = false, options = listOf("Exciting", "Professional", "Casual", "Funny", "Inspirational")),
                PromptField(id = "length", label = "Length", placeholder = "Short", type = PromptFieldType.SELECT, required = false, options = listOf("Short", "Medium", "Thread (3 tweets)")),
                PromptField(id = "context", label = "Key points", placeholder = "Optional hashtags, CTA", type = PromptFieldType.TEXT_AREA, required = false, isAdvanced = true)
            ),
            promptTemplate = """Create a Threads post.

Topic: {{topic}}
Audience: {{audience}}
Tone: {{tone}}
Length: {{length}}
Platform: Threads

{{#context}}Key points: {{context}}{{/context}}

Requirements:
- Hook in first line
- Clear CTA
- Hashtags if relevant
- Keep under 500 chars per post
""",
        ),
        StudioTemplate(
            id = "generate_email",
            title = "Generate email",
            description = "Professional email drafts in seconds.",
            exampleUseCase = "Generate a polite follow-up email to a client.",
            category = StudioCategory.EMAIL,
            qualityScore = 91,
            fields = listOf(
                PromptField(id = "topic", label = "Subject / Purpose", placeholder = "Follow-up on project proposal", type = PromptFieldType.TEXT, required = true),
                PromptField(id = "audience", label = "Recipient", placeholder = "Client", type = PromptFieldType.TEXT, required = true),
                PromptField(id = "tone", label = "Tone", placeholder = "Professional", type = PromptFieldType.SELECT, required = true, options = listOf("Professional", "Friendly", "Persuasive", "Concise", "Formal")),
                PromptField(id = "length", label = "Length", placeholder = "Short", type = PromptFieldType.SELECT, required = false, options = listOf("Short", "Medium", "Detailed")),
                PromptField(id = "context", label = "Key points", placeholder = "Include meeting on Tuesday, attach proposal", type = PromptFieldType.TEXT_AREA, required = false)
            ),
            promptTemplate = """Draft an email.

Purpose: {{topic}}
Recipient: {{audience}}
Tone: {{tone}}
Length: {{length}}

Key points:
{{context}}

Provide a subject line and body. Keep it concise, clear, and actionable.
""",
        ),
        StudioTemplate(
            id = "research_topic",
            title = "Research topic",
            description = "Deep dive with structured findings.",
            exampleUseCase = "Research 'on-device LLM quantization' and get a brief.",
            category = StudioCategory.RESEARCH,
            qualityScore = 90,
            fields = listOf(
                PromptField(id = "topic", label = "Research topic", placeholder = "e.g. Quantization for on-device LLMs", type = PromptFieldType.TEXT, required = true),
                PromptField(id = "audience", label = "Audience", placeholder = "Developers", type = PromptFieldType.TEXT, required = false),
                PromptField(id = "length", label = "Depth", placeholder = "Brief", type = PromptFieldType.SELECT, required = false, options = listOf("Brief", "Detailed", "Comprehensive")),
                PromptField(id = "context", label = "Focus areas", placeholder = "Optional sub-topics", type = PromptFieldType.TEXT_AREA, required = false, isAdvanced = true)
            ),
            promptTemplate = """Research the following topic and provide a structured brief.

Topic: {{topic}}
Audience: {{audience}}
Depth: {{length}}

{{#context}}Focus: {{context}}{{/context}}

Structure:
- TL;DR (2 sentences)
- Key findings (bullet points)
- Trade-offs
- References / next steps
""",
        ),
        StudioTemplate(
            id = "brainstorm",
            title = "Brainstorm ideas",
            description = "Generate varied creative directions.",
            exampleUseCase = "Brainstorm 10 ideas for a fitness app.",
            category = StudioCategory.BRAINSTORM,
            qualityScore = 88,
            fields = listOf(
                PromptField(id = "topic", label = "Topic", placeholder = "e.g. Fitness app features", type = PromptFieldType.TEXT, required = true),
                PromptField(id = "audience", label = "Audience", placeholder = "General", type = PromptFieldType.TEXT, required = false),
                PromptField(id = "tone", label = "Style", placeholder = "Creative", type = PromptFieldType.SELECT, required = false, options = listOf("Creative", "Practical", "Bold", "Playful")),
                PromptField(id = "length", label = "Count", placeholder = "10 ideas", type = PromptFieldType.SELECT, required = false, options = listOf("5 ideas", "10 ideas", "20 ideas"))
            ),
            promptTemplate = """Brainstorm ideas about {{topic}}.

Audience: {{audience}}
Tone: {{tone}}
Goal: {{length}}

Vary approaches — practical, bold, playful, unexpected. For each idea, add one sentence on how it could work.
""",
        ),
        StudioTemplate(
            id = "explain_topic",
            title = "Explain topic",
            description = "Clear explanations for any audience.",
            exampleUseCase = "Explain quantum computing to a beginner.",
            category = StudioCategory.EXPLAIN,
            qualityScore = 91,
            fields = listOf(
                PromptField(id = "topic", label = "Topic to explain", placeholder = "e.g. How transformers work", type = PromptFieldType.TEXT, required = true),
                PromptField(id = "audience", label = "Audience", placeholder = "Beginner", type = PromptFieldType.SELECT, required = true, options = listOf("Beginner", "Intermediate", "Expert", "Child")),
                PromptField(id = "tone", label = "Tone", placeholder = "Friendly", type = PromptFieldType.SELECT, required = false, options = listOf("Friendly", "Professional", "Casual")),
                PromptField(id = "length", label = "Length", placeholder = "Medium", type = PromptFieldType.SELECT, required = false, options = listOf("Short", "Medium", "Detailed"))
            ),
            promptTemplate = """Explain {{topic}}.

Audience: {{audience}}
Tone: {{tone}}
Length: {{length}}

Use a simple analogy first, then build up to precise definition with a short example.
""",
        ),
        StudioTemplate(
            id = "prompt_engineering",
            title = "Engineer a prompt",
            description = "Turn a rough idea into a high-quality prompt.",
            exampleUseCase = "Make your prompt work better for small local models.",
            category = StudioCategory.PROMPT_ENGINEERING,
            qualityScore = 93,
            usefulnessTag = "For builders",
            fields = listOf(
                PromptField(id = "task", label = "Task description", placeholder = "e.g. Summarize meeting notes", type = PromptFieldType.TEXT_AREA, required = true),
                PromptField(id = "context", label = "Context", placeholder = "Who will use it, what input looks like", type = PromptFieldType.TEXT_AREA, required = false),
                PromptField(id = "output_format", label = "Desired output format", placeholder = "e.g. Bullet points, JSON, table", type = PromptFieldType.TEXT, required = true),
                PromptField(id = "audience", label = "Model target", placeholder = "Local small model", type = PromptFieldType.SELECT, required = false, options = listOf("Local small model", "Local large model", "Cloud model", "Tool-capable", "Code model")),
                PromptField(id = "examples", label = "Optional examples", placeholder = "Input -> Output examples", type = PromptFieldType.TEXT_AREA, required = false, isAdvanced = true)
            ),
            promptTemplate = """You are a prompt engineering expert. Create a high-quality prompt for:

Task: {{task}}
Context: {{context}}
Output format: {{output_format}}
Model: {{audience}}

{{#examples}}Examples:
{{examples}}{{/examples}}

Requirements:
- Clear role and task
- Constraints and output format
- Safety/clarification rules
- Keep it concise for small models if needed
""",
        ),
        StudioTemplate(
            id = "custom",
            title = "Custom prompt",
            description = "Build any prompt from scratch.",
            exampleUseCase = "Describe your task and get a refined prompt.",
            category = StudioCategory.CUSTOM,
            qualityScore = 85,
            fields = listOf(
                PromptField(id = "task", label = "Task description", placeholder = "e.g. Help me plan a trip to Japan", type = PromptFieldType.TEXT_AREA, required = true),
                PromptField(id = "context", label = "Context", placeholder = "Background, constraints", type = PromptFieldType.TEXT_AREA, required = false),
                PromptField(id = "output_format", label = "Desired output format", placeholder = "e.g. Checklist, table, paragraph", type = PromptFieldType.TEXT, required = false),
                PromptField(id = "tone", label = "Tone", placeholder = "Friendly", type = PromptFieldType.TEXT, required = false, isAdvanced = true),
                PromptField(id = "audience", label = "Audience", placeholder = "General", type = PromptFieldType.TEXT, required = false, isAdvanced = true),
                PromptField(id = "examples", label = "Optional examples", placeholder = "Examples to guide style", type = PromptFieldType.TEXT_AREA, required = false, isAdvanced = true)
            ),
            promptTemplate = """{{task}}

{{#context}}Context:
{{context}}{{/context}}

{{#output_format}}Output format: {{output_format}}{{/output_format}}
{{#tone}}Tone: {{tone}}{{/tone}}
{{#audience}}Audience: {{audience}}{{/audience}}

{{#examples}}Examples:
{{examples}}{{/examples}}
""",
        )
    )

    fun byId(id: String): StudioTemplate? = templates.find { it.id == id }

    fun byCategory(category: StudioCategory): List<StudioTemplate> =
        templates.filter { it.category == category }

    fun grouped(): Map<StudioCategory, List<StudioTemplate>> =
        templates.groupBy { it.category }
}
