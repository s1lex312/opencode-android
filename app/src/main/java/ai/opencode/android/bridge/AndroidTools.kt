package ai.opencode.android.bridge

import ai.opencode.android.R
import ai.opencode.android.data.remote.bool
import ai.opencode.android.data.remote.int
import ai.opencode.android.data.remote.str
import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Result of one tool call: text for the model, flagged when it is an error. */
data class ToolOutcome(val text: String, val isError: Boolean = false)

/**
 * The phone capabilities exposed to the agent. Nothing here needs a runtime permission beyond
 * notifications; anything privacy-sensitive (contacts, SMS, location, camera) is deliberately absent.
 */
class AndroidTools(private val context: Context) : ToolProvider {

    private data class Tool(
        val name: String,
        val description: String,
        val schema: JsonObject,
        val run: (JsonObject) -> ToolOutcome,
    )

    private val main by lazy { Handler(Looper.getMainLooper()) }
    private var tts: TextToSpeech? = null

    private val tools: List<Tool> = listOf(
        Tool("device_info", "Phone model, Android version, locale, time zone, screen, memory and free storage.", schema()) { deviceInfo() },
        Tool("battery_status", "Battery level, charging state and temperature.", schema()) { battery() },
        Tool(
            "clipboard_read",
            "Text currently on the clipboard. Android only allows this while the opencode app is in the foreground.",
            schema(),
        ) { clipboardRead() },
        Tool("clipboard_write", "Put text on the clipboard.", schema("text" to "Text to copy", required = listOf("text"))) { args ->
            clipboardWrite(args.str("text").orEmpty())
        },
        Tool(
            "notify",
            "Show a notification on the phone, e.g. when a long task is done.",
            schema("title" to "Notification title", "text" to "Notification body", required = listOf("text")),
        ) { args -> notify(args.str("title") ?: "opencode", args.str("text").orEmpty()) },
        Tool("toast", "Show a short toast message on screen.", schema("text" to "Message", required = listOf("text"))) { args ->
            onMain { Toast.makeText(context, args.str("text").orEmpty(), Toast.LENGTH_LONG).show() }
            ToolOutcome("shown")
        },
        Tool(
            "open_url",
            "Open a URL (https:, tel:, mailto:, geo:, market:) in the matching app. Works while the app is visible.",
            schema("url" to "URL to open", required = listOf("url")),
        ) { args -> openUrl(args.str("url").orEmpty()) },
        Tool(
            "share_text",
            "Open Android's share sheet with the given text.",
            schema("text" to "Text to share", "title" to "Chooser title", required = listOf("text")),
        ) { args -> shareText(args.str("text").orEmpty(), args.str("title")) },
        Tool("list_apps", "Launchable apps: label and package name.", schema("query" to "Optional filter on label or package")) { args ->
            listApps(args.str("query"))
        },
        Tool("launch_app", "Start an app by package name (see list_apps).", schema("package" to "Package name", required = listOf("package"))) { args ->
            launchApp(args.str("package").orEmpty())
        },
        Tool("torch", "Turn the flashlight on or off.", schema("on" to "true to turn on", types = mapOf("on" to "boolean"), required = listOf("on"))) { args ->
            torch(args.bool("on") == true)
        },
        Tool(
            "vibrate",
            "Vibrate for the given duration.",
            schema("milliseconds" to "Duration, 1-5000", types = mapOf("milliseconds" to "integer")),
        ) { args -> vibrate((args.int("milliseconds") ?: 300).coerceIn(1, 5000)) },
        Tool(
            "set_volume",
            "Set a volume stream to a percentage.",
            schema(
                "stream" to "music, ring, alarm or notification",
                "percent" to "0-100",
                types = mapOf("percent" to "integer"),
                required = listOf("percent"),
            ),
        ) { args -> setVolume(args.str("stream") ?: "music", (args.int("percent") ?: 50).coerceIn(0, 100)) },
        Tool("speak", "Read text aloud with the phone's text-to-speech engine.", schema("text" to "Text to speak", required = listOf("text"))) { args ->
            speak(args.str("text").orEmpty())
        },
        Tool("storage_info", "Where the phone's shared storage is visible inside the Linux environment and how much space is free.", schema()) {
            storageInfo()
        },
    )

