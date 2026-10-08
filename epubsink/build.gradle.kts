// A second app, so a device test can prove another process can read an edition through the
// FileProvider. It is never shipped: only the device-test job installs it.
plugins { alias(libs.plugins.android.application) }

android {
    namespace = "com.app.newspaperss.epubsink"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.app.newspaperss.epubsink"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}
