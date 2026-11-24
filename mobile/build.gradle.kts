import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Optional local-only input. CI and ordinary source builds contain no accessory identity.
val localAuthenticationAssets = providers.environmentVariable("DIPLAY_AUTH_ASSETS_DIR")
    .orNull?.let { file(it).canonicalFile }

// A per-build stamp: short git hash + build time.
//
// versionCode and versionName are frozen between test builds, and PackageInfo.lastUpdateTime is
// the install time rather than the build time, so without this a diagnostic report cannot say
// which APK produced it. Packaged as an asset (not a generated Kotlin constant) so a rebuild only
// repackages the APK and never recompiles the common module's large activities.
val buildStampAssetsDir = layout.buildDirectory.dir("generated/buildStamp/assets")
val buildStampRepoRoot = rootProject.projectDir

val generateBuildStamp by tasks.registering {
    val outputDir = buildStampAssetsDir.get().asFile
    val repoRoot = buildStampRepoRoot
    outputs.dir(outputDir)
    // The timestamp is the point: rewrite it on every build, even when nothing else changed.
    outputs.upToDateWhen { false }
    doLast {
        val hash = runCatching {
            ProcessBuilder("git", "rev-parse", "--short", "HEAD")
                .directory(repoRoot)
                .redirectErrorStream(true)
                .start()
                .inputStream.bufferedReader()
                .use { it.readText().trim() }
        }.getOrDefault("nogit")
        val dirty = runCatching {
            ProcessBuilder("git", "status", "--porcelain")
                .directory(repoRoot)
                .redirectErrorStream(true)
                .start()
                .inputStream.bufferedReader()
                .use { it.readText().isNotBlank() }
        }.getOrDefault(false)
        val time = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        val value = buildString {
            append(hash)
            if (dirty) append("-dirty")
            append(" / ").append(time)
        }
        outputDir.mkdirs()
        outputDir.resolve("build_stamp.txt").writeText(value)
    }
}

val releaseKeystorePath = providers.environmentVariable("ANDROID_KEYSTORE_PATH")
    .orNull?.trim()?.takeIf { it.isNotEmpty() }
val hasPrivateReleaseKeystore = releaseKeystorePath != null && file(releaseKeystorePath).isFile
val requirePrivateSigning = providers.gradleProperty("requirePrivateSigning")
    .map { it.equals("true", ignoreCase = true) }
    .getOrElse(false)