    val names: List<String> get() = tools.map { it.name }

    override fun definitions(): JsonArray = buildJsonArray {
        tools.forEach { tool ->
            add(
                buildJsonObject {
                    put("name", tool.name)
                    put("description", tool.description)
                    put("inputSchema", tool.schema)
                },
            )
        }
    }

    override fun call(name: String, arguments: JsonObject): ToolOutcome {
        val tool = tools.firstOrNull { it.name == name } ?: return ToolOutcome("Unknown tool: $name", isError = true)
        return try {
            tool.run(arguments)
        } catch (e: Exception) {
            ToolOutcome("${tool.name} failed: ${e.message ?: e.javaClass.simpleName}", isError = true)
        }
    }

    // ------------------------------------------------------------------ tools

    private fun deviceInfo(): ToolOutcome {
        val metrics = context.resources.displayMetrics
        val memory = ActivityManager.MemoryInfo().also {
            (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it)
        }
        val data = StatFs(Environment.getDataDirectory().path)
        return ToolOutcome(
            listOf(
                "device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})",
                "android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
                "abi: ${Build.SUPPORTED_ABIS.joinToString()}",
                "locale: ${Locale.getDefault().toLanguageTag()}",
                "timezone: ${TimeZone.getDefault().id}",
                "screen: ${metrics.widthPixels}x${metrics.heightPixels} @ ${metrics.densityDpi}dpi",
                "memory: ${memory.availMem / MB} MB free of ${memory.totalMem / MB} MB",
                "internal storage: ${data.availableBytes / MB} MB free of ${data.totalBytes / MB} MB",
            ).joinToString("\n"),
        )
    }

    private fun battery(): ToolOutcome {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return ToolOutcome("Battery state unavailable", isError = true)
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        val status = when (intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
            BatteryManager.BATTERY_STATUS_FULL -> "full"
            BatteryManager.BATTERY_STATUS_DISCHARGING, BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "discharging"
            else -> "unknown"
        }
        val temperature = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0
        return ToolOutcome("level: ${level * 100 / scale}%\nstatus: $status\ntemperature: $temperature °C")
    }

    private fun clipboardRead(): ToolOutcome {
        val text = onMain {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
        }
        return if (text == null) ToolOutcome("Clipboard is empty or not readable while the app is in the background", isError = true) else ToolOutcome(text)
    }

