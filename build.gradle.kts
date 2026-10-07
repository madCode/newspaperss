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

// A floor, not a target, kept close to actual. With ~10,500 counted lines, each percentage point
// of slack is about 105 lines that may arrive with no tests at all: at 90% against 94% actual it
// allowed 478, more than three times the whole Listen feature, which is how that feature landed at
// near-zero coverage with every gate green. 93 leaves enough room for an honest refactor and not
// enough for a feature to arrive untested.
//
// This still only measures the total, so it catches a slide rather than new code specifically.
// Requiring the *changed* lines to be covered needs a diff-coverage step in CI; until then the
// uncovered classes worth knowing about are in listen/ and work/, which wrap text-to-speech,
// MediaPlayer and WorkManager and want instrumentation tests rather than a higher number here.
kover {
    reports {
        // Generated code (Room's DAOs, Compose's lambda holders) isn't ours to cover, and counting
        // it would hide a drop in what is.
        filters {
            excludes {
                classes("*_Impl", "*_Impl\$*", "*ComposableSingletons*", "*.BuildConfig")
            }
        }
        verify {
            rule {
                minBound(93)
            }
        }
    }
}
