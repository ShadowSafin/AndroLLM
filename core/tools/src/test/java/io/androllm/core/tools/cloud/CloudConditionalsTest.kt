package io.androllm.core.tools.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Conditional tool execution: "if weather says rain then send SMS",
 * "if search results are found then email them".
 */
class CloudConditionalsTest {

    @Test
    fun `sms is skipped when observed weather is clearly dry`() {
        val reason = CloudConditionals.evaluateSkip(
            userQuery = "Check the weather and if it rains, send an SMS to Mom",
            callName = "send_sms",
            observations = mapOf("get_weather" to "Sunny, clear sky, 24°C, 0% rain")
        )
        assertNotNull(reason)
        assertEquals("the observed weather reports no rain, and the user asked for this only if it rains", reason)
    }

    @Test
    fun `sms proceeds when observed weather shows rain`() {
        val reason = CloudConditionals.evaluateSkip(
            userQuery = "Check the weather and if it rains, send an SMS to Mom",
            callName = "send_sms",
            observations = mapOf("get_weather" to "Light rain expected, 18°C, 80% precipitation")
        )
        assertNull(reason)
    }

    @Test
    fun `sms proceeds when weather is ambiguous`() {
        val reason = CloudConditionals.evaluateSkip(
            userQuery = "If it rains tell Mom to bring an umbrella",
            callName = "send_sms",
            observations = mapOf("get_weather" to "Partly cloudy with a chance of showers, 20°C")
        )
        // "showers" is a rain indicator AND "partly cloudy" a no-rain one —
        // conflicting signals proceed (the confirmation gate still protects).
        assertNull(reason)
    }

    @Test
    fun `sms proceeds when no weather was observed yet`() {
        val reason = CloudConditionals.evaluateSkip(
            userQuery = "If it rains message Mom",
            callName = "send_sms",
            observations = emptyMap()
        )
        assertNull(reason)
    }

    @Test
    fun `email is skipped when search found nothing`() {
        val reason = CloudConditionals.evaluateSkip(
            userQuery = "Search for Kotlin 2.2 release notes and if you find anything, email it to me",
            callName = "send_email",
            observations = mapOf("search_web" to "No results found for the query.")
        )
        assertNotNull(reason)
    }

    @Test
    fun `email proceeds when search found results`() {
        val reason = CloudConditionals.evaluateSkip(
            userQuery = "Search for Kotlin 2.2 release notes and if you find anything, email it to me",
            callName = "send_email",
            observations = mapOf("search_web" to "Kotlin 2.2.0 released with new compiler features...")
        )
        assertNull(reason)
    }

    @Test
    fun `no conditional phrase means no skipping`() {
        val reason = CloudConditionals.evaluateSkip(
            userQuery = "Send an SMS to Mom about dinner",
            callName = "send_sms",
            observations = mapOf("get_weather" to "Clear sky, sunny")
        )
        assertNull(reason)
    }

    @Test
    fun `non action tools are never skipped`() {
        val reason = CloudConditionals.evaluateSkip(
            userQuery = "If it rains check the weather again",
            callName = "get_weather",
            observations = mapOf("get_weather" to "Sunny, clear sky")
        )
        assertNull(reason)
    }

    @Test
    fun `blank query or call name never skips`() {
        assertNull(
            CloudConditionals.evaluateSkip("", "send_sms", observations = mapOf("get_weather" to "sunny clear"))
        )
        assertNull(
            CloudConditionals.evaluateSkip("if it rains sms mom", "", observations = emptyMap())
        )
    }

    @Test
    fun `last_tool_output fallback is honored for aliased tools`() {
        val reason = CloudConditionals.evaluateSkip(
            userQuery = "Check weather, if it rains text Mom",
            callName = "send_sms",
            observations = mapOf("last_tool_output" to "Forecast: sunny and dry, no rain")
        )
        assertNotNull(reason)
    }
}
