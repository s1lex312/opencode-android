package ai.opencode.android.server.provision

/**
 * How Local mode can obtain its environment (proot + rootfs + opencode) in *this* APK.
 *
 * The distinction matters because the APK flavour is a build-time fact, while "is the asset
 * readable at runtime" is a runtime fact. A `full` build that cannot see its asset is a **broken
 * artifact** and must be reported as such — never silently degraded to the Lite experience.
 */
enum class EmbeddedBundleStatus {
    /** The bundle ships inside this APK and is readable: installation works offline. */
    EMBEDDED,

    /** The APK was built as Full but the asset is missing/unreadable — broken artifact. */
    BROKEN_FULL,

    /** Lite build: there is nothing inside, the user must provide a URL or a file. */
    LITE,
}

/** Inputs of the decision; kept as plain data so the rule is unit-testable without Android. */
data class EmbeddedBundleInputs(
    val flavor: String,
    /** `BuildConfig.EMBEDDED_BUNDLE` — true only for the `full` flavour. */
    val declaredEmbedded: Boolean,
    /** The `local-env.tar.gz` asset could be opened from this APK. */
    val assetPresent: Boolean,
    /** Size of the asset when known, `0` otherwise. */
    val assetSizeBytes: Long = 0,
)

data class EmbeddedBundleState(
    val status: EmbeddedBundleStatus,
    val flavor: String,
    val assetSizeBytes: Long = 0,
) {
    val isEmbedded: Boolean get() = status == EmbeddedBundleStatus.EMBEDDED
    val isBrokenFull: Boolean get() = status == EmbeddedBundleStatus.BROKEN_FULL
    val isLite: Boolean get() = status == EmbeddedBundleStatus.LITE

    /** Short label for the Setup screen. */
    val label: String
        get() = when (status) {
            EmbeddedBundleStatus.EMBEDDED -> "embedded in this APK (offline)"
            EmbeddedBundleStatus.BROKEN_FULL -> "MISSING — this Full build was packaged without local-env.tar.gz"
            EmbeddedBundleStatus.LITE -> "not embedded — Lite build, download or import required"
        }
}

object EmbeddedBundle {

    fun evaluate(inputs: EmbeddedBundleInputs): EmbeddedBundleState {
        val usable = inputs.assetPresent && inputs.assetSizeBytes >= 0
        val status = when {
            inputs.declaredEmbedded && usable -> EmbeddedBundleStatus.EMBEDDED
            // Declared Full but nothing to read: the artifact is broken, say so loudly.
            inputs.declaredEmbedded -> EmbeddedBundleStatus.BROKEN_FULL
            // A bundle that happens to be present in a non-Full build (local experiments) still
            // works, so accept it.
            usable -> EmbeddedBundleStatus.EMBEDDED
            else -> EmbeddedBundleStatus.LITE
        }
        return EmbeddedBundleState(
            status = status,
            flavor = inputs.flavor,
            assetSizeBytes = if (usable) inputs.assetSizeBytes else 0L,
        )
    }
}
