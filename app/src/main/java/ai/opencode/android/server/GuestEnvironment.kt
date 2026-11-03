package ai.opencode.android.server

/**
 * What the Linux guest must not inherit from the Android app process.
 *
 * proot passes its own environment to the guest. Android sets `ANDROID_DATA=/data` and
 * `ANDROID_ROOT=/system` for every app process, and Python's platformdirs (vendored by pip) takes
 * exactly that pair as "running on Android", then fails with
 * `OSError: Cannot find path to android app folder`. The bionic library path and proot's own
 * variables mean nothing to glibc programs either.
 */
object GuestEnvironment {

    private val HOST_ONLY = setOf(
        "BOOTCLASSPATH",
        "DEX2OATBOOTCLASSPATH",
        "SYSTEMSERVERCLASSPATH",
        "STANDALONE_SYSTEMSERVER_JARS",
        "EXTERNAL_STORAGE",
        "SECONDARY_STORAGE",
        "ASEC_MOUNTPOINT",
        "LD_LIBRARY_PATH",
        "LD_PRELOAD",
        "TMPDIR",
    )

    fun isHostOnly(name: String): Boolean =
        name in HOST_ONLY || name.startsWith("ANDROID_") || name.startsWith("PROOT_")

    /**
     * `-u NAME` pairs for `/usr/bin/env` inside the guest. [hostKeys] is the environment proot is
     * started with; the variables proot itself needs stay on the host side.
     */
    fun unsetArgs(hostKeys: Collection<String>): List<String> =
        (hostKeys.filter(::isHostOnly) + ALWAYS).distinct().sorted().flatMap { listOf("-u", it) }

    /** Unset even when absent from the app's environment, so the result does not depend on the device. */
    private val ALWAYS = listOf("ANDROID_DATA", "ANDROID_ROOT")

    /** Same clean-up as a shell line, for scripts that run inside a server started before 1.7.1. */
    const val SHELL_UNSET = "unset ANDROID_DATA ANDROID_ROOT ANDROID_ART_ROOT ANDROID_I18N_ROOT ANDROID_TZDATA_ROOT " +
        "ANDROID_STORAGE ANDROID_ASSETS BOOTCLASSPATH DEX2OATBOOTCLASSPATH SYSTEMSERVERCLASSPATH LD_PRELOAD 2>/dev/null || true"
}
