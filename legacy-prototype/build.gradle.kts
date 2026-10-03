plugins {
    kotlin("jvm") version "1.9.21"
    id("org.jetbrains.compose") version "1.5.11"
    application
}

group = "com.inkflow"
version = "1.0.0"

repositories {
    google()
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.foundation)
    implementation(compose.material3)
    implementation(compose.ui)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.7.3")
    
    // PDF 渲染
    implementation("org.apache.pdfbox:pdfbox:2.0.29")
    
    // JSON 序列化
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")
    
    // 本地數據庫
    implementation("org.xerial:sqlite-jdbc:3.42.0.0")
}

application {
    mainClass.set("com.inkflow.windows.MainKt")
}

compose.desktop {
    application {
        mainClass = "com.inkflow.windows.MainKt"
        
        nativeDistributions {
            targetFormats(org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi, org.jetbrains.compose.desktop.application.dsl.TargetFormat.Exe)
            packageName = "InkFlow"
            packageVersion = "1.0.0"
            
            windows {
                menuGroup = "InkFlow"
                upgradeUuid = "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
            }
        }
    }
}
