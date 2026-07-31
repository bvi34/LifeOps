plugins {
    alias(libs.plugins.android.application) apply false
    // Declared here too (same AGP artifact as android.application) so :lifeops/:citation can apply
    // it without a version — otherwise Gradle refuses, since AGP is already on the classpath from
    // the line above and it can't re-verify the version request.
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}
