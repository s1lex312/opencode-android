package ai.opencode.android.data.diagnostics

import ai.opencode.android.BuildConfig
import ai.opencode.android.R
import ai.opencode.android.bridge.StorageAccess
import ai.opencode.android.data.model.ConnectionState
import ai.opencode.android.di.AppContainer
import ai.opencode.android.server.LocalServerState
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.StatFs
import androidx.annotation.StringRes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

enum class DiagStatus { OK, WARN, ERROR, INFO }

enum class DiagSection(@StringRes val title: Int) {
    APP(R.string.diag_section_app),
    CONNECTION(R.string.diag_section_connection),
    LOCAL(R.string.diag_section_local),
    NETWORK(R.string.diag_section_network),
    DATA(R.string.diag_section_data),
    PERMISSIONS(R.string.diag_section_permissions),
}

/** One line of the diagnostics screen; [label] is English for the report file. */
data class DiagItem(
    val section: DiagSection,
    @StringRes val title: Int,
    val label: String,
    val value: String,
    val status: DiagStatus = DiagStatus.INFO,
)

/**
 * Everything worth knowing when something does not work: versions, signing key, connection, the
 * in-app server and its port, proxy, storage, backups, permissions. Also writes a zip for bug
 * reports with logs and configuration, keys and passwords removed.
 */
class Diagnostics(private val container: AppContainer) {

    private val context: Context get() = container.context

    suspend fun collect(): List<DiagItem> = withContext(Dispatchers.IO) {
        val items = mutableListOf<DiagItem>()
        fun add(section: DiagSection, title: Int, label: String, value: String, status: DiagStatus = DiagStatus.INFO) {
            items += DiagItem(section, title, label, value, status)
        }
        val settings = container.settingsRepository.current()

        // ---------------------------------------------------------------- app
        add(DiagSection.APP, R.string.diag_version, "Version", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}), ${BuildConfig.FLAVOR}")
        val cert = signingCertificate()
        add(
            DiagSection.APP, R.string.diag_signature, "Signing certificate", cert ?: "?",
            if (cert == RELEASE_CERT) DiagStatus.OK else DiagStatus.WARN,
        )
        add(DiagSection.APP, R.string.diag_device, "Device", "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.SUPPORTED_ABIS.firstOrNull()}")
        val update = container.appUpdater.state.value
        add(
            DiagSection.APP, R.string.diag_app_update, "App update", "${update.phase}${update.latest?.let { " → $it" } ?: ""}${update.message?.let { ": $it" } ?: ""}",
            if (update.phase.name == "FAILED" || update.phase.name == "SIGNATURE_MISMATCH") DiagStatus.WARN else DiagStatus.INFO,
        )

        // ---------------------------------------------------------------- connection
        add(DiagSection.CONNECTION, R.string.diag_mode, "Mode", settings.connectionMode.name)
        add(DiagSection.CONNECTION, R.string.diag_server_url, "Server URL", settings.effectiveServerUrl)
        when (val connection = container.connectionManager.state.value) {
            is ConnectionState.Connected -> add(
                DiagSection.CONNECTION, R.string.diag_connection, "Connection",
                "connected, opencode ${connection.info.version ?: "?"} (${connection.info.flavor})", DiagStatus.OK,
            )
            is ConnectionState.Failed -> add(DiagSection.CONNECTION, R.string.diag_connection, "Connection", connection.message, DiagStatus.ERROR)
            is ConnectionState.Reconnecting -> add(DiagSection.CONNECTION, R.string.diag_connection, "Connection", "reconnecting (${connection.attempt}): ${connection.reason}", DiagStatus.WARN)
            else -> add(DiagSection.CONNECTION, R.string.diag_connection, "Connection", connection.javaClass.simpleName, DiagStatus.WARN)
        }

