plugins {
    alias(libs.plugins.android.library)
}

// The development control API's shared definitions: method specs, parameter
// checking, and the Binder interface (IDevHooks) between the dev agent
// (:devd) and the app's dev flavor. Only dev builds depend on this module; its
// package is io.github.franticplant.koiterminal.dev.api, which
// scripts/check-release-apk.sh requires to be absent from prod APKs.
android {
    namespace = "io.github.franticplant.koiterminal.dev.api"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 37
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        aidl = true
    }
}

dependencies {
    // org.json is part of Android; JVM unit tests need the real one.
    testImplementation(libs.junit)
    testImplementation("org.json:json:20240303")
}
