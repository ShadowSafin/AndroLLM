package io.androllm.core.models.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuantClassifierTest {

    private fun expect(quant: String, level: QuantLevel) {
        assertEquals("$quant should classify as $level", level, QuantClassifier.classify(quant))
        assertTrue("$quant should be known", QuantClassifier.isKnown(quant))
    }

    @Test
    fun classifiesKnownKQuants() {
        expect("Q2_K", QuantLevel.Q2)
        expect("Q3_K_M", QuantLevel.Q3)
        expect("Q4_K_S", QuantLevel.Q4)
        expect("Q4_K_M", QuantLevel.Q4)
        expect("Q5_K_M", QuantLevel.Q5)
        expect("Q6_K", QuantLevel.Q6)
        expect("Q8_0", QuantLevel.Q8)
    }

    @Test
    fun classifiesLegacyQuants() {
        expect("Q4_0", QuantLevel.Q4)
        expect("Q4_1", QuantLevel.Q4)
        expect("Q5_0", QuantLevel.Q5)
        expect("Q5_1", QuantLevel.Q5)
        expect("F16", QuantLevel.F16)
        expect("FP16", QuantLevel.F16)
        expect("BF16", QuantLevel.BF16)
    }

    @Test
    fun classifiesIMatrixQuants() {
        expect("IQ1_S", QuantLevel.IQ1)
        expect("IQ1_M", QuantLevel.IQ1)
        expect("IQ2_XXS", QuantLevel.IQ2)
        expect("IQ2_XS", QuantLevel.IQ2)
        expect("IQ2_M", QuantLevel.IQ2)
        expect("IQ3_XXS", QuantLevel.IQ3)
        expect("IQ3_M", QuantLevel.IQ3)
        expect("IQ4_XS", QuantLevel.IQ4)
        expect("IQ4_NL", QuantLevel.IQ4)
    }

    @Test
    fun handlesLowercaseAndHyphenatedFileNames() {
        expect("q4_k_m", QuantLevel.Q4)
        expect("q8-0", QuantLevel.Q8)
        expect("iq4-xs", QuantLevel.IQ4)
    }

    @Test
    fun unknownStringsClassifyAsOther() {
        assertEquals(QuantLevel.OTHER, QuantClassifier.classify("IQ5_0"))
        assertEquals(QuantLevel.OTHER, QuantClassifier.classify("XYZ"))
        assertFalse(QuantClassifier.isKnown("IQ5_0"))
    }
}

class ParameterCountTest {

    @Test
    fun parsesBillionsAndMillions() {
        assertEquals(1.5, ParameterCount.parse("1.5B")!!, 0.001)
        assertEquals(8.0, ParameterCount.parse("8B")!!, 0.001)
        assertEquals(0.5, ParameterCount.parse("0.5B")!!, 0.001)
        assertEquals(0.407, ParameterCount.parse("407M")!!, 0.001)
        assertEquals(1.7, ParameterCount.parse("1.7b")!!, 0.001)
    }

    @Test
    fun returnsNullForUnparsable() {
        assertNull(ParameterCount.parse(""))
        assertNull(ParameterCount.parse("unknown"))
    }
}
