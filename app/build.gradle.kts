import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun fcmProp(key: String) = (localProps.getProperty(key) ?: "").trim().replace("\"", "")

android {
    namespace = "pl.sinusoide.sinuschat"
    compileSdk {
        version = release(37) {
            minorApiLevel = 1
        }
    }
    ndkVersion = "30.0.16248370"

    defaultConfig {
        applicationId = "pl.sinusoide.sinuschat"
        minSdk = 26
        targetSdk = 36
        versionCode = 25
        versionName = "1.2.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "FCM_API_KEY", "\"${fcmProp("fcm.apiKey")}\"")
        buildConfigField("String", "FCM_APP_ID", "\"${fcmProp("fcm.appId")}\"")
        buildConfigField("String", "FCM_PROJECT_ID", "\"${fcmProp("fcm.projectId")}\"")
        buildConfigField("String", "FCM_SENDER_ID", "\"${fcmProp("fcm.senderId")}\"")

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    val keystorePath = System.getenv("KEYSTORE_PATH") ?: (project.findProperty("KEYSTORE_PATH") as? String)
    signingConfigs {
        if (!keystorePath.isNullOrBlank() && file(keystorePath).exists()) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD") ?: (project.findProperty("KEYSTORE_PASSWORD") as? String)
                keyAlias = System.getenv("KEY_ALIAS") ?: (project.findProperty("KEY_ALIAS") as? String)
                keyPassword = System.getenv("KEY_PASSWORD") ?: (project.findProperty("KEY_PASSWORD") as? String)
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (project.hasProperty("r8test")) {
                signingConfig = signingConfigs.getByName("debug")
            } else if (signingConfigs.findByName("release") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            ndk {
                debugSymbolLevel = "SYMBOL_TABLE"
            }
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
        isCoreLibraryDesugaringEnabled = true
    }

    androidResources {
        noCompress += listOf("task", "tflite", "bin", "litertlm")
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/LICENSE", "META-INF/NOTICE",
                "*.dylib", "**/*.dylib", "*.dll", "**/*.dll"
            )
        }
        jniLibs {
            useLegacyPackaging = false
            excludes += setOf("**/libsignal_jni_testing.so")
            pickFirsts += setOf("lib/arm64-v8a/libc++_shared.so")
        }
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("androidx.fragment:fragment-ktx:1.8.3")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.coordinatorlayout:coordinatorlayout:1.2.0")
    implementation("androidx.exifinterface:exifinterface:1.4.2")

    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("com.google.zxing:core:3.5.3")

    implementation("com.duckduckgo.translatekit:translate-kit-android:0.1.0")

    implementation("com.google.ai.edge.litertlm:litertlm-android:0.11.0")
    implementation("com.google.android.gms:play-services-tflite-java:16.4.0")
    implementation("com.google.android.gms:play-services-tflite-gpu:16.4.0")
    implementation("com.google.android.gms:play-services-tflite-support:16.4.0")

    implementation("androidx.biometric:biometric:1.1.0")

    implementation("com.google.firebase:firebase-messaging:24.1.0")

    implementation("org.signal:libsignal-android:0.101.1")

    implementation("pl.sinusoide.sinuschat:link:1.0")

    implementation("io.github.webrtc-sdk:android:144.7559.04")

    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("com.google.crypto.tink:tink-android:1.8.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("com.google.android.gms:play-services-auth:21.2.0")

    implementation("com.googlecode.libphonenumber:libphonenumber:8.13.50")

    implementation("com.github.bumptech.glide:glide:4.16.0")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("net.zetetic:sqlcipher-android:4.17.0")
    implementation("androidx.sqlite:sqlite:2.4.0")

    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-process:2.7.0")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-sse:4.12.0")

    implementation("info.guardianproject:tor-android:0.4.9.14")
    implementation("info.guardianproject:jtorctl:0.4.5.7")

    implementation("androidx.work:work-runtime-ktx:2.9.0")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
