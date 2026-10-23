import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Optional release signing: reads release.properties (storeFile, storePassword, keyAlias, keyPassword)
val releaseProps = Properties().apply {
    val f = rootProject.file("release.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.abhirha.shraa"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.abhirha.shraa"
        minSdk = 21
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        if (releaseProps.getProperty("storeFile") != null) {
            create("release") {
                storeFile = rootProject.file(releaseProps.getProperty("storeFile"))
                storePassword = releaseProps.getProperty("storePassword")
                keyAlias = releaseProps.getProperty("keyAlias")
                keyPassword = releaseProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // java.time (ZonedDateTime, LocalDateTime...) on API 21-25
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
}
