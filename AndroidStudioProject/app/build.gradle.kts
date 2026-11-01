// import org.gradle.kotlin.dsl.support.kotlinCompilerOptions

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.protobuf)
}

// Version. scripts/build passes both from git: KOI_VERSION_CODE grows from the
// 2026091900 base with commit time, so it never goes backwards; KOI_VERSION_NAME
// is e.g. "2026091900-g1a2b3c4". A plain Gradle run gets the base version.
val koiVersionCode = providers.environmentVariable("KOI_VERSION_CODE")
val koiVersionName = providers.environmentVariable("KOI_VERSION_NAME")

android {
    namespace = "com.android.virtualization.koiterminal"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "io.github.franticplant.koiterminal"
        minSdk = 37
        targetSdk = 37
        versionCode = koiVersionCode.map { it.toInt() }.getOrElse(2026091900)
        versionName = koiVersionName.getOrElse("2026091900")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // dev and prod are the same application (same applicationId, same signing
    // key), so either installs over the other. dev adds the development control
    // plane: its code lives only in src/dev, so a prod APK does not contain it
    // at all, and scripts/check-release-apk.sh verifies that.
    flavorDimensions += "tooling"
    productFlavors {
        create("dev") {
            dimension = "tooling"
            versionNameSuffix = "-dev"
            buildConfigField("boolean", "DEV_TOOLS", "true")
        }
        create("prod") {
            dimension = "tooling"
            buildConfigField("boolean", "DEV_TOOLS", "false")
        }
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
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
            if (keystore.exists() && !keystorePassword.isNullOrEmpty()) {
                signingConfig = signingConfigs["release"]
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        buildConfig = true
        compose = true
        aidl = true
    }
}

dependencies {
    implementation(project(":libs_debian_service"))
    implementation(project(":libs_android_display_backend"))
    implementation(project(":android_virtualizationservice_aidl"))
    implementation(project(":koiterminal-stubs"))
    // The development control API, for src/dev only.
    "devImplementation"(project(":devapi"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.com.google.android.material)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.window)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.compose.material3.adaptive)
    implementation(libs.androidx.compose.material3.adaptive.layout)
    implementation(libs.androidx.compose.material3.adaptive.navigation)
    implementation(libs.protobuf.kotlinlite)
    implementation(libs.org.apache.commons.compress)
    implementation(libs.androidx.work)
    implementation(libs.io.grpc.stub)
    implementation(libs.io.grpc.okhttp)
    implementation(libs.com.google.code.gson)
}

protobuf {
    protoc { artifact = "com.google.protobuf:protoc:4.33.0" }
    generateProtoTasks {
        all().forEach {
            it.plugins {
                create("java") { option("lite") }
                create("kotlin")
            }
        }
    }
}
