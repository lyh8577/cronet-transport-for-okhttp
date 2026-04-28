plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.gradleMavenPublish)
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

publishing {
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/lyh8577/pkgrepo")
            credentials {
                username = "lyh8577"
                password = System.getenv("TOKEN_GH")
            }
        }
    }
}

mavenPublishing {
    coordinates("com.github.yhong.android", "cronet-okhttp", "0.0.1")

    // the following is optional

    pom {
        name.set("Cronet Transport for OkHttp and Retrofit")
        description.set("This package allows OkHttp and Retrofit users to use Cronet as their transport layer, benefiting from features like QUIC/HTTP3 support and connection migration.")
        packaging = "aar"
        url.set("https://github.com/lyh8577/cronet-transport-for-okhttp")
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }
    }
}