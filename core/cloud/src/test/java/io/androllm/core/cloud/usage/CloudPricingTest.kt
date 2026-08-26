package io.androllm.core.cloud.usage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cost estimation: known models, family heuristics, cache savings, formatting. */
class CloudPricingTest {

    @Test
    fun `known model pricing is used for cost estimates`() {
        // gpt-4o: $2.50/M input, $10/M output.
        val cost = CloudPricing.estimateCostMicros("openai/gpt-4o", 1_000_000, 1_000_000)
        assertEquals(12_500_000L, cost) // $12.50
    }

    @Test
    fun `provider prefix does not break matching`() {
        val direct = CloudPricing.estimateCostMicros("gpt-4o-mini", 1_000_000, 0)
        val prefixed = CloudPricing.estimateCostMicros("openai/gpt-4o-mini", 1_000_000, 0)
        assertEquals(direct, prefixed)
        assertEquals(150_000L, direct) // $0.15
    }

    @Test
    fun `claude and gemini families resolve`() {
        val claude = CloudPricing.pricingFor("anthropic/claude-3-5-sonnet-20241022")
        assertEquals(3.0, claude.inputPerMillionUsd, 0.001)
        assertEquals(15.0, claude.outputPerMillionUsd, 0.001)

        val gemini = CloudPricing.pricingFor("gemini/gemini-1.5-flash")
        assertEquals(0.075, gemini.inputPerMillionUsd, 0.001)
    }

    @Test
    fun `unknown models fall back to plausible heuristics`() {
        val tiny = CloudPricing.pricingFor("myhost/super-nano-v2")
        assertTrue(tiny.inputPerMillionUsd <= 0.5)

        val flagship = CloudPricing.pricingFor("myhost/mega-opus-ultra")
        assertTrue(flagship.inputPerMillionUsd >= 10.0)

        val mid = CloudPricing.pricingFor("myhost/unknown-model-x")
        assertTrue(mid.inputPerMillionUsd in 0.5..5.0)
    }

    @Test
    fun `zero tokens cost nothing`() {
        assertEquals(0L, CloudPricing.estimateCostMicros("openai/gpt-4o", 0, 0))
    }

    @Test
    fun `cache savings use discounted input price`() {
        val full = CloudPricing.estimateCostMicros("openai/gpt-4o", 1_000_000, 0)
        val saved = CloudPricing.estimateCacheSavingsMicros("openai/gpt-4o", 1_000_000)
        // 50% discount of the input price.
        assertEquals(full / 2, saved)
        assertEquals(0L, CloudPricing.estimateCacheSavingsMicros("openai/gpt-4o", 0))
    }

    @Test
    fun `usd formatting stays readable across magnitudes`() {
        assertEquals("$0.00", CloudPricing.formatUsd(0))
        assertEquals("$0.0042", CloudPricing.formatUsd(4_200))
        assertEquals("$0.123", CloudPricing.formatUsd(123_000))
        assertEquals("$12.35", CloudPricing.formatUsd(12_345_678))
    }
}
