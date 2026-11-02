package ai.opencode.android.server.provision

import ai.opencode.android.settings.LocalServerSettings
import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

/** Streams the environment bundle from an HTTPS/HTTP URL. */
class HttpArtifactSource(
    private val url: String,
    private val client: OkHttpClient,
    private val declaredSize: Long = 0,
) : ArtifactSource {

    override val label: String get() = url

    override val sizeBytes: Long? get() = declaredSize.takeIf { it > 0 }

    override fun open(): InputStream {
        val request = Request.Builder().url(url).get().build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            response.close()
            throw IOException("Cannot download the environment bundle: HTTP ${response.code}")
        }
        val body = response.body ?: run {
            response.close()
            throw IOException("Empty response body")
        }
        val stream = body.byteStream()
        return object : FilterInputStream(stream) {
            override fun close() {
                runCatching { super.close() }
                response.close()
            }
        }
    }
}

/** Reads the bundle that was packaged into the APK (offline installs, CI-built artifacts). */
class AssetArtifactSource(
    private val context: Context,
    private val assetName: String,
) : ArtifactSource {

    override val label: String get() = "APK asset $assetName"

    override val sizeBytes: Long?
        get() = runCatching { context.assets.openFd(assetName).use { it.length } }.getOrNull()

    override fun open(): InputStream = context.assets.open(assetName, android.content.res.AssetManager.ACCESS_STREAMING)
}

/**
 * Reads a bundle the user picked manually (SAF): the file is already copied into app storage, so
 * installing it needs neither network nor a published URL.
 */
class FileArtifactSource(private val file: File) : ArtifactSource {

    override val label: String get() = "local file ${file.name}"

    override val sizeBytes: Long? get() = file.length().takeIf { it > 0 }

    override fun open(): InputStream = file.inputStream()
}

/**
 * Resolves the effective manifest: the one shipped in `assets/local-env.json`, optionally
 * overridden by values the user pasted into Settings (self-hosted bundle, custom hash/version).
 */
object EnvConfig {

    const val ASSET_MANIFEST = "local-env.json"

    fun assetManifest(context: Context): EnvManifest {
        val text = runCatching {
            context.assets.open(ASSET_MANIFEST).bufferedReader().use { it.readText() }
        }.getOrNull()
        return EnvManifest.parseOrNull(text) ?: EnvManifest(version = "unset")
    }

    fun effective(assetManifest: EnvManifest, settings: LocalServerSettings): EnvManifest {
        var manifest = assetManifest
        if (settings.artifactUrl.isNotBlank()) {
            manifest = manifest.copy(url = settings.artifactUrl.trim(), asset = "")
        }
        if (settings.artifactSha256.isNotBlank()) {
            manifest = manifest.copy(sha256 = settings.artifactSha256.trim())
        }
        if (settings.artifactVersion.isNotBlank()) {
            manifest = manifest.copy(version = settings.artifactVersion.trim())
        }
        return manifest
    }

    /** Result of probing the APK for the bundled environment. */
    data class EmbeddedProbe(val present: Boolean, val sizeBytes: Long)

    /**
     * Looks for [assetName] inside this APK.
     *
     * Uncompressed assets (`noCompress`) can be opened by descriptor and report their real size;
     * compressed ones only provide a stream, so the size falls back to `available()` (or 0).
     */
    fun probeEmbeddedBundle(
        context: Context,
        assetName: String = EnvManifest.DEFAULT_ASSET,
    ): EmbeddedProbe {
        val byDescriptor = runCatching {
            context.assets.openFd(assetName).use { it.declaredLength }
        }.getOrNull()
        if (byDescriptor != null) return EmbeddedProbe(present = true, sizeBytes = byDescriptor.coerceAtLeast(0L))

        val stream = runCatching {
            context.assets.open(assetName, android.content.res.AssetManager.ACCESS_STREAMING)
        }.getOrNull() ?: return EmbeddedProbe(present = false, sizeBytes = 0L)
        stream.use { opened ->
            return EmbeddedProbe(
                present = true,
                sizeBytes = runCatching { opened.available().toLong() }.getOrDefault(0L),
            )
        }
    }

    /** Top level asset names, used only for diagnostics when a Full build looks broken. */
    fun assetNames(context: Context): List<String> =
        runCatching { context.assets.list("")?.toList().orEmpty() }.getOrDefault(emptyList())

    fun source(context: Context, manifest: EnvManifest, client: OkHttpClient): ArtifactSource? {
        if (manifest.isDownload) {
            if (!manifest.url.startsWith("https://") && !manifest.url.startsWith("http://127.0.0.1")) {
                // Bundles are large and executables: cleartext download of a binary from the public
                // internet is never acceptable. https:// or a local file server only.
                return null
            }
            return HttpArtifactSource(manifest.url, client, manifest.sizeBytes)
        }
        return AssetArtifactSource(context, manifest.asset.ifBlank { EnvManifest.DEFAULT_ASSET })
    }

    /** Dedicated client: long downloads, no global timeouts, redirects allowed. */
    fun downloadClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
}
