package ai.opencode.android.server.provision

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.DigestInputStream
import java.security.MessageDigest

/**
 * Downloads (or copies from APK assets), verifies and unpacks the proot + rootfs + opencode
 * bundle into the app's private storage.
 *
 * Everything here is root-free: files land in `noBackupFilesDir/opencode-env`, which is private to
 * the app, and proot runs as the app's own UID.
 *
 * The class deliberately has no Android imports so the whole flow (hash verification, archive
 * layout, failure handling, cleanup) is covered by plain JVM unit tests.
 */
class EnvProvisioner(
    private val paths: EnvPaths,
    private val bundledManifest: () -> EnvManifest?,
    private val sourceFactory: (EnvManifest) -> ArtifactSource?,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    private val _state = MutableStateFlow(ProvisionState())
    val state: StateFlow<ProvisionState> = _state.asStateFlow()

    init {
        refresh()
    }

    /** Re-reads the marker file and validates that the files it promises still exist. */
    fun refresh() {
        val installed = installedManifest()
        _state.value = if (installed != null) {
            ProvisionState(
                phase = ProvisionState.Phase.READY,
                progress = 1f,
                message = "Environment ${installed.version} installed",
                version = installed.version,
            )
        } else {
            ProvisionState(phase = ProvisionState.Phase.NOT_INSTALLED)
        }
    }

    fun paths(): EnvPaths = paths

    fun bundled(): EnvManifest? = bundledManifest()

    /** Manifest of the environment that is currently installed, or null. */
    fun installedManifest(): EnvManifest? {
        val marker = paths.manifestFile
        if (!marker.isFile) return null
        val parsed = runCatching { EnvManifest.parse(marker.readText()) }.getOrNull() ?: return null
        val usable = paths.prootFile.isFile &&
            paths.rootfsDir.isDirectory &&
            File(paths.rootfsDir, "bin/sh").isFile
        return if (usable) parsed else null
    }

    fun isInstalled(): Boolean = installedManifest() != null

    fun installedVersion(): String? = installedManifest()?.version

    fun hasFreeSpace(bytes: Long): Boolean {
        val usable = runCatching { paths.root.parentFile?.usableSpace ?: 0L }.getOrDefault(0L)
        return usable <= 0L || usable > bytes
    }

    /**
     * Installs (or reinstalls) the environment described by [manifest].
     *
     * On failure the previous installation is left untouched: extraction happens in a staging
     * directory that is swapped in only after every step succeeded.
     */
    suspend fun provision(
        manifest: EnvManifest,
        sourceOverride: ArtifactSource? = null,
    ): ProvisionResult = withContext(Dispatchers.IO) {
        if (_state.value.busy) return@withContext ProvisionResult.Err("Installation already running")
        paths.prepare()

        val expectedHash = manifest.requireHash()
        if (manifest.isDownload && expectedHash == null) {
            return@withContext fail("No SHA-256 published for this bundle; refusing to install it.")
        }

        val source = sourceOverride ?: runCatching { sourceFactory(manifest) }.getOrNull()
            ?: return@withContext fail(
                if (manifest.isDownload) {
                    "Cannot download the environment bundle from ${manifest.url}"
                } else {
                    "The APK does not ship ${manifest.asset}. Publish a bundle and set its URL in Settings."
                },
            )

        // Bytes that come straight out of the APK are covered by the APK signature, and aapt2
        // rewrites the gzip wrapper while packaging (`local-env.tar.gz` becomes `local-env.tar`),
        // so the SHA-256 computed at build time can never match the packaged bytes. Comparing it
        // would reject a perfectly good Full build, so the hash check applies to downloads and
        // user-picked files only.
        val fromApkAsset = sourceOverride == null && !manifest.isDownload

        if (manifest.sizeBytes > 0 && !hasFreeSpace(manifest.sizeBytes * 3)) {
            return@withContext fail("Not enough free space. The environment needs about ${manifest.sizeBytes * 3 / 1_000_000} MB.")
        }

        try {
            _state.value = ProvisionState(
                phase = ProvisionState.Phase.DOWNLOADING,
                message = if (manifest.isDownload) {
                    "Downloading ${manifest.version}"
                } else {
                    "Unpacking ${source.label} (${manifest.version})"
                },
                bytesTotal = source.sizeBytes ?: manifest.sizeBytes,
                version = manifest.version,
            )

            val archive = File(paths.downloadDir, "bundle.tar.gz")
            paths.downloadDir.mkdirs()
            archive.delete()
            val digest = MessageDigest.getInstance("SHA-256")
            var copied = 0L
            source.open().use { input ->
                DigestInputStream(input.buffered(64 * 1024), digest).use { stream ->
                    archive.outputStream().buffered(64 * 1024).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = stream.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            copied += read
                            _state.value = _state.value.copy(
                                phase = ProvisionState.Phase.DOWNLOADING,
                                bytesDone = copied,
                                progress = progressOf(copied, source.sizeBytes ?: manifest.sizeBytes),
                            )
                        }
                    }
                }
            }

            _state.value = _state.value.copy(
                phase = ProvisionState.Phase.VERIFYING,
                bytesDone = copied,
                progress = 1f,
                message = "Verifying checksum",
            )
            val actualHash = Digests.toHex(digest.digest())
            if (fromApkAsset) {
                // Repackaged by aapt2: keep the digest for diagnostics, never fail on it.
                _state.value = _state.value.copy(
                    message = "Embedded bundle from the APK (sha256 ${actualHash.take(12)}…, trusted via APK signature)",
                )
            }
            if (!fromApkAsset && expectedHash != null && !Digests.matches(expectedHash, actualHash)) {
                archive.delete()
                return@withContext fail(
                    "Checksum mismatch: expected ${expectedHash.take(12)}…, got ${actualHash.take(12)}…. " +
                        "The download was corrupted or the manifest is out of date.",
                )
            }

            _state.value = _state.value.copy(
                phase = ProvisionState.Phase.EXTRACTING,
                progress = 0f,
                message = "Unpacking root filesystem",
            )
            val staging = paths.stagingDir
            staging.deleteRecursively()
            staging.mkdirs()
            var lastProgress = 0L
            val total = copied.coerceAtLeast(1)
            TarGzExtractor.extract(archive.inputStream().buffered(64 * 1024), staging) { written ->
                // Extraction progress is a coarse estimate: the archive is mostly already-compressed
                // content, so bytes written map roughly onto archive size.
                if (written - lastProgress > 8L * 1024 * 1024) {
                    lastProgress = written
                    _state.value = _state.value.copy(
                        phase = ProvisionState.Phase.EXTRACTING,
                        progress = ((written.toFloat() / total.toFloat()).coerceIn(0f, 0.99f)),
                    )
                }
            }
            archive.delete()

            _state.value = _state.value.copy(
                phase = ProvisionState.Phase.INSTALLING,
                progress = 0.99f,
                message = "Finishing installation",
            )
            installFromStaging(staging, manifest)
            staging.deleteRecursively()

            val installed = installedManifest()
            if (installed == null) {
                return@withContext fail("Installation finished but the environment is incomplete (rootfs/bin/sh missing).")
            }

            _state.value = ProvisionState(
                phase = ProvisionState.Phase.READY,
                progress = 1f,
                bytesDone = copied,
                bytesTotal = copied,
                message = "Environment ${manifest.version} installed",
                version = installed.version,
            )
            ProvisionResult.Ok(installed.version)
        } catch (t: Throwable) {
            runCatching { paths.stagingDir.deleteRecursively() }
            runCatching { File(paths.downloadDir, "bundle.tar.gz").delete() }
            fail(friendlyMessage(t))
        }
    }

    suspend fun clear(): Boolean = withContext(Dispatchers.IO) {
        val removed = runCatching {
            paths.rootfsDir.deleteRecursively()
            paths.prootFile.parentFile?.deleteRecursively()
            paths.stagingDir.deleteRecursively()
            paths.downloadDir.deleteRecursively()
            paths.manifestFile.delete()
            paths.logFile.delete()
            true
        }.getOrDefault(false)
        _state.value = ProvisionState(
            phase = if (removed) ProvisionState.Phase.NOT_INSTALLED else ProvisionState.Phase.FAILED,
            error = if (removed) null else "Could not delete every file of the environment",
        )
        removed
    }

    private fun installFromStaging(staging: File, manifest: EnvManifest) {
        val stagedRootfs = File(staging, "rootfs")
        if (!stagedRootfs.isDirectory) throw IOException("Archive has no rootfs/ directory")
        val stagedProot = File(staging, "proot")
        if (!stagedProot.isFile) throw IOException("Archive has no proot binary")
        if (!File(stagedRootfs, "bin/sh").isFile) throw IOException("Root filesystem has no /bin/sh")

        paths.rootfsDir.deleteRecursively()
        paths.prootFile.parentFile?.deleteRecursively()
        paths.prootFile.parentFile?.mkdirs()
        if (!stagedProot.renameTo(paths.prootFile)) {
            stagedProot.copyTo(paths.prootFile, overwrite = true)
            stagedProot.delete()
        }
        if (!stagedRootfs.renameTo(paths.rootfsDir)) {
            copyDirectory(stagedRootfs, paths.rootfsDir)
        }
        paths.prootFile.setExecutable(true, false)
        paths.prootFile.setReadable(true, false)
        // Bundles may ship a dynamically linked proot (e.g. the Termux build); its shared
        // libraries are kept next to the binary and picked up through LD_LIBRARY_PATH.
        val stagedLibs = File(staging, "proot-libs")
        if (stagedLibs.isDirectory) {
            val libs = File(paths.prootFile.parentFile, "lib")
            libs.deleteRecursively()
            copyDirectory(stagedLibs, libs)
            libs.listFiles()?.forEach { it.setReadable(true, false) }
        }
        paths.manifestFile.writeText(EnvManifest.encode(manifest))
    }

    private fun copyDirectory(from: File, to: File) {
        to.mkdirs()
        from.listFiles()?.forEach { child ->
            val destination = File(to, child.name)
            if (child.isDirectory) {
                copyDirectory(child, destination)
            } else {
                runCatching { child.copyTo(destination, overwrite = true) }
            }
        }
    }

    private fun friendlyMessage(t: Throwable): String = when (t) {
        is java.io.FileNotFoundException ->
            "The environment bundle was not found (${t.message}). " +
                "Ship it as app/src/main/assets/local-env.tar.gz or publish it and set the URL on the Setup screen."

        is IOException -> t.message ?: "I/O error while installing the environment"
        else -> t.message ?: t.javaClass.simpleName
    }

    private fun progressOf(done: Long, total: Long): Float =
        if (total <= 0) 0f else (done.toFloat() / total.toFloat()).coerceIn(0f, 1f)

    private fun fail(message: String): ProvisionResult.Err {
        _state.value = _state.value.copy(
            phase = ProvisionState.Phase.FAILED,
            error = message,
            progress = 0f,
        )
        return ProvisionResult.Err(message)
    }
}
