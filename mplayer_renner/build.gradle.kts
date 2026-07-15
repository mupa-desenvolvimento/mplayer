import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.kapt")
}

val rennerIconSrc = file("ic_mplayer.png")
val rennerIconResDir = layout.buildDirectory.dir("generated/rennerIconResV2")

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
        applicationId = "com.mupa.player.renner"
        minSdk = 21
        targetSdk = 34

        // Versioning — single source of truth in version.properties (managed by build_release).
        versionCode = 100
        versionName = "1.0.0"
        run {
            val vp = Properties()
            val vpFile = file("version.properties")
            if (vpFile.exists()) {
                vpFile.inputStream().use { vp.load(it) }
                vp.getProperty("VERSION_NAME")?.trim()?.takeIf { it.isNotEmpty() }?.let { versionName = it }
                vp.getProperty("VERSION_CODE")?.trim()?.toIntOrNull()?.let { versionCode = it }
            }
        }

        resValue("string", "app_name", "MPlayer Renner")

        manifestPlaceholders["appIcon"] = "@drawable/ic_renner"
        manifestPlaceholders["appRoundIcon"] = "@drawable/ic_renner"

        fun String.escapeForBuildConfig(): String = replace("\\", "\\\\").replace("\"", "\\\"")
        val supabaseToken = (getConfig("SUPABASE_TOKEN") ?: "").trim()

        buildConfigField("String", "SUPABASE_TOKEN", "\"${supabaseToken.escapeForBuildConfig()}\"")
        buildConfigField("String", "BUILD_STAMP", "\"${System.currentTimeMillis()}\"")
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
        buildConfigField(
            "String",
            "TFLITE_MODELS_BASE_URL",
            "\"https://pub-0e15cc358ba84ff2a24226b12278433b.r2.dev/tflite/\"",
        )
        buildConfigField("Boolean", "USE_MAC_AS_SERIAL", "false")
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
                "../app/proguard-rules.pro",
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    flavorDimensions += "api"
    productFlavors {
        create("legacy") {
            dimension = "api"
            minSdk = 21
            buildConfigField("Boolean", "USE_MAC_AS_SERIAL", "true")
        }
        create("modern") {
            dimension = "api"
            minSdk = 24
        }
    }

    sourceSets {
        getByName("main") {
            manifest.srcFile("../app/src/main/AndroidManifest.xml")
            java.srcDirs("../app/src/main/java")
            res.srcDirs(rennerIconResDir, "src/main/res", "../app/src/main/res")
            assets.srcDirs("../app/src/main/assets")
        }
        // Override activity_camera_test.xml for the legacy flavor to replace
        // GestureOverlayView (engage-ui, modern-only) with a plain View so
        // the generated ActivityCameraTestBinding compiles without engage-ui.
        getByName("legacy") {
            res.srcDirs("../app/src/legacy/res")
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

val syncRennerIcon =
    tasks.register<Copy>("syncRennerIcon") {
        onlyIf { rennerIconSrc.exists() }
        from(rennerIconSrc)
        into(rennerIconResDir.map { it.dir("drawable-nodpi") })
        rename { "ic_renner.png" }
    }

tasks.named("preBuild") {
    dependsOn(syncRennerIcon)
}

dependencies {
    add("modernImplementation", project(":engage-ui"))
    add("modernImplementation", project(":engage-vision"))

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    implementation("com.google.android.material:material:1.12.0")

    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.2")
    implementation("androidx.lifecycle:lifecycle-process:2.8.2")

    implementation("androidx.datastore:datastore-preferences:1.1.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-moshi:2.11.0")
    implementation("com.squareup.moshi:moshi-kotlin:1.15.1")

    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-ui:1.3.1")

    implementation("io.coil-kt:coil:2.6.0")
    implementation("androidx.palette:palette-ktx:1.0.0")

    val cameraXVersion = "1.3.4"
    implementation("androidx.camera:camera-camera2:$cameraXVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraXVersion")
    implementation("androidx.camera:camera-view:$cameraXVersion")

    implementation("com.google.mlkit:face-detection:16.1.6")

    implementation("org.tensorflow:tensorflow-lite:2.16.1")

    add("modernImplementation", files("../app/libs/EasyLayerUnificadaVarejo_1_0_3.aar"))
}

tasks.register("assembleBothDebug") {
    dependsOn("assembleLegacyDebug", "assembleModernDebug")
}
