package io.androllm.core.tools.tool.impl

import io.androllm.core.tools.api.Tool
import io.androllm.core.tools.api.ToolCategory
import io.androllm.core.tools.api.ToolPermission
import io.androllm.core.tools.api.ToolResult
import io.androllm.core.tools.api.ToolSpec
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Converts between common units: length, mass, temperature, data, volume,
 * speed and time. All conversions are exact (temperature uses the proper
 * affine formulas, not a linear factor).
 */
@Singleton
class UnitConverterTool @Inject constructor() : Tool {

    override val spec = ToolSpec(
        name = "convert_units",
        description = "Convert a value between units: length (m, km, mi, ft, in, cm), mass (kg, g, lb, oz), temperature (°C, °F, K), data (B, KB, MB, GB, TB), volume (L, mL, gal, cup), speed (km/h, mph, m/s), time (s, min, h, day).",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("value") { put("type", "number") }
                putJsonObject("from") { put("type", "string") }
                putJsonObject("to") { put("type", "string") }
            }
            putJsonArray("required") { add("value"); add("from"); add("to") }
        },
        permission = ToolPermission.CALCULATOR,
        category = ToolCategory.INFORMATION
    )

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val value = ToolArgs.double(arguments, "value")
            ?: return ToolResult.Failure("Missing required argument: value")
        val from = ToolArgs.str(arguments, "from", "unit")?.lowercase()
            ?: return ToolResult.Failure("Missing required argument: from")
        val to = ToolArgs.str(arguments, "to")?.lowercase()
            ?: return ToolResult.Failure("Missing required argument: to")

        val converted = convert(value, from, to)
            ?: return ToolResult.Failure("Unknown or incompatible units '$from' → '$to'.")
        return ToolResult.Success(
            "$value $from = ${format(converted)} $to",
            buildJsonObject {
                put("value", value)
                put("from", from)
                put("to", to)
                put("result", converted)
            }
        )
    }

    private fun convert(value: Double, from: String, to: String): Double? {
        // Temperature needs affine formulas; everything else is linear.
        val temp = TEMPERATURES[from] ?: TEMPERATURES[to]
        if (temp != null) {
            val f = TEMPERATURES[from] ?: return null
            val t = TEMPERATURES[to] ?: return null
            return f.toCelsius(value)?.let { t.fromCelsius(it) }
        }
        val f = LINEAR[from] ?: return null
        val t = LINEAR[to] ?: return null
        return value * f / t
    }

    private fun format(d: Double): String =
        if (d == Math.floor(d) && Math.abs(d) < 1e12) d.toLong().toString()
        else String.format(Locale.US, "%.4f", d).trimEnd('0').trimEnd('.')

    private companion object {
        /** Linear factors relative to each group's base unit. */
        val LINEAR = mapOf(
            // length (m)
            "m" to 1.0, "meter" to 1.0, "meters" to 1.0, "km" to 1000.0, "kilometer" to 1000.0,
            "kilometers" to 1000.0, "cm" to 0.01, "centimeter" to 0.01, "mm" to 0.001,
            "mi" to 1609.344, "mile" to 1609.344, "miles" to 1609.344, "ft" to 0.3048,
            "foot" to 0.3048, "feet" to 0.3048, "in" to 0.0254, "inch" to 0.0254, "inches" to 0.0254,
            // mass (kg)
            "kg" to 1.0, "kilogram" to 1.0, "kilograms" to 1.0, "g" to 0.001, "gram" to 0.001,
            "grams" to 0.001, "mg" to 1e-6, "lb" to 0.45359237, "lbs" to 0.45359237,
            "pound" to 0.45359237, "pounds" to 0.45359237, "oz" to 0.028349523125,
            "ounce" to 0.028349523125, "ounces" to 0.028349523125,
            // data (byte)
            "b" to 1.0, "byte" to 1.0, "bytes" to 1.0, "kb" to 1024.0, "kilobyte" to 1024.0,
            "mb" to 1048576.0, "megabyte" to 1048576.0, "gb" to 1073741824.0, "gigabyte" to 1073741824.0,
            "tb" to 1099511627776.0, "terabyte" to 1099511627776.0,
            // volume (L)
            "l" to 1.0, "liter" to 1.0, "liters" to 1.0, "ml" to 0.001, "milliliter" to 0.001,
            "gal" to 3.785411784, "gallon" to 3.785411784, "gallons" to 3.785411784,
            "cup" to 0.2365882365, "cups" to 0.2365882365, "tbsp" to 0.0147867648,
            "tsp" to 0.00492892159,
            // speed (m/s)
            "m/s" to 1.0, "km/h" to 0.277777778, "kph" to 0.277777778,
            "mph" to 0.44704, "mps" to 1.0,
            // time (s)
            "s" to 1.0, "sec" to 1.0, "secs" to 1.0, "second" to 1.0, "seconds" to 1.0,
            "min" to 60.0, "mins" to 60.0, "minute" to 60.0, "minutes" to 60.0,
            "h" to 3600.0, "hr" to 3600.0, "hrs" to 3600.0, "hour" to 3600.0, "hours" to 3600.0,
            "day" to 86400.0, "days" to 86400.0
        )

        val TEMPERATURES = mapOf(
            "c" to TempScale.C, "celsius" to TempScale.C,
            "f" to TempScale.F, "fahrenheit" to TempScale.F,
            "k" to TempScale.K, "kelvin" to TempScale.K
        )

        enum class TempScale {
            C, F, K;

            fun toCelsius(v: Double): Double? = when (this) {
                C -> v
                F -> (v - 32.0) * 5.0 / 9.0
                K -> v - 273.15
            }

            fun fromCelsius(c: Double): Double = when (this) {
                C -> c
                F -> c * 9.0 / 5.0 + 32.0
                K -> c + 273.15
            }
        }
    }
}

