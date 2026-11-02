package ai.opencode.android.ui.format

import ai.opencode.android.R
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalContext
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Compact relative timestamp for session lists and message headers. */
fun formatRelativeTime(then: Long, now: Long = System.currentTimeMillis()): String {
    if (then <= 0L) return ""
    val delta = (now - then).coerceAtLeast(0L)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(delta)
    val hours = TimeUnit.MILLISECONDS.toHours(delta)
    val days = TimeUnit.MILLISECONDS.toDays(delta)
    return when {
        delta < TimeUnit.MINUTES.toMillis(1) -> "now"
        minutes < 60 -> "${minutes}m ago"
        hours < 24 -> "${hours}h ago"
        days < 7 -> "${days}d ago"
        else -> SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(then))
    }
}

/**
 * Localised version of [formatRelativeTime] for the UI: "5 min ago", "vor 5 Min." … following the
 * app's interface language (resources come from the activity context).
 */
@Composable
@ReadOnlyComposable
fun relativeTime(then: Long, now: Long = System.currentTimeMillis()): String {
    if (then <= 0L) return ""
    val resources = LocalContext.current.resources
    val delta = (now - then).coerceAtLeast(0L)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(delta).toInt()
    val hours = TimeUnit.MILLISECONDS.toHours(delta).toInt()
    val days = TimeUnit.MILLISECONDS.toDays(delta).toInt()
    return when {
        delta < TimeUnit.MINUTES.toMillis(1) -> resources.getString(R.string.time_just_now)
        minutes < 60 -> resources.getQuantityString(R.plurals.time_minutes_ago, minutes, minutes)
        hours < 24 -> resources.getQuantityString(R.plurals.time_hours_ago, hours, hours)
        days < 7 -> resources.getQuantityString(R.plurals.time_days_ago, days, days)
        else -> DateFormat.getDateInstance(DateFormat.MEDIUM, resources.configuration.locales[0]).format(Date(then))
    }
}

fun formatClockTime(epochMillis: Long): String {
    if (epochMillis <= 0L) return ""
    return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(epochMillis))
}

fun String.ellipsize(max: Int): String = if (length <= max) this else take(max - 1) + "…"

/** One-line summary of a shell command or file path for compact cards. */
fun String.oneLine(max: Int = 120): String = replace(Regex("\\s+"), " ").trim().ellipsize(max)
