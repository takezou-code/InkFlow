pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
        // The Compose Multiplatform Gradle plugin resolves through here. Needed by
        // :desktopApp now that it can no longer declare its own repositories.
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven(url = "https://jitpack.io") {
            content { includeGroup("com.github.styropyr0") }
        }
        maven(url = "https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

rootProject.name = "InkFlow"
include(":app")

// Windows desktop client. Same build as the tablet so a `:shared` module can be
// referenced by both — the whole point of the merge. Previously this was a
// separate Gradle build with its own settings.gradle.kts, which made sharing UI
// impossible: the only option was hand-copying files, and they drifted.
include(":desktopApp")
project(":desktopApp").projectDir = file("desktop-app")

// Cross-platform library: the code both apps must agree on byte for byte —
// the ink format, the sync protocol, the glass material, the motion language.
// A file that lives here cannot drift between the two.
include(":shared")