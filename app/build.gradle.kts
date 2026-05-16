plugins {
    id("com.android.application")
}

android {
    namespace = "dev.canxin.homescreenlayoutstudio"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.canxin.homescreenlayoutstudio"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            merges += "META-INF/xposed/*"
        }
    }
}

configurations.configureEach {
    resolutionStrategy.force(
        "androidx.emoji2:emoji2:1.3.0",
        "androidx.emoji2:emoji2-views-helper:1.3.0",
        "androidx.fragment:fragment:1.5.4",
        "androidx.recyclerview:recyclerview:1.1.0"
    )
}

dependencies {
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.emoji2:emoji2:1.3.0")
    implementation("androidx.emoji2:emoji2-views-helper:1.3.0")
    implementation("androidx.fragment:fragment:1.5.4")
    implementation("androidx.recyclerview:recyclerview:1.1.0")
    compileOnly("io.github.libxposed:api:101.0.1")
}
