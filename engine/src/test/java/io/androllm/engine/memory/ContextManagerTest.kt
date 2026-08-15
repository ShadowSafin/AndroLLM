package io.androllm.engine.memory

import io.androllm.core.common.AppConstants
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Deterministic context resolution: an explicit context is always chosen
 * BEFORE any native allocation so the RAM guard and the actual llama.cpp
 * context agree (n_ctx=0 would silently mean "train context").
 */
class ContextManagerTest {

    @Test
    fun `explicit context is passed through`() {
        assertEquals(4096, ContextManager.resolveContextLength(4096))
        assertEquals(512, ContextManager.resolveContextLength(512))
    }

    @Test
    fun `zero context resolves to the app default`() {
        assertEquals(AppConstants.Model.DEFAULT_CONTEXT_LENGTH, ContextManager.resolveContextLength(0))
        assertEquals(AppConstants.Model.DEFAULT_CONTEXT_LENGTH, ContextManager.resolveContextLength(-1))
    }

    @Test
    fun `clamp never exceeds the train context`() {
        assertEquals(4096, ContextManager.clampToTrainContext(8192, 4096))
        assertEquals(8192, ContextManager.clampToTrainContext(8192, 0))
        assertEquals(2048, ContextManager.clampToTrainContext(2048, 32768))
    }
}