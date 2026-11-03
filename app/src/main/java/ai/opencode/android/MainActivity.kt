package ai.opencode.android

import ai.opencode.android.di.AppContainer
import ai.opencode.android.server.LocalServerService
import ai.opencode.android.settings.AppearanceSettings
import ai.opencode.android.ui.navigation.OpencodeNavHost
import ai.opencode.android.ui.theme.OpencodeTheme
import ai.opencode.android.ui.theme.isDark
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * AppCompatActivity (not ComponentActivity) because per-app languages on Android 12 and older are
 * applied by AppCompat's delegate; on 13+ the platform LocaleManager does it.
 */
class MainActivity : AppCompatActivity() {

    /** Chat to open, set by notification taps (cold start or [onNewIntent]). */
    private val openSessionRequest = mutableStateOf<String?>(null)

    /** Bumped when another app shares something; the nav host opens the Share screen. */
    private val shareRequest = mutableIntStateOf(0)

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container: AppContainer = (application as OpencodeApp).container
        if (savedInstanceState == null) {
            openSessionRequest.value = intent?.getStringExtra(EXTRA_SESSION_ID)
            handleShare(container, intent)
        }
        // One synchronous DataStore read so the first frame already has the chosen theme instead
        // of flashing the system one.
        val initialAppearance = runBlocking {
            runCatching { container.settingsRepository.current().appearance }.getOrDefault(AppearanceSettings())
        }
        val appearanceFlow = container.settingsRepository.settings.map { it.appearance }.distinctUntilChanged()
        enableEdgeToEdge()
        setContent {
            val appearance by appearanceFlow.collectAsStateWithLifecycle(initialValue = initialAppearance)
            val dark = appearance.isDark()
            DisposableEffect(dark) {
                val style = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose {}
            }
            OpencodeTheme(appearance = appearance) {
                OpencodeNavHost(
                    container = container,
                    openSessionRequest = openSessionRequest.value,
                    onSessionRequestHandled = { openSessionRequest.value = null },
                    shareRequest = shareRequest.intValue,
                )
            }
        }

        maybeStartLocalServer(container)
        maybeAskNotificationPermission(container)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_SESSION_ID)?.let { openSessionRequest.value = it }
        handleShare((application as OpencodeApp).container, intent)
    }

    /** `ACTION_SEND` / `ACTION_SEND_MULTIPLE` from other apps: text, links, images, files. */
    private fun handleShare(container: AppContainer, intent: Intent?) {
        if (intent == null) return
        val action = intent.action
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty()
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)
        val uris = buildList<android.net.Uri> {
            if (action == Intent.ACTION_SEND) {
                streamExtra(intent)?.let(::add)
            } else {
                addAll(streamListExtra(intent))
            }
            // Some apps only fill ClipData.
            val clip = intent.clipData
            if (isEmpty() && clip != null) {
                for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let(::add)
            }
        }.distinct()
        if (text.isBlank() && uris.isEmpty()) return
        container.pendingShare.value = ai.opencode.android.data.share.SharedContent(text, uris, subject)
        shareRequest.intValue += 1
    }

    @Suppress("DEPRECATION")
    private fun streamExtra(intent: Intent): android.net.Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }

    @Suppress("DEPRECATION")
    private fun streamListExtra(intent: Intent): List<android.net.Uri> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java).orEmpty()
        } else {
            intent.getParcelableArrayListExtra<android.net.Uri>(Intent.EXTRA_STREAM).orEmpty()
        }

    /** Agent notifications are on by default; ask once on Android 13+ so they can be shown. */
    private fun maybeAskNotificationPermission(container: AppContainer) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        val prefs = getSharedPreferences(UI_PREFS, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_ASKED_NOTIFICATIONS, false)) return
        lifecycleScope.launch {
            val enabled = runCatching { container.settingsRepository.current().notifications }.getOrDefault(true)
            if (!enabled) return@launch
            prefs.edit().putBoolean(KEY_ASKED_NOTIFICATIONS, true).apply()
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    companion object {
        const val EXTRA_SESSION_ID = "ai.opencode.android.extra.SESSION_ID"
        private const val UI_PREFS = "ui_state"
        private const val KEY_ASKED_NOTIFICATIONS = "asked_notifications"
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
