import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ksp)
}

val localProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}

val googleOauthClientId = (
    localProps.getProperty("GOOGLE_OAUTH_CLIENT_ID")
        ?: System.getenv("GOOGLE_OAUTH_CLIENT_ID")
        ?: ""
    ).trim()

val appAuthRedirectScheme = if (googleOauthClientId.endsWith(".apps.googleusercontent.com")) {
    googleOauthClientId.split(".").reversed().joinToString(".")
} else {
    "com.adwewecat.nana.oauth"
}

val escapedGoogleOauthClientId = googleOauthClientId
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")

fun readLocalOrEnv(name: String, defaultValue: String = ""): String =
    (localProps.getProperty(name) ?: System.getenv(name) ?: defaultValue)
        .trim()
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")

val seedGeminiPrimary = readLocalOrEnv("NANA_SEED_GEMINI_PRIMARY")
val seedGeminiBackup = readLocalOrEnv("NANA_SEED_GEMINI_BACKUP")
val seedGeminiPrimaryModel = readLocalOrEnv("NANA_SEED_GEMINI_PRIMARY_MODEL", "gemini-3.5-flash")
val seedGeminiBackupModel = readLocalOrEnv("NANA_SEED_GEMINI_BACKUP_MODEL", "gemini-3.5-flash-lite")

android {
    namespace = "com.adwewecat.nana"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.adwewecat.nana"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        manifestPlaceholders["appAuthRedirectScheme"] = appAuthRedirectScheme
        buildConfigField("String", "GOOGLE_OAUTH_CLIENT_ID", "\"$escapedGoogleOauthClientId\"")
        buildConfigField("String", "NANA_SEED_GEMINI_PRIMARY", "\"$seedGeminiPrimary\"")
        buildConfigField("String", "NANA_SEED_GEMINI_BACKUP", "\"$seedGeminiBackup\"")
        buildConfigField("String", "NANA_SEED_GEMINI_PRIMARY_MODEL", "\"$seedGeminiPrimaryModel\"")
        buildConfigField("String", "NANA_SEED_GEMINI_BACKUP_MODEL", "\"$seedGeminiBackupModel\"")
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

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.okhttp)
    implementation(libs.appauth)
    implementation(libs.androidx.security.crypto)
    implementation(libs.vosk.android)
    implementation(libs.jna) {
        artifact {
            type = "aar"
        }
    }

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.okhttp.mockwebserver)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}
