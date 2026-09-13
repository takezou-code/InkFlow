plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.vic.inkflow"
    compileSdk {
        version = release(37) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.vic.inkflow"
        minSdk = 32
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Release signing from local.properties (gitignored). If the keystore is absent
    // (e.g. fresh clone), release builds stay unsigned rather than failing config.
    // (Manual parse: java.util.* is not on the script classpath.)
    val ksMap: Map<String, String> = rootProject.file("local.properties")
        .takeIf { it.exists() }
        ?.readLines()
        .orEmpty()
        .mapNotNull { line ->
            val i = line.indexOf('=')
            if (i <= 0) null else line.substring(0, i).trim() to line.substring(i + 1).trim()
        }
        .toMap()
    val ksFile = rootProject.file(ksMap["inkflow.storeFile"] ?: "release.pfx")
    signingConfigs {
        if (ksFile.exists() && ksMap.containsKey("inkflow.storePassword")) {
            create("release") {
                storeFile = ksFile
                storePassword = ksMap["inkflow.storePassword"]
                keyAlias = ksMap["inkflow.keyAlias"] ?: "inkflow"
                keyPassword = ksMap["inkflow.keyPassword"]
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    // Navigation
    implementation(libs.androidx.navigation.compose)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // DataStore
    implementation(libs.androidx.datastore.preferences)

    // WorkManager (periodic auto backup)
    implementation(libs.androidx.work.runtime.ktx)

    // Gson
    implementation(libs.gson)

    // PDFBox
    implementation(libs.pdfbox.android)

    // Liquid-glass backdrop blur
    implementation(libs.haze)

    // 真折射玻璃試點（PrismalAGSL, JitPack）：只給重點面板，小元件維持 haze
    implementation(libs.prismal)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

