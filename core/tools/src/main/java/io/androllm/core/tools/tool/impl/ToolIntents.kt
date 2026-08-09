package io.androllm.core.tools.tool.impl

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Intent helpers shared by the launch-based tools. All tools run outside an
 * Activity (voice service or chat pipeline), so every intent needs
 * FLAG_ACTIVITY_NEW_TASK; on Android 10+ the system may still block background
 * starts, in which case the tool reports a clear failure instead of crashing.
 */
object ToolIntents {

    fun launch(context: Context, intent: Intent): Boolean = runCatching {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    }.getOrDefault(false)

    /** Safely builds a `tel:` intent (ACTION_DIAL needs no permission). */
    fun dialUri(phone: String): Intent =
        Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(phone)}"))

    fun geoUri(query: String, lat: Double? = null, lon: Double? = null): Uri {
        val q = Uri.encode(query)
        return if (lat != null && lon != null) {
            Uri.parse("geo:$lat,$lon?q=$q")
        } else {
            Uri.parse("geo:0,0?q=$q")
        }
    }

    fun mapsNavigationUri(query: String, mode: String?): Uri {
        val m = mode?.lowercase()?.takeIf { it in setOf("drive", "walk", "transit", "bicycle", "bike") }
        val suffix = if (m != null) "&mode=$m" else ""
        return Uri.parse("google.navigation:q=${Uri.encode(query)}$suffix")
    }
}
