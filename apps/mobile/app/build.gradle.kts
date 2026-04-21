plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.google.services)
}

import java.util.Properties

val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use(::load)
    }
}

fun localConfigValue(key: String): String =
    localProperties.getProperty(key)
        ?: System.getenv(key)
        ?: ""

fun quotedBuildConfigValue(value: String): String =
    "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

val googleServerClientId = localConfigValue("GOOGLE_SERVER_CLIENT_ID")

android {
    namespace = "com.subhajit.mulberry"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.subhajit.mulberry"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    flavorDimensions += "environment"

    productFlavors {
        create("dev") {
            dimension = "environment"
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
            buildConfigField("String", "APP_ENVIRONMENT", "\"dev\"")
            buildConfigField("String", "API_BASE_URL", "\"http://10.0.2.2:8080/\"")
            buildConfigField("boolean", "ENABLE_DEBUG_MENU", "true")
            buildConfigField(
                "String",
                "GOOGLE_SERVER_CLIENT_ID",
                quotedBuildConfigValue(googleServerClientId)
            )
        }
        create("prod") {
            dimension = "environment"
            buildConfigField("String", "APP_ENVIRONMENT", "\"prod\"")
            buildConfigField("String", "API_BASE_URL", "\"https://api.mulberry.app/\"")
            buildConfigField("boolean", "ENABLE_DEBUG_MENU", "false")
            buildConfigField(
                "String",
                "GOOGLE_SERVER_CLIENT_ID",
                quotedBuildConfigValue(googleServerClientId)
            )
        }
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
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kapt {
    correctErrorTypes = true
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.material)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.retrofit.core)
    implementation(libs.retrofit.gson)
    implementation(libs.okhttp.logging)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.googleid)
    implementation(libs.hilt.android)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.hilt.work)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.firebase.messaging)
    implementation(libs.coil.compose)
    implementation(libs.androidx.ink.strokes)
    implementation(libs.androidx.ink.brush)
    implementation(libs.androidx.ink.brush.compose)
    implementation(libs.androidx.ink.rendering)
    implementation(libs.androidx.ink.nativeloader)
    kapt(libs.hilt.compiler)
    kapt(libs.androidx.hilt.compiler)
    kapt(libs.androidx.room.compiler)
    testImplementation(libs.junit)
    testImplementation(libs.androidx.datastore.preferences.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.room.testing)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
