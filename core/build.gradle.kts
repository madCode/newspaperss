plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kover)
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation(libs.coroutines.core)
    implementation(libs.jsoup)
    // jsoup's nullability annotations are JSpecify's, and jsoup keeps that jar off its
    // consumers' classpath (provided scope). Kotlin needs to read the annotation on an
    // inferred jsoup type, so the compiler has to see it: a warning on 2.3, an error from
    // language version 2.4 (KT-80247). compileOnly, because nothing needs it at runtime.
    compileOnly(libs.jspecify)
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

// A developer tool, not part of the build: see LiveEdition.kt.
tasks.register<JavaExec>("liveEdition") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.app.newspaperss.core.tools.LiveEditionKt")
    workingDir = projectDir
}

// A developer tool, not part of the build: see PlatformCorpus.kt.
tasks.register<JavaExec>("platformCorpus") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.app.newspaperss.core.tools.PlatformCorpusKt")
    workingDir = projectDir
}
