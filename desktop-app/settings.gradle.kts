pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

// Gradle toolchain 自動取得：這台機器只有 Android Studio 內建的 JDK 25，
// 但 build.gradle.kts 宣告 jvmToolchain(17)，沒有這行會直接
// "Cannot find a Java installation ... matching: {languageVersion=17}" 失敗。
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

rootProject.name = "InkFlowDesktop"
