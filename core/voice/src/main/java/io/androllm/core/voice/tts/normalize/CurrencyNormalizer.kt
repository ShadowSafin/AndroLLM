package io.androllm.core.voice.tts.normalize

import java.util.regex.Pattern

/**
 * Currencies → spoken amounts.
 *
 *   * "$20" → "twenty dollars"
 *   * "₹500" → "five hundred rupees"
 *   * "€19.99" → "nineteen euros and ninety-nine cents"
 *   * "£12.00" → "twelve pounds"
 *
 * Symbol table is small (the six most common currencies + a couple more);
 * ISO codes ("USD 20") are handled too. Cent values use the classic
 * "<main> <currency> and <cents> cents" pattern.
 */
class CurrencyNormalizer : TextProcessor {

    override fun process(input: String): String = replaceAll(input)

    companion object {
        private val SYMBOL_TO_CURRENCY = mapOf(
            "$" to "dollar", "€" to "euro", "£" to "pound", "¥" to "yen",
            "₹" to "rupee", "₽" to "ruble", "₩" to "won", "₺" to "lira",
            "₫" to "dong", "₪" to "shekel", "฿" to "baht", "₦" to "naira",
            "₱" to "peso", "₴" to "hryvnia", "₸" to "tenge", "₼" to "manat"
        )
        private val CODE_TO_CURRENCY = mapOf(
            "USD" to "dollar", "EUR" to "euro", "GBP" to "pound", "JPY" to "yen",
            "INR" to "rupee", "RUB" to "ruble", "KRW" to "won", "TRY" to "lira",
            "CAD" to "canadian dollar", "AUD" to "australian dollar", "CNY" to "yuan"
        )
        // "₹500", "$ 20", "€19.99"; symbol optionally space-separated.
        private val SYMBOL_AMOUNT = Pattern.compile(
            """([$€£¥₹₽₩₺₫₲₦₱฿₸₿])\s*(-?\d+(?:,\d{3})*(?:\.\d+)?)"""
        )
        // "USD 20.50", " € 12" — ISO code form (capital letters before digits).
        private val CODE_AMOUNT = Pattern.compile(
            """\b(USD|EUR|GBP|JPY|INR|RUB|KRW|TRY|BND|CAD|AUD|CNY)\s+(-?\d+(?:,\d{3})*(?:\.\d+)?)\b"""
        )
    }

    private fun replaceAll(input: String): String {
        var text = input
        // Symbols with exact widths ("$5") — must run before the plain
        // number stage (which runs much later), and before "$" cleanup.
        text = SYMBOL_AMOUNT.matcher(text).replaceAll { m ->
            amountToWords(m.group(1), m.group(2)) ?: m.group()
        }
        // ISO codes: "USD 20".
        text = CODE_AMOUNT.matcher(text).replaceAll { m ->
            amountToWords(m.group(1), m.group(2)) ?: m.group()
        }
        // Lone symbols with no amount ("USD" alone, "$"), spoken as units.
        text = text.replace("$", " dollars ")
        text = text.replace("€", " euros ")
        text = text.replace("₹", " rupees ")
        text = text.replace("£", " pounds ")
        text = text.replace("¥", " yen ")
        return text
    }

    private fun amountToWords(symbol: String, amount: String): String? {
        val name = CODE_TO_CURRENCY[symbol] ?: SYMBOL_TO_CURRENCY[symbol] ?: return null
        val negative = amount.startsWith("-")
        val body = amount.removePrefix("-")
        val whole = body.substringBefore('.').replace(",", "").toLongOrNull() ?: return null
        val centsStr = body.substringAfter('.', missingDelimiterValue = "")
        val cents = if (centsStr.isEmpty()) null else {
            centsStr.padEnd(2, '0').take(2).toLongOrNull()?.takeIf { it > 0 }
        }
        val sb = StringBuilder()
        if (negative) sb.append("minus ")
        sb.append(SpeechNumbers.int(whole))
        sb.append(' ').append(name)
        if (cents != null) {
            sb.append(" and ").append(SpeechNumbers.int(cents)).append(" cents")
        }
        return sb.toString()
    }
}