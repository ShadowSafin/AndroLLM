package io.androllm.core.tools.agent

import javax.inject.Inject
import javax.inject.Singleton

/**
 * The agent's working memory for a single turn. Tools write their structured
 * outputs here ("weather", "search_results", "selected_contact", …) and the
 * planner re-reads the snapshot at the start of every round, so the result of
 * one tool becomes an input to the next WITHOUT the model having to remember
 * it (the "variables are passed between tools automatically" requirement).
 *
 * Scope rules:
 * - Variables live per conversation and are cleared by [beginTurn] — every new
 *   user message starts from a fresh slate, so stale values can never leak
 *   into a later task. Current time / battery / clipboard etc. are re-collected
 *   by [DeviceContextProvider] each turn anyway.
 * - The voice assistant (no conversation id) uses the [VOICE] scope.
 */
@Singleton
class AgentVariableStore @Inject constructor() {

    @Volatile
    private var currentScope: String = DEFAULT_SCOPE

    private val lock = Any()
    private val byScope = mutableMapOf<String, MutableMap<String, String>>()

    /** Conversation id the tool-calling pipeline is currently serving. */
    val scope: String get() = currentScope

    /** Call at the start of a chat turn; resets the variables for that scope. */
    fun beginTurn(conversationId: String?) {
        val next = conversationId?.takeIf { it.isNotBlank() } ?: DEFAULT_SCOPE
        // Scope pointer and map mutation update atomically so a concurrent
        // reader can never see the new scope with the old variables.
        synchronized(lock) {
            currentScope = next
            byScope.remove(next)
        }
    }

    fun set(key: String, value: String) = set(currentScope, key, value)

    fun set(scope: String, key: String, value: String) {
        synchronized(lock) {
            byScope.getOrPut(scope) { mutableMapOf() }[key.trim()] = value.trim()
        }
    }

    fun get(key: String): String? = get(currentScope, key)

    fun get(scope: String, key: String): String? = synchronized(lock) {
        byScope[scope]?.get(key.trim())
    }

    fun remove(key: String) {
        synchronized(lock) { byScope[currentScope]?.remove(key.trim()) }
    }

    /** Full snapshot for the current scope (used by the context builder). */
    fun snapshot(): Map<String, String> = snapshot(currentScope)

    fun snapshot(scope: String): Map<String, String> = synchronized(lock) {
        byScope[scope]?.toMap() ?: emptyMap()
    }

    fun clearAll() {
        synchronized(lock) { byScope.clear() }
    }

    companion object {
        /** Fallback scope when no conversation id is available (voice mode). */
        const val DEFAULT_SCOPE = "default"
        const val VOICE = "voice"
    }
}
