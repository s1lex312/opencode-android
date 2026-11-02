package ai.opencode.android

import ai.opencode.android.di.AppContainer
import ai.opencode.android.ui.navigation.OpencodeNavHost
import ai.opencode.android.ui.theme.OpencodeTheme
import ai.opencode.android.server.LocalServerService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container: AppContainer = (application as OpencodeApp).container
        setContent {
            OpencodeTheme {
                OpencodeNavHost(container = container)
            }
        }

        maybeStartLocalServer(container)
    }

    /**
     * Honour `LocalServerSettings.enabled` by starting the foreground service. Everything is
     * best-effort: if the platform refuses the start (background restrictions, missing proot) the
     * app still works against a remote server, and the failure is reported by the supervisor.
     */
    private fun maybeStartLocalServer(container: AppContainer) {
        lifecycleScope.launch {
            val settings = runCatching { container.settingsRepository.current() }.getOrNull() ?: return@launch
            if (!settings.localServer.enabled) return@launch
            if (container.localServerManager.state.value.running) return@launch
            runCatching { LocalServerService.start(this@MainActivity) }
        }
    }
}
