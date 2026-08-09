package io.androllm.core.tools.tool.impl

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CalculatorToolTest {

    @Test
    fun `basic arithmetic`() {
        assertThat(Eval.run("2+3")).isEqualTo(Eval.Ok(5.0))
        assertThat(Eval.run("10-4")).isEqualTo(Eval.Ok(6.0))
        assertThat(Eval.run("6*7")).isEqualTo(Eval.Ok(42.0))
        assertThat(Eval.run("8/2")).isEqualTo(Eval.Ok(4.0))
        assertThat(Eval.run("10%3")).isEqualTo(Eval.Ok(1.0))
        assertThat(Eval.run("2^10")).isEqualTo(Eval.Ok(1024.0))
    }

    @Test
    fun `precedence and parentheses`() {
        assertThat(Eval.run("2+3*4")).isEqualTo(Eval.Ok(14.0))
        assertThat(Eval.run("(2+3)*4")).isEqualTo(Eval.Ok(20.0))
        assertThat(Eval.run("((15 + 3) * 2) / 4")).isEqualTo(Eval.Ok(9.0))
    }

    @Test
    fun `decimals and negatives`() {
        assertThat(Eval.run("-5+3")).isEqualTo(Eval.Ok(-2.0))
        assertThat(Eval.run("0.5*2")).isEqualTo(Eval.Ok(1.0))
        assertThat(Eval.run("1e3")).isEqualTo(Eval.Ok(1000.0))
    }

    @Test
    fun `errors never throw`() {
        assertThat(Eval.run("8/0")).isInstanceOf(Eval.Err::class.java)
        assertThat(Eval.run("2+")).isInstanceOf(Eval.Err::class.java)
        assertThat(Eval.run("(2+3")).isInstanceOf(Eval.Err::class.java)
        assertThat(Eval.run("hello")).isInstanceOf(Eval.Err::class.java)
        assertThat(Eval.run("")).isInstanceOf(Eval.Err::class.java)
    }
}
