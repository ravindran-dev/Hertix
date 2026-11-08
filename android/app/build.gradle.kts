plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "dev.ravindran.ythub.mobile"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.ravindran.ythub.mobile"
        minSdk = 26
        targetSdk = 36
        for (key in listOf("SUPABASE_URL", "SUPABASE_PUBLISHABLE_KEY")) {
            val value = providers.environmentVariable(key).orElse(providers.gradleProperty(key)).getOrElse("")
            buildConfigField("String", key, "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
        }
        // Keep versionCode monotonic from semantic versions: major*1,000,000 + minor*1,000 + patch.
        versionCode = 1_000_010
        versionName = "1.0.10"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true; buildConfig = true }
    signingConfigs {
        create("release") {
            val store = providers.environmentVariable("YTHUB_RELEASE_KEYSTORE").orNull
            if (!store.isNullOrBlank()) {
                storeFile = file(store)
                storePassword = providers.environmentVariable("YTHUB_RELEASE_STORE_PASSWORD").orNull
                keyAlias = providers.environmentVariable("YTHUB_RELEASE_KEY_ALIAS").orNull
                keyPassword = providers.environmentVariable("YTHUB_RELEASE_KEY_PASSWORD").orNull
            }
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    packaging { jniLibs.useLegacyPackaging = true }
    buildTypes {
        release { isMinifyEnabled = false; signingConfig = signingConfigs.getByName("release") }
        // Internal phone validation with release runtime behavior and the existing install signature.
        create("preview") {
            initWith(getByName("release"))
            isDebuggable = false
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.06.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("androidx.media3:media3-session:1.8.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.google.code.gson:gson:2.13.1")
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.06.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
