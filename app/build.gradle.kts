import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")
}

android {
    namespace = "com.mupa.player.enterprise"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.mupa.player.enterprise"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        fun String.escapeForBuildConfig(): String = replace("\\", "\\\\").replace("\"", "\\\"")

        val localProps = Properties()
        val localPropsFile = rootProject.file("local.properties")
        if (localPropsFile.exists()) {
            localPropsFile.inputStream().use { localProps.load(it) }
        }

        val localSupabaseToken: String? = localProps.getProperty("SUPABASE_TOKEN")
        val defaultSupabaseToken =
            "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Iml1cnFkZGt1aWhqc214dWJpYmFvIiwicm9sZSI6ImFub24iLCJpYXQiOjE3MzI2MzUxNzMsImV4cCI6MjA0ODIxMTE3M30.KNNp_wFMSDWOuDlvWs2xuuzC5wj-d3saoc-8zQ4AkN4"
        val supabaseToken = (
            providers.gradleProperty("SUPABASE_TOKEN").orNull
                ?: localSupabaseToken
                ?: System.getenv("SUPABASE_TOKEN")
                ?: defaultSupabaseToken
            ).trim()

        buildConfigField(
            "String",
            "SUPABASE_TOKEN",
            "\"${supabaseToken.escapeForBuildConfig()}\"",
        )
        buildConfigField(
            "String",
            "SUPABASE_DEVICE_RPC_URL",
            "\"https://iurqddkuihjsmxubibao.supabase.co/rest/v1/rpc/get_dispositivo_por_serial\"",
        )
        buildConfigField(
            "String",
            "SUPABASE_CREATE_DEVICE_RPC_URL",
            "\"https://iurqddkuihjsmxubibao.supabase.co/rest/v1/rpc/create_dispositivo\"",
        )
        buildConfigField(
            "String",
            "SUPABASE_COMPANIES_URL",
            "\"https://iurqddkuihjsmxubibao.supabase.co/rest/v1/companies\"",
        )
    }

    signingConfigs {
        create("release") {
            val localProps = Properties()
            val localPropsFile = rootProject.file("local.properties")
            if (localPropsFile.exists()) {
                localPropsFile.inputStream().use { localProps.load(it) }
            }

            val storeFilePath =
                providers.gradleProperty("RELEASE_STORE_FILE").orNull
                    ?: localProps.getProperty("RELEASE_STORE_FILE")
                    ?: System.getenv("RELEASE_STORE_FILE")
                    ?: "${System.getProperty("user.home")}\\.android\\debug.keystore"

            storeFile = file(storeFilePath)
            storePassword =
                providers.gradleProperty("RELEASE_STORE_PASSWORD").orNull
                    ?: localProps.getProperty("RELEASE_STORE_PASSWORD")
                    ?: System.getenv("RELEASE_STORE_PASSWORD")
                    ?: "android"
            keyAlias =
                providers.gradleProperty("RELEASE_KEY_ALIAS").orNull
                    ?: localProps.getProperty("RELEASE_KEY_ALIAS")
                    ?: System.getenv("RELEASE_KEY_ALIAS")
                    ?: "androiddebugkey"
            keyPassword =
                providers.gradleProperty("RELEASE_KEY_PASSWORD").orNull
                    ?: localProps.getProperty("RELEASE_KEY_PASSWORD")
                    ?: System.getenv("RELEASE_KEY_PASSWORD")
                    ?: "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            isMinifyEnabled = false
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
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    implementation("com.google.android.material:material:1.12.0")

    implementation("androidx.webkit:webkit:1.12.1")

    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.2")
    implementation("androidx.lifecycle:lifecycle-process:2.8.2")

    implementation("androidx.work:work-runtime-ktx:2.9.0")

    implementation("androidx.datastore:datastore-preferences:1.1.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    implementation(platform("com.google.firebase:firebase-bom:33.2.0"))
    implementation("com.google.firebase:firebase-database-ktx")
    implementation("com.google.firebase:firebase-storage-ktx")
}
