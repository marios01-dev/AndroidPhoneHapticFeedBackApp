plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.smartwatchhapticsystem"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.smartwatchhapticsystem"
        minSdk = 30
        targetSdk = 34
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
}

dependencies {
    implementation(libs.appcompat)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)

    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)

    implementation(libs.play.services.location)

    implementation("com.android.volley:volley:1.2.1")
}
