import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")
}

android {
    namespace = "com.mupa.player.enterprise"
    compileSdk = 34

    val localProps = Properties()
    val localPropsFile = rootProject.file("local.properties")
    if (localPropsFile.exists()) {
        localPropsFile.inputStream().use { localProps.load(it) }
    }

    fun getConfig(name: String): String? {
        return providers.gradleProperty(name).orNull
            ?: localProps.getProperty(name)
            ?: System.getenv(name)
    }

    defaultConfig {
        applicationId = "com.mupa.player.enterprise"
        minSdk = 24
        targetSdk = 34
        versionCode = 2
        versionName = "1.0.1"

        fun String.escapeForBuildConfig(): String = replace("\\", "\\\\").replace("\"", "\\\"")

        val defaultSupabaseToken =
            "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Iml1cnFkZGt1aWhqc214dWJpYmFvIiwicm9sZSI6ImFub24iLCJpYXQiOjE3MzI2MzUxNzMsImV4cCI6MjA0ODIxMTE3M30.KNNp_wFMSDWOuDlvWs2xuuzC5wj-d3saoc-8zQ4AkN4"
        val supabaseToken = getConfig("SUPABASE_TOKEN")?.trim().orEmpty().ifBlank { defaultSupabaseToken }

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
            fun requireProp(name: String): String {
                return (getConfig(name) ?: "").trim().ifBlank {
                    throw GradleException(
                        "Release signing not configured. Missing $name. " +
                            "Set it in local.properties or as Gradle property/environment variable.",
                    )
                }
            }

            val storeFilePath = (getConfig("RELEASE_STORE_FILE") ?: "").trim()
            if (storeFilePath.isBlank()) return@create

            storeFile = file(storeFilePath)
            if (!storeFile!!.exists()) {
                throw GradleException("Release keystore not found at: $storeFilePath")
            }
            storePassword = requireProp("RELEASE_STORE_PASSWORD")
            keyAlias = requireProp("RELEASE_KEY_ALIAS")
            keyPassword = requireProp("RELEASE_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            val storeFilePath = (getConfig("RELEASE_STORE_FILE") ?: "").trim()
            if (storeFilePath.isNotBlank()) {
                signingConfig = signingConfigs.getByName("release")
            }
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
