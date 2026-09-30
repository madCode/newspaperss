plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.kover)
}

dependencies {
    kover(project(":app"))
    kover(project(":core"))
}

// A floor, not a target: coverage was 92.5% of lines when it was set, so this only fails a
// change that lets it slide.
kover {
    reports {
        verify {
            rule {
                minBound(90)
            }
        }
    }
}