android {
    namespace = "com.shilapi.xcertplay"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        // Both variants share the .hudtest application id on purpose: the debug
        // channel has a large installed base and a release build must overwrite
        // those installs in place, keeping their pairing data.
        applicationId = "com.shihab.diplay.hudtest"
        minSdk = 25
        targetSdk = 37
        versionCode = 45
        versionName = "0.2.30"

    }


    localAuthenticationAssets?.let { sourceSets.getByName("main").assets.srcDir(it) }
    sourceSets.getByName("main").assets.srcDir(buildStampAssetsDir.get().asFile)

    signingConfigs {
        create("release") {
            storeFile = file(releaseKeystorePath ?: "missing-release-keystore.jks")
            storePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").getOrElse("")
            keyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").getOrElse("")
            keyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").getOrElse("")
            enableV1Signing = true
            enableV2Signing = true
        }
    }

    buildTypes {
        debug {
            versionNameSuffix = "-hud-test"
        }
        release {
            optimization {
                enable = true
            }
            isMinifyEnabled = true
            isShrinkResources = true
            versionNameSuffix = "-hud-release"
            // Prefer an explicitly configured private keystore (SEC-01) so third-party debug-signed
            // APKs cannot overwrite the car install; fall back to debug signing only for local
            // source/CI builds when requirePrivateSigning is not enforced.
            signingConfig = if (hasPrivateReleaseKeystore) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

// The generated stamp must exist before assets are merged into the APK.
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }.configureEach {
    dependsOn(generateBuildStamp)
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(project(":common"))
    implementation(project(":shared"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.app.projected)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

// No implicit import. Only the two explicitly selected local runtime assets are allowed.
val credentialAssets = files(android.sourceSets.flatMap { source ->
    source.assets.directories.map { directory ->
        fileTree(directory) {
            include("**/offline-mfi/**", "**/*.pk8", "**/*.p7b", "**/*.key",
                "**/*.pem", "**/*.p12", "**/*.pfx", "**/*.jks", "**/*.keystore")
        }
    }
})
val rejectBundledCredentials by tasks.registering {
    group = "verification"
    description = "Reject unexpected credential files in APK assets."
    // The generated build stamp is one of the asset directories this task scans.
    dependsOn(generateBuildStamp)
    val filesToCheck = credentialAssets
    val allowed = localAuthenticationAssets?.let { dir ->
        listOf("identity.pk8", "certificate.p7b").map { dir.resolve("offline-mfi/$it").canonicalFile }.toSet()
    } ?: emptySet()
    inputs.files(filesToCheck)
    doLast {
        check(allowed.all { it.isFile }) { "Explicit local authentication assets are incomplete" }
        val unexpected = filesToCheck.files.filter { it.canonicalFile !in allowed }
        check(unexpected.isEmpty()) { "Unexpected credential files in APK assets" }
    }
}
tasks.named("preBuild") { dependsOn(rejectBundledCredentials) }

// Car-test packages must be standalone. Keep ordinary source/CI builds identity-free.
val verifyStandaloneAuthentication by tasks.registering {
    group = "verification"
    description = "Require the explicit runtime authentication input for a standalone car-test APK."
    val directory = localAuthenticationAssets
    doLast {
        check(directory != null) {
            "Standalone car builds require DIPLAY_AUTH_ASSETS_DIR; assembleDebug alone is source-only."
        }
        check(listOf("identity.pk8", "certificate.p7b").all {
            directory.resolve("offline-mfi/$it").let { file -> file.isFile && file.length() > 0 }
        }) { "Standalone CarPlay authentication files are missing or empty" }
    }
}
val verifyPrivateReleaseSigning by tasks.registering {
    group = "verification"
    description = "Require a private release keystore (ANDROID_KEYSTORE_PATH) instead of the public debug key."
    val configured = hasPrivateReleaseKeystore
    val keystorePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").orNull.orEmpty()
    val alias = providers.environmentVariable("ANDROID_KEY_ALIAS").orNull.orEmpty()
    val keyPwd = providers.environmentVariable("ANDROID_KEY_PASSWORD").orNull.orEmpty()
    doLast {
        check(configured) {
            "Private release signing requires ANDROID_KEYSTORE_PATH pointing to an existing .jks/.keystore file."
        }
        check(keystorePassword.isNotBlank() && alias.isNotBlank() && keyPwd.isNotBlank()) {
            "Private release signing requires ANDROID_KEYSTORE_PASSWORD, ANDROID_KEY_ALIAS, and ANDROID_KEY_PASSWORD."
        }
    }
}
if (requirePrivateSigning) {
    tasks.matching { it.name == "packageRelease" || it.name == "assembleRelease" }.configureEach {
        dependsOn(verifyPrivateReleaseSigning)
    }
} else if (!hasPrivateReleaseKeystore) {
    tasks.matching { it.name == "packageRelease" }.configureEach {
        doFirst {
            logger.warn(
                "SECURITY WARNING (SEC-01): ANDROID_KEYSTORE_PATH is not set; packaging release APK " +
                    "with the public debug signingConfig. Pass ANDROID_KEYSTORE_* or use assemblePrivateRelease for car installs.",
            )
        }
    }
}
tasks.named("preBuild") { mustRunAfter(verifyStandaloneAuthentication, verifyPrivateReleaseSigning) }
tasks.register("assembleStandaloneDebug") {
    group = "build"
    description = "Build a standalone car-test APK with explicitly provisioned authentication."
    dependsOn(verifyStandaloneAuthentication, "assembleDebug")
}
tasks.register("assemblePrivateRelease") {
    group = "build"
    description = "Build a car-ready release APK with standalone MFi assets and private release signing."
    dependsOn(verifyStandaloneAuthentication, verifyPrivateReleaseSigning, "assembleRelease")
}
