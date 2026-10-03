// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false

    // Declared here, applied in the desktop module. A module may not carry a
    // plugin version once it is part of a multi-project build — that is why the
    // desktop app could never simply be `include`d before.
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.jetbrains.compose) apply false

    // :shared - the Kotlin Multiplatform library both apps depend on. Tracked by
    // the same `kotlin` version as the tablet, because Kotlin metadata is not
    // forward-compatible between compiler versions.
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
}