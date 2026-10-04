plugins {
    alias(libs.plugins.kotlin.multiplatform)
    // AGP 9 refuses `com.android.library` alongside the KMP plugin; the replacement
    // is the Android-maintained KMP library plugin. Applying the old one fails with
    // "The 'com.android.library' plugin is not compatible with the
    // 'org.jetbrains.kotlin.multiplatform' plugin since AGP 9.0".
    alias(libs.plugins.android.kotlin.multiplatform.library)
    // The Compose compiler plugin is version-locked to `kotlin`. Without it any
    // @Composable in this module fails to compile.
    alias(libs.plugins.kotlin.compose)
}

// `:shared` exists so the tablet and the desktop stop being two apps that
// hand-copy each other's files.
//
// The cost of that duplication, measured rather than assumed:
//   - `EnvelopeUtils.kt` is 129 lines on the tablet and 135 on the desktop, hand
//     copied. Proving they matched needed a differential test that re-compiles the
//     tablet's algorithm inside the desktop's test source set and compares 253 real
//     synced strokes point by point.
//   - `StrokeEntity.kt` had already drifted: 82 lines on the tablet vs 48 on the
//     desktop, with different columns.
//
// A file that exists once cannot drift. commonMain is for anything both platforms
// can express; what genuinely cannot be shared (haze's real refraction, SAF, Room,
// WebView) stays in the platform modules behind an interface declared here.

kotlin {
    // Must equal the tablet's Kotlin. Metadata is not forward-compatible, so a skew
    // here is not "mostly working", it is unreadable.
    jvmToolchain(17)

    // Named "desktop", not "jvm", so the two JVM-ish artifacts cannot collide in
    // dependency resolution and the intent stays readable.
    jvm("desktop")

    // `android { }` — NOT `androidTarget()`. AGP 9's KMP library plugin replaces the
    // old target outright: passing androidTarget() fails with "Enabled androidTarget()
    // target is not compatible with 'com.android.kotlin.multiplatform.library'
    // plugin". The DSL is also flatter than the AGP library plugin — `compileSdk`,
    // `minSdk` and `namespace` are direct properties with no `defaultConfig { }`
    // wrapper. See https://kotl.in/gradle/agp-new-kmp
    android {
        namespace = "com.vic.inkflow.shared"
        compileSdk = 37
        minSdk = 32
    }

    sourceSets {
        commonMain.dependencies {
            // Explicit coordinates rather than the `compose.xxx` shortcuts: those
            // resolve to plain Strings and are deprecated at ERROR level with
            // "Specify dependency directly".
            //
            // `material3` is pinned to its own version because CMP publishes it on a
            // slower cadence — at CMP 1.12.1 there is no stable material3 1.12.1.
            // See the note on `composeMaterial3` in gradle/libs.versions.toml.
            val cmp = libs.versions.composeMultiplatform.get()
            implementation("org.jetbrains.compose.runtime:runtime:$cmp")
            implementation("org.jetbrains.compose.ui:ui:$cmp")
            implementation("org.jetbrains.compose.foundation:foundation:$cmp")
            // `Motion.kt` needs this and nothing else: Spring and spring() are the
            // entire surface it touches.
            implementation("org.jetbrains.compose.animation:animation-core:$cmp")
            implementation(
                "org.jetbrains.compose.material3:material3:" +
                    libs.versions.composeMaterial3.get()
            )

            // Haze 2.0.0 — the same version `:app` already uses, deliberately.
            //
            // Haze 1.x was Android-only in practice (real backdrop blur via
            // `RenderEffect`), which is why `:shared/ui/Glass.kt` originally carried
            // only the faux path and the desktop had to hand-port it. Haze 2.x runs on
            // Skia, so the *real* refraction path is multiplatform too — which means
            // the desktop can stop approximating the tablet's primary material.
            //
            // Not bumping Kotlin for this: 2.0.0 is already built against the 2.4.x
            // line and `:app` compiles on 2.4.10 with it. Only 2.0.1 wanted 2.4.20,
            // and taking that would have meant a repo-wide compiler bump for one
            // patch release.
            implementation(libs.haze)
            implementation(libs.haze.blur)
            implementation(libs.haze.glass)

            // GlassSurface.kt's dialog and chip components reference `Icons`, which
            // lives in the multiplatform icons artifact. The AndroidX one is
            // Android-only, so it cannot be declared here.
            implementation(
                "org.jetbrains.compose.material:material-icons-core:" +
                    libs.versions.composeMaterialIcons.get()
            )
        }
        androidMain.dependencies {
            implementation(libs.androidx.core.ktx)
        }
    }
}