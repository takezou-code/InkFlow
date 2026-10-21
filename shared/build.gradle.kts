import com.android.build.api.withAndroid

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

        // Host tests are **off by default** in AGP 9's KMP library plugin, and the
        // build says so:
        //
        //   WARNING: The 'commonTest' source directory exists, but android host
        //   tests are not enabled. To enable android host tests, add
        //   `withHostTest {}` to your android target configuration.
        //
        // Without this, `commonTest` only ever ran in the desktop compilation —
        // the protocol's golden vectors were verified on one platform and
        // assumed to hold on the other. "Assumed to hold" is exactly the failure
        // mode this module exists to prevent.
        withHostTest {
            isIncludeAndroidResources = true
        }
    }

    // `jvmCommon` — the source set both JVM targets share.
    //
    // Both targets of this module are JVMs (Android and the desktop), so
    // `java.io` and `java.security` are available on both. The sync protocol's
    // byte-level layer — the length-prefixed frame, the SHA-256 helpers — used
    // to be hand-copied into both apps, and the copies had already drifted: the
    // tablet enforced the 512 MiB frame cap and reported it as an IOException,
    // the desktop used `require()` and had no cap check on the write side.
    //
    // Declaring the layer once in a source set that both leaves depend on
    // makes "the two ends agree" a property of the build rather than a promise
    // in a comment.
    //
    // WHY THIS IS DECLARED THROUGH THE HIERARCHY TEMPLATE AND NOT BY HAND:
    // a manual `dependsOn` edge cancels the default hierarchy template
    // outright — the build says so on every run:
    //
    //   w: Default Kotlin Hierarchy Template Not Applied Correctly
    //   [androidMain, desktopMain, jvmCommon]
    //
    // and the price is paid later: the template no longer creates the
    // `iosMain`/`nativeMain` groups a future target would need, and the test
    // source sets have to be wired by hand as well.
    //
    // Kotlin's own hierarchy template does not cover "JVM + Android" (the
    // combination this project has) — but the template DSL can be extended
    // with a group of exactly those two targets, which is what
    // `group("jvmCommon") { withJvm(); withAndroid() }` does. `withAndroid()`
    // is AGP's extension for this (com.android.build.api.withAndroid, AGP
    // 9.3.2+); the official AGP example is the same shape.
    applyDefaultHierarchyTemplate {
        common {
            group("jvmCommon") {
                withJvm()
                withAndroid()
            }
        }
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

            // Haze 2.0.1 — the same version `:app` already uses, deliberately.
            //
            // Haze 1.x was Android-only in practice (real backdrop blur via
            // `RenderEffect`), which is why `:shared/ui/Glass.kt` originally carried
            // only the faux path and the desktop had to hand-port it. Haze 2.x runs on
            // Skia, so the *real* refraction path is multiplatform too — which means
            // the desktop can stop approximating the tablet's primary material.
            //
            // 2.0.1 is the version that wants Kotlin 2.4.20+; taking it together
            // with the repo-wide compiler bump to 2.4.21 (2026-10) means the bump
            // happens once instead of twice.
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
            // The outlined icon set (Article, Undo, Redo, Folder, Bookmark, …) used by
            // the library panels and editor chrome. Same slow-cadence caveat as core:
            // 1.7.3 is the newest published line.
            implementation(
                "org.jetbrains.compose.material:material-icons-extended:" +
                    libs.versions.composeMaterialIcons.get()
            )
        }
        androidMain.dependencies {
            implementation(libs.androidx.core.ktx)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}