    private fun clipboardWrite(text: String): ToolOutcome {
        onMain {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("opencode", text))
        }
        return ToolOutcome("copied ${text.length} characters")
    }

    private fun notify(title: String, text: String): ToolOutcome {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.phone_tools_channel), NotificationManager.IMPORTANCE_DEFAULT),
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return ToolOutcome("Notification permission is not granted", isError = true)
        }
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val pending = launch?.let {
            android.app.PendingIntent.getActivity(context, 0, it, android.app.PendingIntent.FLAG_IMMUTABLE)
        }
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        manager.notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), notification)
        return ToolOutcome("notification shown")
    }

    private fun openUrl(url: String): ToolOutcome {
        val uri = Uri.parse(url)
        if (uri.scheme.isNullOrBlank()) return ToolOutcome("URL needs a scheme, e.g. https://", isError = true)
        val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return startActivity(intent, "opened $url")
    }

    private fun shareText(text: String, title: String?): ToolOutcome {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        val chooser = Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return startActivity(chooser, "share sheet opened")
    }

    private fun listApps(query: String?): ToolOutcome {
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launcher, 0)
            .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
            .distinctBy { it.second }
            .filter { (label, pkg) -> query.isNullOrBlank() || label.contains(query, true) || pkg.contains(query, true) }
            .sortedBy { it.first.lowercase() }
        if (apps.isEmpty()) return ToolOutcome("No matching apps")
        return ToolOutcome(apps.take(MAX_APPS).joinToString("\n") { (label, pkg) -> "$label — $pkg" })
    }

    private fun launchApp(packageName: String): ToolOutcome {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: return ToolOutcome("No launchable app with package $packageName", isError = true)
        return startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), "started $packageName")
    }

    private fun startActivity(intent: Intent, success: String): ToolOutcome {
        val error = onMain { runCatching { context.startActivity(intent) }.exceptionOrNull() }
        return if (error == null) ToolOutcome(success) else ToolOutcome("Could not start: ${error.message}", isError = true)
    }

    private fun torch(on: Boolean): ToolOutcome {
        val camera = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = camera.cameraIdList.firstOrNull { id ->
            camera.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return ToolOutcome("This phone has no flashlight", isError = true)
        camera.setTorchMode(id, on)
        return ToolOutcome(if (on) "torch on" else "torch off")
    }

    private fun vibrate(milliseconds: Int): ToolOutcome {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        if (!vibrator.hasVibrator()) return ToolOutcome("No vibrator", isError = true)
        vibrator.vibrate(VibrationEffect.createOneShot(milliseconds.toLong(), VibrationEffect.DEFAULT_AMPLITUDE))
        return ToolOutcome("vibrated ${milliseconds}ms")
    }

    private fun setVolume(stream: String, percent: Int): ToolOutcome {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val type = when (stream.lowercase()) {
            "ring" -> AudioManager.STREAM_RING
            "alarm" -> AudioManager.STREAM_ALARM
            "notification" -> AudioManager.STREAM_NOTIFICATION
            else -> AudioManager.STREAM_MUSIC
        }
        val max = audio.getStreamMaxVolume(type)
        return try {
            audio.setStreamVolume(type, max * percent / 100, 0)
            ToolOutcome("$stream volume set to $percent%")
        } catch (security: SecurityException) {
            ToolOutcome("Android refused (Do Not Disturb is on)", isError = true)
        }
    }

    private fun speak(text: String): ToolOutcome {
        val ready = CountDownLatch(1)
        val status = AtomicReference(TextToSpeech.ERROR)
        onMain {
            val existing = tts
            if (existing != null) {
                status.set(TextToSpeech.SUCCESS)
                ready.countDown()
            } else {
                tts = TextToSpeech(context) { result ->
                    status.set(result)
                    ready.countDown()
                }
            }
        }
        if (!ready.await(TTS_INIT_SECONDS, TimeUnit.SECONDS) || status.get() != TextToSpeech.SUCCESS) {
            return ToolOutcome("Text-to-speech is not available", isError = true)
        }
        onMain { tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "opencode-${System.nanoTime()}") }
        return ToolOutcome("speaking ${text.length} characters")
    }

    private fun storageInfo(): ToolOutcome {
        val granted = StorageAccess.granted(context)
        val external = Environment.getExternalStorageDirectory()
        val stat = runCatching { StatFs(external.path) }.getOrNull()
        return ToolOutcome(
            listOfNotNull(
                if (granted) "shared storage: /sdcard (also /storage/emulated/0)" else "shared storage: not mounted (grant 'All files access' in opencode → Settings → Phone access)",
                stat?.let { "free: ${it.availableBytes / MB} MB of ${it.totalBytes / MB} MB" },
                "home: /root (private to the app)",
            ).joinToString("\n"),
        )
    }

    // ------------------------------------------------------------------ helpers

    /** Runs [block] on the main thread and waits for its result (UI services require it). */
    private fun <T> onMain(block: () -> T): T? {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val result = AtomicReference<T?>(null)
        val done = CountDownLatch(1)
        main.post {
            try {
                result.set(block())
            } finally {
                done.countDown()
            }
        }
        done.await(MAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        return result.get()
    }

    private fun schema(
        vararg properties: Pair<String, String>,
        types: Map<String, String> = emptyMap(),
        required: List<String> = emptyList(),
    ): JsonObject = buildJsonObject {
        put("type", "object")
        put(
            "properties",
            buildJsonObject {
                properties.forEach { (name, description) ->
                    put(
                        name,
                        buildJsonObject {
                            put("type", types[name] ?: "string")
                            put("description", description)
                        },
                    )
                }
            },
        )
        if (required.isNotEmpty()) put("required", buildJsonArray { required.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
    }

    private companion object {
        const val CHANNEL = "opencode_agent_tools"
        const val MB = 1024L * 1024L
        const val MAX_APPS = 300
        const val MAIN_TIMEOUT_SECONDS = 5L
        const val TTS_INIT_SECONDS = 5L
    }
}
