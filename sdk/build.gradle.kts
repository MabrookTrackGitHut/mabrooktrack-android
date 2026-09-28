plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("maven-publish")
}

group = "com.mabrooktrack"
version = "0.1.0"

android {
    namespace = "com.mabrooktrack.sdk"
    compileSdk = 35
    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    publishing { singleVariant("release") { withSourcesJar() } }
}

dependencies {
    implementation("com.google.android.gms:play-services-ads-identifier:18.1.0")
    implementation("com.android.installreferrer:installreferrer:2.2")
}

// `./gradlew :sdk:publishToMavenLocal` for local testing; JitPack / Maven Central use this too.
afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = "com.mabrooktrack"
                artifactId = "sdk"
                version = project.version.toString()
                pom {
                    name.set("MabrookTrack Android SDK")
                    description.set("Install attribution and in-app events for TikTok app campaigns.")
                    url.set("https://mabrooktrack.com/#mmp")
                }
            }
        }
    }
}
