package io.androllm.core.tools.tool.impl

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Lenient argument readers shared by every tool. */
object ToolArgs {

    /** First non-blank string argument among [keys], or null. */
    fun str(args: JsonObject, vararg keys: String): String? {
        for (key in keys) {
            val el = args[key] ?: continue
            val v = (el as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            if (v.isNotEmpty()) return v
        }
        return null
    }

    fun bool(args: JsonObject, key: String, default: Boolean = false): Boolean =
        (args[key] as? kotlinx.serialization.json.JsonPrimitive)?.booleanOrNull ?: default

    fun int(args: JsonObject, key: String, default: Int = 0): Int =
        (args[key] as? kotlinx.serialization.json.JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.trim()?.toIntOrNull() }
            ?: default

    fun double(args: JsonObject, key: String): Double? =
        (args[key] as? kotlinx.serialization.json.JsonPrimitive)?.let {
            it.doubleOrNull ?: it.contentOrNull?.trim()?.toDoubleOrNull()
        }

    fun long(args: JsonObject, key: String): Long? =
        (args[key] as? kotlinx.serialization.json.JsonPrimitive)?.let {
            it.longOrNull ?: it.contentOrNull?.trim()?.toLongOrNull()
        }
}

/** Parses the human-friendly time strings the LLM tends to produce. */
object ToolTime {

    private val iso = DateTimeFormatter.ISO_LOCAL_DATE_TIME

    /**
     * Accepts: epoch millis, `2026-08-09T14:30`, `2026-08-09 14:30`, `14:30`
     * (next occurrence), `09:00 AM`. Returns epoch millis or null.
     */
    fun parseMillis(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        val t = raw.trim()
        t.toLongOrNull()?.let { return it }

        val zone = ZoneId.systemDefault()

        runCatching {
            return LocalDateTime.parse(t, iso).atZone(zone).toInstant().toEpochMilli()
        }
        runCatching {
            return LocalDateTime.parse(t.replace(' ', 'T'), iso).atZone(zone).toInstant().toEpochMilli()
        }

        // "14:30" / "9:00 AM"
        val ampm = Regex("""(\d{1,2}):(\d{2})\s*([ap]\.?m\.?)?""", RegexOption.IGNORE_CASE)
            .find(t)
        ampm?.let { m ->
            var hour = m.groupValues[1].toIntOrNull() ?: return null
            val min = m.groupValues[2].toIntOrNull() ?: return null
            val period = m.groupValues[3].lowercase().replace(".", "")
            if (period == "pm" && hour < 12) hour += 12
            if (period == "am" && hour == 12) hour = 0
            var dt = LocalDateTime.of(LocalDate.now(), LocalTime.of(hour.coerceIn(0, 23), min.coerceIn(0, 59)))
            if (!dt.isAfter(LocalDateTime.now())) dt = dt.plusDays(1)
            return dt.atZone(zone).toInstant().toEpochMilli()
        }

        // "tomorrow 9am"
        if (t.contains("tomorrow", ignoreCase = true)) {
            val hhmm = Regex("""(\d{1,2})(?::(\d{2}))?\s*([ap]\.?m\.?)?""", RegexOption.IGNORE_CASE)
                .find(t)
            hhmm?.let { m ->
                var hour = m.groupValues[1].toIntOrNull() ?: return null
                val min = m.groupValues[2].toIntOrNull() ?: 0
                val period = m.groupValues[3].lowercase().replace(".", "")
                if (period == "pm" && hour < 12) hour += 12
                if (period == "am" && hour == 12) hour = 0
                val dt = LocalDateTime.of(LocalDate.now().plusDays(1), LocalTime.of(hour.coerceIn(0, 23), min.coerceIn(0, 59)))
                return dt.atZone(zone).toInstant().toEpochMilli()
            }
        }
        return null
    }
}
