plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "win.fantest.familytouch"
    testBuildType = "qa"
    compileSdk = 36
    defaultConfig {
        applicationId = "win.fantest.familytouch"
        minSdk = 29
        targetSdk = 36
        versionCode = 18
        versionName = "0.3.1-connect01"
        buildConfigField("boolean", "EMBEDDED_VPN", "true")
        buildConfigField("boolean", "ADMIN_APP", "false")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        manifestPlaceholders["familyTouchAppLabel"] = "Family Touch"
    }
    signingConfigs {
        create("internal") {
            val keyPath = System.getenv("FAMILY_TOUCH_KEYSTORE_FILE")
            if (!keyPath.isNullOrBlank()) storeFile = file(keyPath)
            storePassword = System.getenv("FAMILY_TOUCH_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("FAMILY_TOUCH_KEY_ALIAS")
            keyPassword = System.getenv("FAMILY_TOUCH_KEY_PASSWORD")
        }
    }
    buildTypes {
        create("qa") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".qa"
            versionNameSuffix = "-qa"
            manifestPlaceholders["familyTouchAppLabel"] = "Family Touch QA"
            matchingFallbacks += listOf("debug")
        }
        release {
            signingConfig = signingConfigs.getByName("internal")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        create("embedded") {
            initWith(getByName("release"))
            applicationIdSuffix = ".embedded"
            versionNameSuffix = "-embedded"
            manifestPlaceholders["familyTouchAppLabel"] = "Family Touch"
            matchingFallbacks += listOf("release")
        }
        create("admin") {
            initWith(getByName("release"))
            applicationIdSuffix = ".admin"
            versionNameSuffix = "-admin"
            buildConfigField("boolean", "ADMIN_APP", "true")
            manifestPlaceholders["familyTouchAppLabel"] = "Family Touch Admin"
            matchingFallbacks += listOf("release")
        }
    }
    sourceSets {
        listOf("debug", "release", "qa", "embedded").forEach { variant ->
            getByName(variant) {
                java.srcDir("src/engine/java")
                manifest.srcFile("src/engine/AndroidManifest.xml")
            }
        }
        getByName("admin") {
            java.srcDir("src/engine/java")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

dependencies {
    listOf("debug", "release", "qa", "embedded", "admin").forEach { variant ->
        add("${variant}Implementation", files("libs/libtailscale.aar"))
    }
    implementation("androidx.security:security-crypto:1.1.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("io.github.webrtc-sdk:android:150.7871.01")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
