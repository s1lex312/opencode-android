package ai.opencode.android.data.remote

/** One dispatched Server-Sent-Events message. */
data class SseEvent(
    val type: String?,
    val data: String,
    val id: String? = null,
)

/**
 * Incremental parser for the `text/event-stream` wire format.
 *
 * Feed complete lines (without the trailing `\n`) via [feed]. A blank line dispatches the
 * buffered event; comment lines (`: heartbeat`) are ignored; `retry:` is ignored because the
 * client owns its own backoff policy. The parser is intentionally allocation-light and pure,
 * which keeps it unit-testable without a socket.
 */
class SseParser {

    private val data = StringBuilder()
    private var eventType: String? = null
    private var lastId: String? = null
    private var hasData = false

    /** Feeds one line. Returns a dispatched [SseEvent] when the line terminates a message. */
    fun feed(line: String): SseEvent? {
        val clean = line.removeSuffix("\r")
        if (clean.isEmpty()) return dispatch()

        if (clean.startsWith(":")) return null

        val colon = clean.indexOf(':')
        val field: String
        var value: String
        if (colon < 0) {
            field = clean
            value = ""
        } else {
            field = clean.substring(0, colon)
            value = clean.substring(colon + 1)
            if (value.startsWith(" ")) value = value.substring(1)
        }

        when (field) {
            "data" -> {
                if (hasData) data.append('\n')
                data.append(value)
                hasData = true
            }

            "event" -> eventType = value
            "id" -> lastId = value
            "retry" -> Unit
            else -> Unit
        }
        return null
    }

    /** Dispatches a partially buffered event, e.g. when the stream ends without a blank line. */
    fun flush(): SseEvent? = dispatch()

    fun reset() {
        data.setLength(0)
        eventType = null
        hasData = false
    }

    private fun dispatch(): SseEvent? {
        val event = if (hasData) SseEvent(eventType, data.toString(), lastId) else null
        data.setLength(0)
        hasData = false
        eventType = null
        return event
    }
}
