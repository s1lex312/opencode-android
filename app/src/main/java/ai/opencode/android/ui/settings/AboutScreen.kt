package ai.opencode.android.ui.settings

import ai.opencode.android.R
import ai.opencode.android.di.AppContainer
import ai.opencode.android.ui.components.SettingsDivider
import ai.opencode.android.ui.components.SettingsGroup
import ai.opencode.android.ui.components.SettingsItem
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

private const val GITHUB_URL = "https://github.com/s1lex312/opencode-android"
private const val OPENCODE_URL = "https://opencode.ai"
private const val DOCS_URL = "https://opencode.ai/docs"

@Composable
fun AboutScreen(container: AppContainer, onBack: () -> Unit) {
    val context = LocalContext.current
    val noBrowser = stringResource(R.string.settings_about_no_browser)
    val copied = stringResource(R.string.copied_to_clipboard)
    val open: (String) -> Unit = { url -> context.openUrl(url, noBrowser) }

    SettingsPage(title = stringResource(R.string.settings_about_title), onBack = onBack) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(80.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Terminal,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(40.dp),
                    )
                }
            }
            Text(stringResource(R.string.settings_about_app_name), style = MaterialTheme.typography.headlineSmall)
            Text(
                text = stringResource(R.string.settings_about_tagline),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }

        SettingsGroup(title = stringResource(R.string.settings_about_app_group)) {
            SettingsItem(
                title = stringResource(R.string.settings_about_version),
                subtitle = stringResource(R.string.settings_about_version_hint, container.buildLabel),
                icon = Icons.Default.Tag,
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    clipboard?.setPrimaryClip(ClipData.newPlainText("version", container.buildLabel))
                    Toast.makeText(context, copied, Toast.LENGTH_SHORT).show()
                },
            )
            SettingsDivider()
            val full = container.flavor == "full"
            SettingsItem(
                title = stringResource(if (full) R.string.settings_about_build_full else R.string.settings_about_build_lite),
                subtitle = stringResource(
                    if (full) R.string.settings_about_build_full_hint else R.string.settings_about_build_lite_hint,
                ),
                icon = Icons.Default.Inventory2,
            )
            SettingsDivider()
            SettingsItem(
                title = stringResource(R.string.settings_about_compat),
                subtitle = stringResource(R.string.settings_about_compat_hint),
                icon = Icons.Default.Hub,
            )
        }

        SettingsGroup(title = stringResource(R.string.settings_about_links)) {
            SettingsItem(
                title = stringResource(R.string.settings_about_github),
                subtitle = GITHUB_URL.removePrefix("https://"),
                icon = Icons.Default.Code,
                onClick = { open(GITHUB_URL) },
            )
            SettingsDivider()
            SettingsItem(
                title = stringResource(R.string.settings_about_opencode),
                subtitle = OPENCODE_URL.removePrefix("https://"),
                icon = Icons.Default.Public,
                onClick = { open(OPENCODE_URL) },
            )
            SettingsDivider()
            SettingsItem(
                title = stringResource(R.string.settings_about_docs),
                subtitle = DOCS_URL.removePrefix("https://"),
                icon = Icons.AutoMirrored.Filled.MenuBook,
                onClick = { open(DOCS_URL) },
            )
        }

        SettingsGroup(title = stringResource(R.string.settings_about_privacy)) {
            val persistent = container.secretStore.persistent
            SettingsItem(
                title = stringResource(if (persistent) R.string.settings_about_secrets else R.string.settings_about_secrets_memory),
                subtitle = stringResource(
                    if (persistent) R.string.settings_about_secrets_hint else R.string.settings_about_secrets_memory_hint,
                ),
                icon = Icons.Default.Lock,
            )
            SettingsDivider()
            SettingsItem(
                title = stringResource(R.string.settings_about_https),
                subtitle = stringResource(R.string.settings_about_https_hint),
                icon = Icons.Default.VerifiedUser,
            )
        }
    }
}

/** Opens [url] in whatever handles https; a toast instead of a crash when nothing does. */
internal fun Context.openUrl(url: String, noAppMessage: String) {
    try {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(this, noAppMessage, Toast.LENGTH_SHORT).show()
    }
}
