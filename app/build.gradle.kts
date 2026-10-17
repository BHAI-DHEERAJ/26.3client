plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ---------------------------------------------------------------------------
// Webhook configuration (injected at build time, never committed)
//
// Priority:
//   1. -PhtmlBackup.webhookUrl=...
//   2. HTML_BACKUP_WEBHOOK_URL environment variable (used by CI from the
//      DISCORD_WEBHOOK_URL repository secret)
//   3. empty string -> WebhookConfig falls back to its placeholder and Send is
//      disabled in the app.
//
// The APK necessarily contains the URL when one is supplied. Keep builds that
// contain a real webhook private.
// ---------------------------------------------------------------------------
val configuredWebhookUrl: String = providers.gradleProperty("htmlBackup.webhookUrl")
    .orElse(providers.environmentVariable("HTML_BACKUP_WEBHOOK_URL"))
    .orElse("")
    .get()
    .trim()

fun quotedJavaString(value: String): String =
    "\"" + value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\r", "")
        .replace("\n", "\\n") + "\""

android {
    namespace = "com.example.htmlbackup"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.htmlbackup"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        buildConfigField("String", "WEBHOOK_URL", quotedJavaString(configuredWebhookUrl))
    }

    buildTypes {
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

    buildFeatures {
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.json:json:20240303")
}
