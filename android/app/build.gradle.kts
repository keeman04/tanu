plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

val apiBaseUrl = providers.gradleProperty("TANU_API_BASE_URL")
    .orElse("http://10.0.2.2:8000")
    .get()
val apiToken = providers.gradleProperty("TANU_API_TOKEN")
    .orElse("")
    .get()

android {
    namespace = "com.tanu.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tanu.app"
        minSdk = 29
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0-phase1-long-meeting"

        buildConfigField("String", "TANU_API_BASE_URL", "\"${apiBaseUrl.replace("\"", "\\\"")}\"")
        buildConfigField("String", "TANU_API_TOKEN", "\"${apiToken.replace("\"", "\\\"")}\"")
    }

    buildFeatures { buildConfig = true }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    implementation("androidx.room:room-runtime:2.8.2")
    implementation("androidx.room:room-ktx:2.8.2")
    ksp("androidx.room:room-compiler:2.8.2")

    implementation("androidx.work:work-runtime-ktx:2.11.2")

    testImplementation("junit:junit:4.13.2")
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}
