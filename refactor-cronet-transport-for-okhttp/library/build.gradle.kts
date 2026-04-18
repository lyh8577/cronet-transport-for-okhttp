plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.google.net.cronet.okhttptransport"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        minSdk = 23

        consumerProguardFiles("consumer-rules.pro")
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
    compileOnly(libs.okhttp)
    compileOnly(libs.okio)

    implementation(libs.androidx.annotation)
    implementation(libs.jsr305)
    implementation(libs.guava)
    implementation(libs.cronet.api)
}