        // ---------------------------------------------------------------- local server
        if (settings.isLocalMode) {
            val server = container.localServerManager.state.value
            add(
                DiagSection.LOCAL, R.string.diag_server_phase, "Server",
                "${server.phase}, pid ${server.pid ?: "-"}, port ${server.port}, restarts ${server.restarts}${server.message?.let { ": $it" } ?: ""}",
                when (server.phase) {
                    LocalServerState.Phase.READY -> DiagStatus.OK
                    LocalServerState.Phase.FAILED -> DiagStatus.ERROR
                    else -> DiagStatus.WARN
                },
            )
            val port = settings.localServer.port
            val open = portOpen(port)
            add(
                DiagSection.LOCAL, R.string.diag_port, "Port $port", if (open) "accepting connections" else "closed",
                if (open == (server.phase == LocalServerState.Phase.READY)) DiagStatus.OK else DiagStatus.WARN,
            )
            val paths = container.envPaths
            add(DiagSection.LOCAL, R.string.diag_env, "Environment", container.provisioner.installedVersion() ?: "not installed", if (container.provisioner.installedVersion() != null) DiagStatus.OK else DiagStatus.ERROR)
            add(DiagSection.LOCAL, R.string.diag_proot, "proot", paths.prootBinary?.absolutePath ?: "missing", if (paths.prootBinary != null) DiagStatus.OK else DiagStatus.ERROR)
            add(DiagSection.LOCAL, R.string.diag_rootfs, "rootfs", if (paths.rootfsDir.isDirectory) paths.rootfsDir.absolutePath else "missing", if (paths.rootfsDir.isDirectory) DiagStatus.OK else DiagStatus.ERROR)
            add(DiagSection.LOCAL, R.string.diag_log, "Server log", "${paths.logFile.length() / 1024} KB")
        }

        // ---------------------------------------------------------------- network
        val proxy = container.proxyController.state.value
        add(
            DiagSection.NETWORK, R.string.diag_proxy, "Proxy",
            when {
                !settings.proxy.enabled -> "off"
                !settings.proxy.configured -> "enabled, no host"
                else -> "${settings.proxy.type} ${settings.proxy.host}:${settings.proxy.port}, ${settings.proxy.scope}, bridge ${proxy.bridgePort}, server ${proxy.serverPort ?: "-"}"
            },
            when {
                !settings.proxy.enabled -> DiagStatus.INFO
                proxy.bridgePort == 0 -> DiagStatus.ERROR
                settings.isLocalMode && settings.proxy.applyToServer && proxy.serverPort != proxy.bridgePort -> DiagStatus.WARN
                else -> DiagStatus.OK
            },
        )

        // ---------------------------------------------------------------- data
        val free = runCatching { StatFs(context.noBackupFilesDir.absolutePath).availableBytes }.getOrDefault(0L)
        add(DiagSection.DATA, R.string.diag_storage, "Free space", "${free / (1024 * 1024)} MB", if (free < 500L * 1024 * 1024) DiagStatus.WARN else DiagStatus.OK)
        val backup = container.configBackup.state.value
        add(
            DiagSection.DATA, R.string.diag_backup, "Settings backup",
            if (backup.lastAt > 0) "${STAMP.format(Date(backup.lastAt))} → ${backup.location ?: "?"}${backup.error?.let { " ($it)" } ?: ""}" else (backup.error ?: "never"),
            when {
                backup.error != null -> DiagStatus.ERROR
                backup.lastAt == 0L -> DiagStatus.WARN
                else -> DiagStatus.OK
            },
        )
        add(DiagSection.DATA, R.string.diag_plugins, "Plugins", container.plugins.state.value.installed.joinToString { it.manifest.id }.ifBlank { "none" })
        add(DiagSection.DATA, R.string.diag_profiles, "Settings profiles", container.settingsProfiles.state.value.profiles.size.toString())

