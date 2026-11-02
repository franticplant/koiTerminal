import org.gradle.api.tasks.Exec

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// The dev agent: a separate package that owns the development control plane
// (listener, pairing, mutual TLS, scopes, access log, updater) and reaches the
// app's dev flavor through the DevHooks Binder service. It is never part of a
// production setup. Same signing key as the app, so the signature-level
// DEV_HOOKS permission ties the two together.
val koiVersionCode = providers.environmentVariable("KOI_VERSION_CODE")
val koiVersionName = providers.environmentVariable("KOI_VERSION_NAME")

// devd checks every APK it installs against the pinned koi certificate. The
// public digest is committed; the private key never is.
val pinnedCertFile = rootProject.file("../dev/signing/koi-dev-cert.sha256")
val pinnedFromEnv = providers.environmentVariable("KOI_CERT_SHA256").orNull?.trim()
val pinnedCert =
    if (!pinnedFromEnv.isNullOrEmpty()) pinnedFromEnv
    else {
        require(pinnedCertFile.isFile) { "missing ${pinnedCertFile.path}" }
        pinnedCertFile.readText().trim()
    }
require(Regex("[0-9a-f]{64}").matches(pinnedCert)) {
    "the pinned certificate must be one lowercase SHA-256 hex digest"
}

// devd's native network side (AndroidStudioProject/devd-native, crate
// koi-devd-native) is built by cargo-ndk into build/rustJniLibs/<abi>/libkoi_devd.so.
val rustAbis = (findProperty("rustAbis") ?: "arm64-v8a,x86_64").toString().split(',')
val rustOut = layout.buildDirectory.dir("rustJniLibs")
val rustSrc = rootProject.file("devd-native")

val cargoNdk =
    tasks.register<Exec>("cargoNdk") {
        group = "build"
        description = "Builds libkoi_devd.so for each Android ABI with cargo-ndk."
        workingDir = rustSrc
        val args = mutableListOf("cargo", "ndk", "--platform", "34")
        rustAbis.forEach { args += listOf("-t", it) }
        args += listOf("-o", rustOut.get().asFile.path, "build", "--release", "--locked")
        commandLine(args)
        inputs.files(
            fileTree(rustSrc) {
                include("Cargo.toml", "Cargo.lock", "src/**")
                exclude("target/**")
            }
        )
        inputs.property("abis", rustAbis.toList())
        outputs.dir(rustOut)
    }

android {
    namespace = "io.github.franticplant.koiterminal.devd"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "io.github.franticplant.koiterminal.devd"
        minSdk = 37
        targetSdk = 37
        versionCode = koiVersionCode.map { it.toInt() }.getOrElse(2026091900)
        versionName = koiVersionName.getOrElse("2026091900")
        buildConfigField("String", "PINNED_CERT_SHA256", "\"$pinnedCert\"")
    }

    val keystore = rootProject.file("keystore.jks")
    val keystorePassword = System.getenv("KEYSTORE_PASSWORD")
    if (keystore.exists() && !keystorePassword.isNullOrEmpty()) {
        signingConfigs {
            create("release") {
                storeFile = keystore
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            optimization { enable = false }
            if (keystore.exists() && !keystorePassword.isNullOrEmpty()) {
                signingConfig = signingConfigs["release"]
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
        compose = true
        aidl = true
    }

    sourceSets.getByName("main").jniLibs.directories.add(rustOut.get().asFile.path)
}

tasks.named("preBuild") { dependsOn(cargoNdk) }

// Third-party notices (assets/third_party_notices.txt): see AndroidStudioProject/notices.gradle.
extra["noticesRustManifest"] = "AndroidStudioProject/devd-native/Cargo.toml"
extra["noticesRustBuildTask"] = ":devd:cargoNdk"
apply(from = rootProject.file("notices.gradle"))

dependencies {
    implementation(project(":devapi"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)

    testImplementation(libs.junit)
    testImplementation("org.json:json:20240303")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("androidx.test:core:1.7.0")
}
