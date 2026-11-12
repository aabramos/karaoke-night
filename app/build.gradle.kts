import java.util.Properties

plugins {
    id("com.android.application")
}

// Optional settings live in local.properties (never commit it):
//   YOUTUBE_API_KEY=AIza...   (leave out to search without a key)
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

// Optional release signing: create keystore.properties next to local.properties with
//   storeFile=/path/to/karaoke.jks  storePassword=...  keyAlias=...  keyPassword=...
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.karaoketv"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.karaoketv"
        minSdk = 24
        targetSdk = 35
        versionCode = 25
        versionName = "3.19"
        buildConfigField("String", "YOUTUBE_API_KEY", "\"${localProps.getProperty("YOUTUBE_API_KEY", "").trim()}\"")
    }

    // Only ship English resources from libraries (smaller APK)
    androidResources {
        localeFilters += listOf("en")
    }

    signingConfigs {
        if (keystoreProps.getProperty("storeFile") != null) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // R8: strips unused code and resources → a much smaller APK
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    // Kotlin follows this too (built-in Kotlin takes its JVM target from targetCompatibility)
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // The Kotlin files live next to the two Java files in src/main/java
    sourceSets {
        named("main") {
            kotlin.directories += "src/main/java"
        }
    }

    packaging {
        resources {
            excludes += listOf(
                "META-INF/*.version", "META-INF/*.kotlin_module", "META-INF/LICENSE*", "META-INF/NOTICE*",
                "kotlin/**", "DebugProbesKt.bin", "**/*.kotlin_builtins",
                // build metadata nobody needs on the device
                "META-INF/version-control-info.textproto", "META-INF/*.kotlin_metadata"
            )
        }
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

dependencies {
    // Wraps YouTube's official embedded player in a WebView. Pure Java/Kotlin, no native code,
    // so it runs on the Shield's 32-bit (armeabi-v7a) and 64-bit userspace alike.
    implementation("com.pierfrancescosoffritti.androidyoutubeplayer:core:13.0.0")
    // Debug builds only: watches for memory leaks and reports them. Not included in release APKs.
    debugImplementation("com.squareup.leakcanary:leakcanary-android:2.14")
}