/**
 * Currency conversion with an offline reference-rate table. Explicitly
 * labelled approximate — the assistant should say so when quoting results.
 * Online rates are intentionally not fetched to keep the tool instant and
 * private; a future cloud provider can override these values.
 */
@Singleton
class CurrencyTool @Inject constructor() : Tool {

    override val spec = ToolSpec(
        name = "convert_currency",
        description = "Convert an amount between currencies (USD, EUR, GBP, JPY, INR, CAD, AUD, CHF, CNY, BRL, KRW, MXN, SEK, NOK, NZD, SGD, HKD, ZAR). Rates are approximate (fixed reference table) — mention they may not be live.",
        parameters = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("amount") { put("type", "number") }
                putJsonObject("from") { put("type", "string") }
                putJsonObject("to") { put("type", "string") }
            }
            putJsonArray("required") { add("amount"); add("from"); add("to") }
        },
        permission = ToolPermission.CALCULATOR,
        category = ToolCategory.INFORMATION
    )

    override suspend fun execute(arguments: JsonObject): ToolResult {
        val amount = ToolArgs.double(arguments, "amount")
            ?: return ToolResult.Failure("Missing required argument: amount")
        val from = ToolArgs.str(arguments, "from", "currency")?.uppercase()
            ?: return ToolResult.Failure("Missing required argument: from")
        val to = ToolArgs.str(arguments, "to")?.uppercase()
            ?: return ToolResult.Failure("Missing required argument: to")
        val fromRate = RATES[from] ?: return ToolResult.Failure("Unknown currency '$from'.")
        val toRate = RATES[to] ?: return ToolResult.Failure("Unknown currency '$to'.")
        val result = amount * fromRate / toRate
        return ToolResult.Success(
            "${formatMoney(amount)} $from ≈ ${formatMoney(result)} $to (approximate rates)",
            buildJsonObject {
                put("amount", amount)
                put("from", from)
                put("to", to)
                put("result", result)
                put("approximate", true)
            }
        )
    }

    private fun formatMoney(d: Double): String = String.format(Locale.US, "%,.2f", d)

    private companion object {
        /** Value of 1 unit of currency in USD (fixed reference, mid-2026). */
        val RATES = mapOf(
            "USD" to 1.0, "EUR" to 1.09, "GBP" to 1.27, "JPY" to 0.0067,
            "INR" to 0.012, "CAD" to 0.73, "AUD" to 0.66, "CHF" to 1.12,
            "CNY" to 0.14, "BRL" to 0.18, "KRW" to 0.00072, "MXN" to 0.055,
            "SEK" to 0.095, "NOK" to 0.092, "NZD" to 0.60, "SGD" to 0.74,
            "HKD" to 0.128, "ZAR" to 0.055
        )
    }
}
