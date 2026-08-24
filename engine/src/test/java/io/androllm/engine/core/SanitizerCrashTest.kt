package io.androllm.engine.core

import org.junit.Assert.*
import org.junit.Test

class SanitizerCrashTest {
    @Test fun emptyString() {
        assertEquals("", OutputSanitizer.sanitize(""))
    }
    @Test fun blankString() {
        assertEquals("", OutputSanitizer.sanitize("   "))
    }
    @Test fun controlTokens() {
        assertEquals("Hello", OutputSanitizer.sanitize("Hello <|im_start|>"))
    }
    @Test fun nestedTags() {
        assertEquals("", OutputSanitizer.sanitize("<tool_call>get_weather</tool_call>"))
    }
    @Test fun toolCallBlock() {
        assertEquals("Done.", OutputSanitizer.sanitize("Done.<tool_call>x</tool_call>"))
    }
    @Test fun byteFallback() {
        assertEquals("Hi", OutputSanitizer.sanitize("Hi<0x0A>"))
    }
    @Test fun trailingPartialTag() {
        assertEquals("Hello", OutputSanitizer.sanitize("Hello<_"))
    }
    @Test fun unicodeSurvives() {
        assertEquals("Привет", OutputSanitizer.sanitize("Привет"))
    }
    @Test fun comparisonSurvives() {
        assertEquals("2 < 3", OutputSanitizer.sanitize("2 < 3"))
    }
    @Test fun streamingReadyEmpty() {
        assertEquals("", OutputSanitizer.streamingReady(""))
    }
    @Test fun streamingReadyHoldsBackPartialTag() {
        val result = OutputSanitizer.streamingReady("Hello<tool_call>partial")
        assertFalse(result.contains("<tool_call>"))
    }
}
