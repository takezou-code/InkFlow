plugins {
    kotlin("jvm") version "2.0.21"
    id("org.jetbrains.compose") version "1.7.3"
    kotlin("plugin.compose") version "2.0.21"
}

group = "com.vic.inkflow"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    google()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

dependencies {
    // Kotlin Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
    
    // Compose Desktop
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.foundation)
    implementation(compose.materialIconsExtended)
    
    // PDFBox for PDF reading/writing
    implementation("org.apache.pdfbox:pdfbox:3.0.4")
    
    // JSON serialization
    implementation("com.google.code.gson:gson:2.14.0")
    
    // SQLite database
    implementation("org.xerial:sqlite-jdbc:3.46.0.0")
    
    // Logging
    implementation("io.github.microutils:kotlin-logging-jvm:3.0.5")
    implementation("ch.qos.logback:logback-classic:1.5.7")
    
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(17)
}

compose.desktop {
    application {
        mainClass = "com.vic.inkflow.MainKt"
        
        nativeDistributions {
            packageName = "InkFlow"
            packageVersion = "1.0.0"
            windows {
                menuGroup = "InkFlow"
                upgradeUuid = "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
            }
        }
        
        buildTypes.release {
            proguard {
                isEnabled = false
            }
        }
    }
}
