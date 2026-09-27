plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// A Firebase (FCM) csak akkor aktív, ha az app/google-services.json fájl létezik.
val hasGoogleServices = file("google-services.json").exists()
if (hasGoogleServices) {
    apply(plugin = "com.google.gms.google-services")
}

val runNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "hu.dkc.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "hu.dkc.app"
        minSdk = 26
        targetSdk = 36
        versionCode = runNumber
        versionName = "1.0.$runNumber"

        buildConfigField("String", "BASE_URL", "\"https://dkc.hu\"")
        buildConfigField("String", "HOME_URL", "\"https://dkc.hu/\"")
        buildConfigField("boolean", "FCM_ENABLED", hasGoogleServices.toString())
    }

    signingConfigs {
        create("release") {
            val ks = System.getenv("KEYSTORE_PATH")
            if (ks != null && file(ks).exists()) {
                storeFile = file(ks)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            val rel = signingConfigs.getByName("release")
            if (rel.storeFile != null) signingConfig = rel
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.work:work-runtime-ktx:2.10.2")
    implementation(platform("com.google.firebase:firebase-bom:33.16.0"))
    implementation("com.google.firebase:firebase-messaging")
}
