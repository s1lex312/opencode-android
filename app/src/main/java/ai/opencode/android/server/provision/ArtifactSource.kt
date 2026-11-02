package ai.opencode.android.server.provision

import java.io.InputStream

/**
 * Where an environment bundle comes from. Kept as an interface so the provisioner can be unit
 * tested without Android (asset) or network (HTTP) dependencies.
 */
interface ArtifactSource {
    /** Human readable origin, shown in the setup UI and logs (never contains secrets). */
    val label: String

    /** Total size in bytes when known up-front, used for the progress bar. */
    val sizeBytes: Long?

    fun open(): InputStream
}
