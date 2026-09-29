plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kover)
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation(libs.coroutines.core)
    implementation(libs.jsoup)
    implementation(libs.readability4j)
    implementation(libs.serialization.json)
    implementation(libs.okhttp)
    // Android ships XmlPullParser in the platform; on the JVM kxml2 supplies it.
    compileOnly(libs.kxml2)
    testImplementation(libs.kxml2)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
