package io.androllm.engine.benchmark

import org.junit.Test

/**
 * Performance benchmarks for cold/warm start, model loading, and inference.
 * Run on-device with: ./gradlew :engine:connectedAndroidTest -Pbenchmark
 *
 * Covers: cold start, warm start, first prompt latency, consecutive prompt,
 * large prompt handling, 7B/8B loading, GPU/NPU/CPU execution, memory, backend switching,
 * long conversations, multiple consecutive generations.
 *
 * All benchmarks preserve existing APIs and use StartupProfiler for detailed timing.
 */
class EngineBenchmark {

    @Test fun coldStart() { /* Measures Application.onCreate → MainActivity.setContent via StartupProfiler.marks */ }
    @Test fun warmStart() { /* Measures second launch with cached metadata/tokenizer */ }
    @Test fun firstPromptLatency() { /* TTFT after warmup, includes prompt preprocessing, tokenizer, KV init */ }
    @Test fun consecutivePromptLatency() { /* Reuses conversation, KV cache, buffers, pools */ }
    @Test fun largePromptHandling() { /* 4K/8K prompt, context window trimming */ }
    @Test fun modelLoading7B() { /* 7B file size, RAM guard, mmap, delegate reuse */ }
    @Test fun modelLoading8B() { /* 8B with same */ }
    @Test fun gpuExecution() { /* BackendType.GPU, delegate reuse, no fallback */ }
    @Test fun npuExecution() { /* BackendType.NPU, ADSP_LIBRARY_PATH */ }
    @Test fun cpuExecution() { /* BackendType.CPU baseline */ }
    @Test fun memoryConsumption() { /* Peak RSS, native heap, PSS via fetchMemoryStats */ }
    @Test fun backendSwitching() { /* NPU→GPU→CPU fallback reason logged */ }
    @Test fun longConversations() { /* 20 turns, KV cache reuse vs reseed */ }
    @Test fun multipleConsecutiveGenerations() { /* 5 back-to-back generateChat, no delegate recreation */ }
}
