package ai.opencode.android.notify

import ai.opencode.android.OpencodeApp
import ai.opencode.android.R
import ai.opencode.android.data.model.PermissionDecision
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import ai.opencode.android.data.model.ConnectionState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Answers a permission request from the notification buttons without opening the app. */
class NotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_PERMISSION) return
        val sessionId = intent.getStringExtra(EXTRA_SESSION) ?: return
        val permissionId = intent.getStringExtra(EXTRA_PERMISSION) ?: return
        val decision = intent.getStringExtra(EXTRA_DECISION)
            ?.let { runCatching { PermissionDecision.valueOf(it) }.getOrNull() } ?: return
        val container = (context.applicationContext as OpencodeApp).container

        val pending = goAsync()
        container.appScope.launch {
            try {
                if (!container.connectionManager.isConnected) container.connectionManager.connect()
                val ok = withTimeoutOrNull(REPLY_TIMEOUT_MS) {
                    container.connectionManager.state.first { it is ConnectionState.Connected }
                    container.agentNotifier.reply(sessionId, permissionId, decision)
                } ?: false
                if (!ok) Toast.makeText(context, R.string.notify_reply_failed, Toast.LENGTH_LONG).show()
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_PERMISSION = "ai.opencode.android.action.PERMISSION_REPLY"
        const val EXTRA_SESSION = "session"
        const val EXTRA_PERMISSION = "permission"
        const val EXTRA_DECISION = "decision"
        private const val REPLY_TIMEOUT_MS = 8_000L
    }
}
