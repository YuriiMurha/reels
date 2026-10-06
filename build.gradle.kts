// Declaring the Kotlin plugins here (apply false) puts KGP 2.4.20 on the shared build classpath,
// which overrides the older KGP that AGP 9 bundles for its built-in Kotlin support.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.room) apply false
}
