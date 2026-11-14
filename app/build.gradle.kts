plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "com.example.notes"
    compileSdk = 34
    defaultConfig { applicationId = "com.example.notes"; minSdk = 26; targetSdk = 34; versionCode = 1; versionName = "1.0" }
    // Passkeys are tied to the app's signing key. Put a keystore.properties next to settings.gradle.kts
    // (see keystore.properties.example) so debug and release builds always use the same key.
    val ks = java.util.Properties().apply { val f = rootProject.file("keystore.properties"); if (f.exists()) load(f.inputStream()) }
    signingConfigs {
        if (ks.isNotEmpty()) create("ez") {
            storeFile = rootProject.file(ks.getProperty("storeFile")); storePassword = ks.getProperty("storePassword")
            keyAlias = ks.getProperty("keyAlias"); keyPassword = ks.getProperty("keyPassword")
        }
    }
    buildTypes {
        getByName("debug") { signingConfigs.findByName("ez")?.let { signingConfig = it } }
        getByName("release") { isMinifyEnabled = false; signingConfigs.findByName("ez")?.let { signingConfig = it } }
    }
    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("io.coil-kt:coil-compose:2.6.0")
    implementation("androidx.credentials:credentials:1.2.2")
    implementation("androidx.credentials:credentials-play-services-auth:1.2.2")
}
