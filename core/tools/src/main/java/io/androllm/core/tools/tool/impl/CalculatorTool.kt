package io.androllm.core.tools.tool.impl

import io.androllm.core.tools.api.Tool
import io.androllm.core.tools.api.ToolCategory
import io.androllm.core.tools.api.ToolPermission
import io.androllm.core.tools.api.ToolResult
import io.androllm.core.tools.api.ToolSpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Evaluates arithmetic expressions with a hand-written recursive-descent
 * parser — NEVER eval(). Supports + - * / % ^, parentheses, decimals,
 * negatives and scientific notation ("1e3"). Division by zero and malformed
 * input return clear failures instead of throwing.
 */
@Singleton
class CalculatorTool @Inject constructor() : Tool {

    override val spec = ToolSpec(
        name = "calculate",
        description = "Evaluate a mathematical expression (e.g. '((15 + 3) * 2) / 4', '2^10', '500 * 0.07'). Returns the exact result.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("expression") { put("type", "string") }
            }
            putJsonArray("required") { add("expression") }
        },
        permission = ToolPermission.CALCULATOR,
        category = ToolCategory.INFORMATION,
        supportedTasks = listOf(
            "math", "arithmetic", "calculate", "computation", "multiply",
            "divide", "add", "subtract", "percentage", "equation", "sum"
        ),
        cacheable = true
    )

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val expression = ToolArgs.str(arguments, "expression", "expr", "math")
            ?: return ToolResult.Failure("Missing required argument: expression")
        return when (val result = Eval.run(expression)) {
            is Eval.Ok -> ToolResult.Success(
                "$expression = ${result.value.format()}",
                buildJsonObject { put("expression", expression); put("result", result.value.format()) }
            )
            is Eval.Err -> ToolResult.Failure("Could not calculate '$expression': ${result.reason}")
        }
    }
}

/** Minimal tokenizer + parser. Kept private so tools never eval arbitrary code. */
internal object Eval {

    sealed interface Result
    data class Ok(val value: Double) : Result
    data class Err(val reason: String) : Result

    fun run(expression: String): Result {
        val tokens = tokenize(expression)
            ?: return Err("unsupported characters — use digits and + - * / % ^ ( )")
        if (tokens.isEmpty()) return Err("empty expression")
        return try {
            val parser = Parser(tokens)
            val value = parser.parseExpression()
            when {
                parser.hasNext() -> Err("unexpected token '${parser.peek()}'")
                value.isNaN() -> Err("result is not a number")
                value.isInfinite() -> Err("result is too large")
                else -> Ok(value)
            }
        } catch (e: ArithmeticException) {
            Err(e.message ?: "math error")
        } catch (e: IllegalArgumentException) {
            Err(e.message ?: "malformed expression")
        }
    }

    private fun tokenize(expression: String): List<String>? {
        val out = mutableListOf<String>()
        var i = 0
        val s = expression.replace(" ", "")
        while (i < s.length) {
            val c = s[i]
            when {
                c.isDigit() || c == '.' -> {
                    val start = i
                    while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
                    // scientific notation: 1e3 / 2.5e-2
                    if (i < s.length && (s[i] == 'e' || s[i] == 'E') && i + 1 < s.length &&
                        (s[i + 1].isDigit() || s[i + 1] == '-' || s[i + 1] == '+')
                    ) {
                        i++
                        if (i < s.length && (s[i] == '-' || s[i] == '+')) i++
                        while (i < s.length && s[i].isDigit()) i++
                    }
                    out += s.substring(start, i)
                }
                c in "+-*/%^()" -> { out += c.toString(); i++ }
                else -> return null
            }
        }
        return out
    }

    private class Parser(private val tokens: List<String>) {
        private var pos = 0

        fun hasNext(): Boolean = pos < tokens.size
        fun peek(): String = if (pos < tokens.size) tokens[pos] else ""

        fun parseExpression(): Double {
            var value = parseTerm()
            while (true) {
                when (peek()) {
                    "+" -> { pos++; value += parseTerm() }
                    "-" -> { pos++; value -= parseTerm() }
                    else -> return value
                }
            }
        }

        private fun parseTerm(): Double {
            var value = parseFactor()
            while (true) {
                when (peek()) {
                    "*" -> { pos++; value *= parseFactor() }
                    "/" -> {
                        pos++
                        val divisor = parseFactor()
                        if (divisor == 0.0) throw ArithmeticException("division by zero")
                        value /= divisor
                    }
                    "%" -> {
                        pos++
                        val divisor = parseFactor()
                        if (divisor == 0.0) throw ArithmeticException("modulo by zero")
                        value %= divisor
                    }
                    else -> return value
                }
            }
        }

        private fun parseFactor(): Double {
            var negate = false
            while (peek() == "-") { negate = !negate; pos++ }
            var value = parseUnary()
            while (peek() == "^") {
                pos++
                // right-associative exponent
                val exp = parseFactor()
                value = Math.pow(value, exp)
            }
            return if (negate) -value else value
        }

        private fun parseUnary(): Double {
            return when (peek()) {
                "+" -> { pos++; parseUnary() }
                "(" -> {
                    pos++
                    val v = parseExpression()
                    if (peek() != ")") throw IllegalArgumentException("missing closing parenthesis")
                    pos++
                    v
                }
                else -> {
                    val tok = peek()
                    val d = tok.toDoubleOrNull()
                        ?: throw IllegalArgumentException("expected a number, got '$tok'")
                    pos++
                    d
                }
            }
        }
    }
}

private fun Double.format(): String =
    if (this == Math.floor(this) && !this.isInfinite() && Math.abs(this) < 1e15) {
        this.toLong().toString()
    } else {
        String.format(java.util.Locale.US, "%.6f", this).trimEnd('0').trimEnd('.')
    }
