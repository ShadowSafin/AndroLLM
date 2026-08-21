package io.androllm.core.memory.classify

import io.androllm.core.memory.MemoryCategory
import io.androllm.core.memory.MemoryType
import io.androllm.core.memory.model.ExtractedMemory
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Classifies extracted memories into lifecycle types and handles prompt-memory intents.
 * Determines type, priority, and expiry based on content stability and scope.
 */
@Singleton
class MemoryClassifier @Inject constructor() {

    /**
     * Classifies the lifecycle type for an extracted memory.
     */
    fun classifyType(extracted: ExtractedMemory, chatId: String?): MemoryType {
        val contentLower = extracted.content.lowercase()
        val hasProject = !extracted.projectName.isNullOrBlank() || extracted.category == MemoryCategory.PROJECTS

        // Project memory: explicit project tie or project-specific context
        if (hasProject || contentLower.contains("project context") || contentLower.contains("remember this project")) {
            return MemoryType.PROJECT
        }
        // Session memory: recent chats, recent prompts, frequent commands, recurring tasks
        if (contentLower.contains("recent") || contentLower.contains("last chat") ||
            contentLower.contains("previous prompt") || contentLower.contains("frequent") ||
            contentLower.contains("recurring task") || contentLower.contains("recent prompt")
        ) {
            return MemoryType.SESSION
        }
        // Short-term: current conversation context, temporary working state
        if (contentLower.contains("current conversation") || contentLower.contains("this chat") ||
            contentLower.contains("right now") || contentLower.contains("in this session") ||
            (extracted.category == MemoryCategory.CUSTOM && contentLower.contains("context"))
        ) {
            return MemoryType.SHORT_TERM
        }
        // Default: stable preferences, facts, project-agnostic knowledge -> long-term
        return MemoryType.LONG_TERM
    }

    /**
     * Computes priority (1..5) based on category, content signals, and explicit importance.
     * Stable preferences and pinned facts get higher priority.
     */
    fun computePriority(extracted: ExtractedMemory, type: MemoryType): Int {
        var priority = extracted.importance.coerceIn(1, 5)
        // Prompt memory signals boost priority
        val promptSignals = listOf("prefer", "喜欢", "format", "code block", "short prompt", "tone", "template", "instruction")
        if (promptSignals.any { it in extracted.content.lowercase() }) {
            priority = maxOf(priority, 4)
        }
        // Long-term stable facts get higher than short-term
        when (type) {
            MemoryType.LONG_TERM -> priority = maxOf(priority, 3)
            MemoryType.PROJECT -> priority = maxOf(priority, 3)
            MemoryType.SESSION -> priority = minOf(priority, 3)
            MemoryType.SHORT_TERM -> priority = minOf(priority, 2)
        }
        // Pinned facts category is always high
        if (extracted.category == MemoryCategory.PINNED_FACTS) priority = 5
        return priority.coerceIn(1, 5)
    }

    /**
     * Computes expiryAt timestamp based on type's default TTL. Null = never expires.
     */
    fun computeExpiry(type: MemoryType, now: Long = System.currentTimeMillis()): Long? {
        val ttl = type.defaultTtlMs ?: return null
        return now + ttl
    }

    /**
     * Detects prompt-memory intent: how user likes prompts formatted, tone, templates.
     */
    fun isPromptMemory(content: String): Boolean {
        val lower = content.lowercase()
        return listOf(
            "prompt", "format", "template", "tone", "style", "code block", "copyable",
            "instruction", "prefer short", "prefer long", "writing style", "use this formatting"
        ).any { it in lower }
    }
}
