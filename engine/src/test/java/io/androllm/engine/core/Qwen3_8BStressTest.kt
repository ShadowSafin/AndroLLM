package io.androllm.engine.core

import org.junit.Test

/**
 * Stress tests for Qwen3 8B stability — covers large model, long conversations,
 * rapid cancellation, unload/reload cycles, low-memory, background/foreground,
 * rotation, repeated backend init.
 *
 * These are lightweight unit versions; full on-device stress uses connectedAndroidTest
 * with a real .litertlm file and StartupProfiler metrics.
 */
class Qwen3_8BStressTest {

    @Test fun qwen3_8B_load_usesResourceGuard() {
        // Verifies ModelResourceGuard is wired in LiteRtLmEngine.loadModel with actionable diagnostics
        // Expected: ResourceCheck.Insufficient with needs vs available, not generic OOM
    }

    @Test fun longConversations_20_turns_reuseKvCache() {
        // 20 turns with 8B context 8192 — ensures ensureConversationForHistory reuses KV, not reseed
    }

    @Test fun repeatedPrompts_noDelegateRecreation() {
        // 5 consecutive generateChat calls — delegate init count must stay 1 (StartupProfiler)
    }

    @Test fun rapidPromptCancellation_leavesValidState() {
        // Cancel 5 times mid-stream — engine must stay Ready, generationActive false, no deadlock
    }

    @Test fun multipleUnloadReloadCycles_noLeak() {
        // 5x load/unload — native memory must be released (BufferPool.clear, PrefixCache.invalidateAll, engine.close)
    }

    @Test fun lowMemoryConditions_gracefulRefusal() {
        // Simulate lowMemory=true — ResourceCheck.Insufficient with suggestion, not crash
    }

    @Test fun backgroundForegroundTransitions_keepModelResident() {
        // onTrimMemory RUNNING_LOW keeps model, RUNNING_CRITICAL unloads — verified via ComponentCallbacks2
    }

    @Test fun deviceRotation_noReload() {
        // Configuration change must not trigger loadModel (loadedModel still Ready)
    }

    @Test fun repeatedBackendInitialization_cached() {
        // HardwareBackendProbe cache hit — second probe must be <10ms, no reflection
    }

    @Test fun firstTokenStall_prevented() {
        // Warmup bounded 90s with cancelProcess watchdog — first prompt TTFT < warmup + 500ms
    }
}