        // ---------------------------------------------------------------- permissions
        val notifications = Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        add(DiagSection.PERMISSIONS, R.string.diag_perm_notifications, "Notifications", yes(notifications), if (notifications) DiagStatus.OK else DiagStatus.WARN)
        val battery = container.isIgnoringBatteryOptimizations()
        add(DiagSection.PERMISSIONS, R.string.diag_perm_battery, "Unrestricted battery", yes(battery), if (battery) DiagStatus.OK else DiagStatus.WARN)
        val install = context.packageManager.canRequestPackageInstalls()
        add(DiagSection.PERMISSIONS, R.string.diag_perm_install, "Install unknown apps", yes(install), if (install) DiagStatus.OK else DiagStatus.INFO)
        add(DiagSection.PERMISSIONS, R.string.diag_perm_storage, "All files access", yes(StorageAccess.granted(context)))
        items
    }

    fun report(items: List<DiagItem>): String = buildString {
        appendLine("opencode-android diagnostics, ${STAMP.format(Date())}")
        DiagSection.entries.forEach { section ->
            val rows = items.filter { it.section == section }
            if (rows.isEmpty()) return@forEach
            appendLine()
            appendLine("[${section.name.lowercase()}]")
            rows.forEach { appendLine("${it.status.name.padEnd(5)} ${it.label}: ${it.value}") }
        }
    }

    /** Zip for a bug report: report, server and app logs, settings, opencode config; secrets removed. */
    suspend fun writeBundle(output: OutputStream, items: List<DiagItem>) = withContext(Dispatchers.IO) {
        ZipOutputStream(output).use { zip ->
            fun entry(name: String, text: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
            entry("diagnostics.txt", report(items))
            entry("server.log", Redact.text(container.localServerManager.logLines(5_000).joinToString("\n")))
            entry("app.log", Redact.text(appLog()))
            val settings = container.settingsRepository.exportRaw().mapValues { (_, value) -> value.toString() }
            entry("settings.json", Redact.json(JsonObject(settings.mapValues { JsonPrimitive(it.value) })))
            entry("workbench.json", Redact.text(container.workbench.exportJson()))
            entry("plugins.json", container.plugins.state.value.installed.joinToString("\n") { "${it.manifest.id} ${it.manifest.pluginVersion} ${it.source} ${it.sha256}" })
            val configDir = File(container.envPaths.rootfsDir, "root/.config/opencode")
            configDir.listFiles()?.filter { it.isFile && it.length() < 1_000_000 && (it.name.endsWith(".json") || it.name.endsWith(".jsonc")) }?.forEach { file ->
                val text = file.readText()
                val redacted = runCatching { Redact.json(Json.parseToJsonElement(text)) }.getOrElse { Redact.text(text) }
                entry("opencode-config/${file.name}", redacted)
            }
        }
    }

    /** This process's logcat (an app may always read its own). */
    fun appLog(): String = runCatching {
        val process = ProcessBuilder("logcat", "-d", "-t", "3000", "--pid=${android.os.Process.myPid()}")
            .redirectErrorStream(true)
            .start()
        process.inputStream.bufferedReader().use { it.readText() }
    }.getOrElse { "logcat unavailable: ${it.message}" }

    private fun portOpen(port: Int): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 400) }
        true
    }.getOrDefault(false)

    private fun yes(value: Boolean) = if (value) "yes" else "no"

    @Suppress("DEPRECATION")
    private fun signingCertificate(): String? = runCatching {
        val pm = context.packageManager
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo?.apkContentsSigners
        } else {
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures
        }
        val bytes = signatures?.firstOrNull()?.toByteArray() ?: return@runCatching null
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }.getOrNull()

    companion object {
        /** Certificate of every published APK since 1.6 (`app/debug.keystore`). */
        const val RELEASE_CERT = "d094dce9ca499f2c426971d4c086f64ebfd86893ca1234ce70cc8d0e89bdd0eb"
        private val STAMP = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    }
}

/** Removes keys, tokens and passwords from logs and JSON before they leave the device. */
object Redact {

    private val SECRET_KEY = Regex("(?i)(api[_-]?key|apikey|token|secret|password|passwd|authorization|cookie|bearer|private[_-]?key|access[_-]?key|credential)")

    private val SECRET_VALUE = Regex(
        "(sk-[A-Za-z0-9_-]{8,}|sk-ant-[A-Za-z0-9_-]{8,}|gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|" +
            "xox[abprs]-[A-Za-z0-9-]{10,}|AIza[0-9A-Za-z_-]{20,}|eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{5,}|" +
            "(?i:bearer|basic)\\s+[A-Za-z0-9._~+/=-]{8,})",
    )

    private val ASSIGNMENT = Regex("(?i)((?:api[_-]?key|token|secret|password|passwd)[\"']?\\s*[:=]\\s*[\"']?)[^\"'\\s,}]+")

    private val URL_CREDENTIALS = Regex("(://)[^/@\\s:]+:[^/@\\s]+@")

    fun text(value: String): String = value
        .replace(SECRET_VALUE, "***")
        .replace(ASSIGNMENT) { it.groupValues[1] + "***" }
        .replace(URL_CREDENTIALS) { it.groupValues[1] + "***@" }

    fun json(element: JsonElement): String = Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), clean(element))

    private fun clean(element: JsonElement, key: String? = null): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.mapValues { (name, value) -> clean(value, name) })
        is JsonArray -> JsonArray(element.map { clean(it, key) })
        is JsonPrimitive -> when {
            key != null && SECRET_KEY.containsMatchIn(key) && element.isString && element.content.isNotEmpty() -> JsonPrimitive("***")
            element.isString -> JsonPrimitive(text(element.content))
            else -> element
        }
        else -> element
    }
}
