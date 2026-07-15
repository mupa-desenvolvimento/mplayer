plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

layout.buildDirectory.set(file("$rootDir/.build/engage-vision"))

android {
    namespace = "com.mupa.engage.vision"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":engage-camera"))

    val cameraXVersion = "1.3.4"
    implementation("androidx.camera:camera-camera2:$cameraXVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraXVersion")
    implementation("androidx.camera:camera-core:$cameraXVersion")

    implementation("com.google.mediapipe:tasks-vision:0.10.35")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
