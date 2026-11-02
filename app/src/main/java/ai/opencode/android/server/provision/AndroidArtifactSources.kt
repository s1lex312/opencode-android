package ai.opencode.android.server.provision

import ai.opencode.android.settings.LocalServerSettings
import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
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
