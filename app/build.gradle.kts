plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

fun signingValue(name: String): String? = (findProperty(name) as String?) ?: System.getenv(name)

val releaseVersionName = providers.gradleProperty("APP_VERSION_NAME")
    .orElse(providers.environmentVariable("APP_VERSION_NAME"))
    .orElse("1.0")
val releaseVersionCode = providers.gradleProperty("APP_VERSION_CODE")
    .map(String::toInt)
    .orElse(
        providers.environmentVariable("APP_VERSION_CODE").map(String::toInt)
    )
    .orElse(1)
val releaseStoreFile = signingValue("ANDROID_SIGNING_STORE_FILE")
val releaseStorePassword = signingValue("ANDROID_SIGNING_STORE_PASSWORD")
val releaseKeyAlias = signingValue("ANDROID_SIGNING_KEY_ALIAS")
val releaseKeyPassword = signingValue("ANDROID_SIGNING_KEY_PASSWORD")

android {
    namespace = "com.appblocker"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.appblocker"
        minSdk = 26
        targetSdk = 34
        versionCode = releaseVersionCode.get()
        versionName = releaseVersionName.get()
    }

    signingConfigs {
        if (
            releaseStoreFile != null &&
            releaseStorePassword != null &&
            releaseKeyAlias != null &&
            releaseKeyPassword != null
        ) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            signingConfigs.findByName("release")?.let { signingConfig = it }
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
        viewBinding = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.google.code.gson:gson:2.10.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("androidx.test:core:1.5.0")
    testImplementation("org.robolectric:robolectric:4.11.1")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:runner:1.5.2")
}
