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
     * LOCAL mode is the default: bring the in-app server up as soon as the UI is on screen.
     * Everything is best-effort — if the environment is missing the Setup screen takes over and
     * the app still works in REMOTE mode.
     */
    private fun maybeStartLocalServer(container: AppContainer) {
        lifecycleScope.launch {
            val settings = runCatching { container.settingsRepository.current() }.getOrNull() ?: return@launch
            if (!settings.isLocalMode || !settings.localServer.enabled) return@launch
            if (container.localServerManager.state.value.running) return@launch
            runCatching { LocalServerService.start(this@MainActivity) }
        }
    }
}
