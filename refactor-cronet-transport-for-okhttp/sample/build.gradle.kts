plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.google.samples.cronet.okhttptransport"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.google.samples.cronet.okhttptransport"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
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
}

dependencies {
    implementation(project(":library"))
//    implementation(libs.cronet.okhttp)
    implementation(libs.play.services.cronet)
    implementation(libs.okhttp)
    implementation(libs.guava)
    implementation(libs.jsr305)
    implementation(libs.cronet)
    implementation(libs.cronet.bundled)
}