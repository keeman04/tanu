plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
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
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-core-pipeline"

        buildConfigField("String", "TANU_API_BASE_URL", "\"${apiBaseUrl.replace("\"", "\\\"")}\"")
        buildConfigField("String", "TANU_API_TOKEN", "\"${apiToken.replace("\"", "\\\"")}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
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
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
}
