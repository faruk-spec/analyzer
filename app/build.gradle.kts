import java.io.File
import java.util.Base64

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.kapt)
}

android {
    namespace = "com.example.aviatorsignallab"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.aviator.signallab"
        minSdk = 26
        targetSdk = 35
        versionCode = 17
        versionName = "1.1.6"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "TARGET_URL", "\"https://damansuperstar1.com/\"")
        buildConfigField("String", "UPDATE_METADATA_URL", "\"https://api.github.com/repos/faruk-spec/analyzer/releases/latest\"")
    }

    signingConfigs {
        create("release") {
            val persistentKeystore = file("keystore/release.jks")
            val keystoreBase64 = System.getenv("KEYSTORE_BASE64")?.takeUnless { it.isBlank() }
            val keystoreFileEnv = System.getenv("KEYSTORE_FILE")?.takeUnless { it.isBlank() }
            val storePass = System.getenv("KEYSTORE_PASSWORD")?.takeUnless { it.isBlank() }
                ?: System.getenv("KEY_STORE_PASSWORD")?.takeUnless { it.isBlank() }
                ?: "aviatorsignallab2026"
            val keyAl = System.getenv("KEY_ALIAS")?.takeUnless { it.isBlank() }
                ?: "aviatorlab"
            val keyPass = System.getenv("KEY_PASSWORD")?.takeUnless { it.isBlank() }
                ?: "aviatorsignallab2026"

            if (persistentKeystore.exists()) {
                storeFile = persistentKeystore
                storePassword = storePass
                keyAlias = keyAl
                keyPassword = keyPass
            } else if (!keystoreBase64.isNullOrBlank()) {
                val decodedKeystore = File(layout.buildDirectory.asFile.get(), "release-keystore.jks")
                decodedKeystore.parentFile?.mkdirs()
                decodedKeystore.writeBytes(Base64.getDecoder().decode(keystoreBase64))
                storeFile = decodedKeystore
                storePassword = storePass
                keyAlias = keyAl
                keyPassword = keyPass
            } else if (!keystoreFileEnv.isNullOrBlank() && File(keystoreFileEnv).exists()) {
                storeFile = File(keystoreFileEnv)
                storePassword = storePass
                keyAlias = keyAl
                keyPassword = keyPass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            val releaseSigning = signingConfigs.getByName("release")
            if (releaseSigning.storeFile != null && releaseSigning.storeFile!!.exists()) {
                signingConfig = releaseSigning
            } else {
                signingConfig = signingConfigs.getByName("debug")
            }
        }
        debug {
            isDebuggable = true
            applicationIdSuffix = ""
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.webkit)
    implementation(libs.material)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    // Room Database
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    kapt(libs.androidx.room.compiler)

    // Coroutines
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    // Network & JSON
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.gson)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
