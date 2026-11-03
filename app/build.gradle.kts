plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "cl.shareflow.app"
    compileSdk = 36
    defaultConfig {
        applicationId = "cl.shareflow.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0-beta"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
