package io.androllm.core.voice.tts.normalize

import java.util.regex.Pattern

/**
 * Units of measure → spoken units.
 *
 *   * "15 km" → "15 kilometers"   (the NumberNormalizer later turns the 15)
 *   * "2.4 kg" → "2.4 kilograms"
 *   * "1200 MHz" → "1200 megahertz"
 *   * "5 V" → "5 volts"; "3 A" → "3 amps"; "32 °C" → "32 degrees celsius"
 *   * "10km/h" → "10 kilometers per hour"
 *
 * Runs BEFORE the number stage so glued "10km" splits into "10 kilometers".
 */
class UnitNormalizer : TextProcessor {

    override fun process(input: String): String = replaceAll(input)

    companion object {
        private val UNITS = mapOf(
            // length
            "km" to "kilometers", "cm" to "centimeters", "mm" to "millimeters",
            "m" to "meters", "mi" to "miles", "ft" to "feet", "in" to "inches",
            "yd" to "yards", "nm" to "nanometers", "μm" to "micrometers",
            // area
            "sq km" to "square kilometers", "sq m" to "square meters",
            "ac" to "acres", "ha" to "hectares",
            // mass / volume
            "kg" to "kilograms", "g" to "grams", "mg" to "milligrams",
            "lb" to "pounds", "oz" to "ounces", "t" to "tons",
            "l" to "liters", "ml" to "milliliters", "gal" to "gallons",
            // temperature
            "°c" to "degrees celsius", "°f" to "degrees fahrenheit", "°" to "degrees",
            // time
            "ms" to "milliseconds", "s" to "seconds", "min" to "minutes",
            "hr" to "hours", "hrs" to "hours", "h" to "hours",
            // data
            "tb" to "terabytes", "gb" to "gigabytes", "mb" to "megabytes",
            "kb" to "kilobytes", "b" to "bytes",
            "tbps" to "terabits per second", "gbps" to "gigabits per second",
            "mbps" to "megabits per second", "kbps" to "kilobits per second",
            // frequency
            "ghz" to "gigahertz", "mhz" to "megahertz", "khz" to "kilohertz", "hz" to "hertz",
            // power / electricity
            "w" to "watts", "kw" to "kilowatts", "mw" to "megawatts",
            "v" to "volts", "kv" to "kilovolts", "mv" to "millivolts",
            "a" to "amps", "ma" to "milliamps", "ah" to "amp hours",
            // speed
            "km/h" to "kilometers per hour", "kmh" to "kilometers per hour",
            "mph" to "miles per hour", "m/s" to "meters per second",
            // misc
            "rpm" to "revolutions per minute", "%" to "percent",
            "dpi" to "dots per inch", "ppi" to "pixels per inch",
            "vpu" to "vapor pressure units"
        )
        // Longest units first so "mq ma" style prefixes never shadow.
        private val UNIT_PATTERN = Pattern.compile(
            """(\b\d+(?:,\d{3})*(?:\.\d+)?\s*)(sq km|sq m|km/h|mbps|gbps|kbps|tbps|m/s|kmh|mph|°C|°F|ghz|mhz|khz|hz|rpm|dpi|ppi|μ|°|km|cm|mm|mi|ft|in|yd|nm|kg|mg|lb|oz|ml|gal|mm|tb|gb|mb|kb|mw|kw|kv|mv|ma|ah|hr|hrs|min|sec|s|h|t|l|m|g|b|a|w|v)\b""",
            Pattern.CASE_INSENSITIVE
        )
    }

    private fun replaceAll(input: String): String {
        val matcher = UNIT_PATTERN.matcher(input)
        val sb = StringBuilder(input.length)
        var last = 0
        while (matcher.find()) {
            sb.append(input, last, matcher.start())
            val amount = matcher.group(1).trim()
            val unit = matcher.group(2)
            val spoken = expansion(unit) ?: unit
            sb.append(amount).append(' ').append(spoken)
            last = matcher.end()
        }
        sb.append(input, last, input.length)
        return sb.toString()
    }

    private fun expansion(unitRaw: String): String? {
        val unit = unitRaw.lowercase()
        return if (unitRaw.endsWith("°")) {
            UNITS["°"]
        } else {
            UNITS[unit]
        }
    }
}