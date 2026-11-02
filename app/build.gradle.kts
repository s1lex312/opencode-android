import java.security.MessageDigest
import java.util.Collections
import java.util.Properties
import java.util.zip.ZipFile

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "ai.opencode.android"
    compileSdk = 34

    defaultConfig {
        applicationId = "ai.opencode.android"
        minSdk = 26
        targetSdk = 34
        versionCode = 6
        versionName = "1.1.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    /**
     * Two distribution variants share the same applicationId, so installing Full over Lite (and
     * vice versa) is a normal update: settings, the server password and an already unpacked
     * environment are preserved.
     *
     * - `full` ships the Local-mode bundle (`src/full/assets/local-env.tar.gz`, ~105 MB) and works
     *   completely offline;
     * - `lite` is the thin client (~19 MB): the bundle is downloaded or imported on first run.
     */
    flavorDimensions += "packaging"

    productFlavors {
        create("lite") {
            dimension = "packaging"
            versionNameSuffix = "-lite"
            // Build-time truth: this APK has no environment inside. The app must ask for a source.
            buildConfigField("boolean", "EMBEDDED_BUNDLE", "false")
        }
        create("full") {
            dimension = "packaging"
            versionNameSuffix = "-full"
            // Build-time truth: src/full/assets/local-env.tar.gz is packaged into this APK.
            // A Full build whose asset is missing is a broken artifact, not a Lite build.
            buildConfigField("boolean", "EMBEDDED_BUNDLE", "true")
        }
    }

    signingConfigs {
        // Release signing is intentionally read from an untracked keystore.properties file.
        val keystorePropertiesFile = rootProject.file("keystore.properties")
        if (keystorePropertiesFile.exists()) {
            val props = Properties().apply { keystorePropertiesFile.inputStream().use { load(it) } }
            create("release") {
                storeFile = rootProject.file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (signingConfigs.findByName("release") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-opt-in=kotlin.RequiresOptIn")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        // The environment bundle is already gzip-compressed; storing it as-is keeps the Full APK
        // smaller (and aapt2 otherwise tries to deflate it again, costing minutes per build).
        noCompress += "tar.gz"
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/LICENSE*",
                "/META-INF/NOTICE*",
                "META-INF/versions/9/previous-compilation-data.bin",
            )
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

// ---------------------------------------------------------------------------------------------
// Full flavor: the environment bundle
// ---------------------------------------------------------------------------------------------

/** Where the Full flavor expects the bundle (created by `:app:prepareFullBundle`). */
val fullBundleDir = layout.projectDirectory.dir("src/full/assets")
val fullBundleFile = fullBundleDir.file("local-env.tar.gz").asFile
val fullBundleManifest = fullBundleDir.file("local-env.json").asFile
val MIN_BUNDLE_BYTES = 1_000_000L

fun fullBundleProblem(): String? = when {
    !fullBundleFile.isFile -> "no file at ${fullBundleFile.path}"
    fullBundleFile.length() < MIN_BUNDLE_BYTES ->
        "the file at ${fullBundleFile.path} is only ${fullBundleFile.length()} bytes (the real bundle is ~105 MB)"
    else -> null
}

/**
 * Fails the build with an actionable message instead of silently producing a 19 MB "Full" APK.
 */
fun requireFullBundle(taskName: String) {
    val problem = fullBundleProblem() ?: return
    throw GradleException(
        """
        |The `full` flavor must embed the Local-mode environment bundle, but $problem.
        |
        |Fix it with one of:
        |  * ./gradlew :app:prepareFullBundle   — copies the newest dist/opencode-env-*-arm64.tar.gz
        |  * cp server/dist/opencode-env-<ver>-arm64.tar.gz app/src/full/assets/local-env.tar.gz
        |
        |Use `:app:assembleLiteDebug` / `:app:assembleLiteRelease` for the thin client, which never
        |needs the bundle. (task: $taskName)
        """.trimMargin(),
    )
}

/** Guards every task that packages Full assets (mergeFull*Assets → APK/resource processing). */
tasks.matching { it.name.matches(Regex("mergeFull(Debug|Release)Assets")) }.configureEach {
    doFirst { requireFullBundle(name) }
}

/**
 * Post-package guard: open the produced Full APK as a zip and prove the environment is really
 * inside it. A "Full" APK without `assets/local-env.tar.gz` (~105 MB) is a broken artifact, so the
 * build fails here instead of shipping it and letting the app fall back to the Lite experience.
 */
tasks.matching { it.name.matches(Regex("assembleFull(Debug|Release)")) }.configureEach {
    doLast {
        val buildType = name.removePrefix("assembleFull").lowercase()
        val apkDir = layout.buildDirectory.dir("outputs/apk/full/$buildType").get().asFile
        val apk = apkDir.listFiles().orEmpty().firstOrNull { it.extension == "apk" }
            ?: throw GradleException("Full build produced no APK in ${apkDir.path}")

        ZipFile(apk).use { zip ->
            val assets = Collections.list(zip.entries())
                .map { it.name }
                .filter { it.startsWith("assets/") }
            val bundle = zip.getEntry("assets/local-env.tar.gz")
            if (bundle == null) {
                throw GradleException(
                    "Full APK ${apk.name} does not contain assets/local-env.tar.gz. " +
                        "Assets inside: $assets",
                )
            }
            if (bundle.size < 100_000_000L) {
                throw GradleException(
                    "Full APK ${apk.name} contains assets/local-env.tar.gz but only ${bundle.size} bytes " +
                        "(expected ~105 MB). Assets inside: $assets",
                )
            }
            logger.lifecycle(
                "Full APK verified: ${apk.name} ships environment ${bundle.size} bytes " +
                    "(${apk.length() / 1024 / 1024} MB total)",
            )
        }
    }
}

/**
 * Copies the most recent bundle into `app/src/full/assets/` and regenerates `local-env.json`
 * (version + SHA-256 + size) so the embedded environment is verified on installation.
 */
tasks.register("prepareFullBundle") {
    group = "build"
    description = "Copies dist/opencode-env-*-arm64.tar.gz into src/full/assets/ and writes local-env.json."

    doLast {
        val projectRoot = rootProject.projectDir
        val distDirs = listOf(projectRoot.resolve("dist"), projectRoot.resolve("server/dist"))
        val bundle = distDirs.asSequence()
            .flatMap { dir -> dir.listFiles().orEmpty().asSequence() }
            .filter { it.isFile && it.name.startsWith("opencode-env-") && it.name.endsWith(".tar.gz") }
            .maxByOrNull { it.lastModified() }
            ?: throw GradleException(
                "No bundle found in ${distDirs.joinToString { it.path }}. " +
                    "Build one with server/scripts/build-rootfs.sh --mode full … (or download " +
                    "opencode-env-*.tar.gz from the latest GitHub release).",
            )

        fullBundleFile.parentFile.mkdirs()
        bundle.copyTo(fullBundleFile, overwrite = true)

        val manifestText = distDirs.asSequence()
            .map { it.resolve("manifest.json") }
            .firstOrNull { it.isFile }
            ?.readText()
            .orEmpty()
        val version = Regex("\"version\"\\s*:\\s*\"([^\"]+)\"").find(manifestText)?.groupValues?.get(1)
            ?: Regex("opencode-env-(.+)-arm64\\.tar\\.gz").find(bundle.name)?.groupValues?.get(1)
            ?: "unset"
        val digest = MessageDigest.getInstance("SHA-256")
        bundle.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        val sha = digest.digest().joinToString("") { "%02x".format(it) }

        fullBundleManifest.writeText(
            """
            {
              "version": "$version",
              "abi": "arm64-v8a",
              "url": "",
              "asset": "local-env.tar.gz",
              "sha256": "$sha",
              "sizeBytes": ${fullBundleFile.length()},
              "notes": "Embedded into the Full APK by ./gradlew :app:prepareFullBundle."
            }
            """.trimIndent() + "\n",
        )
        logger.lifecycle(
            "prepareFullBundle: ${bundle.name} -> ${fullBundleFile.path} " +
                "(${fullBundleFile.length() / 1024 / 1024} MB, version=$version, sha256=$sha)",
        )
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
