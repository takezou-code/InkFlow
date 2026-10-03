plugins {
    // Versions live in the root build's plugin block; a module inside a
    // multi-project build must not declare its own.
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

group = "com.vic.inkflow"
version = "1.0-SNAPSHOT"

// No `repositories { }` block here: the root settings declares them with
// FAIL_ON_PROJECT_REPOS, so a module-level block is an error rather than a
// convenience. The Compose Multiplatform repository the desktop build needed is
// now declared centrally in settings.gradle.kts.

dependencies {
    implementation(project(":shared"))
    // Kotlin Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
    
    // Compose Desktop
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.foundation)
    implementation(compose.materialIconsExtended)
    
    // PDFBox for PDF reading/writing
    implementation("org.apache.pdfbox:pdfbox:3.0.8")

    // JPEG 2000 decoder. PDFBox treats JPX as an optional plug-in and, without
    // this, silently renders those images as blank:
    //
    //   ERROR o.a.p.c.PDFStreamEngine - Cannot read JPEG2000 image:
    //   Java Advanced Imaging (JAI) Image I/O Tools are not installed
    //
    // Scanner PDFs (anything produced by a print-to-PDF or a scan workflow) use
    // JPX heavily, so without this a large share of real documents loses its
    // page content while the PDF "opens fine".
    implementation("com.github.jai-imageio:jai-imageio-jpeg2000:1.4.0")
    
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

        // PDFBox tries to release the direct ByteBuffer it rasterises pages into
        // by reaching `jdk.internal.ref.Cleaner` through MethodHandles. On JDK 17
        // that lookup is denied and PDFBox logs "Unmapping is not supported." once
        // per JVM, then continues — rendering is unaffected and the GC still
        // reclaims the buffer, only the eager unmap is lost.
        //
        // Kept even though it does NOT fix that message (the lookup is on
        // jdk.internal.ref, not java.nio): it is the access PDFBox needs for the
        // other direct-buffer paths, and it costs one JVM flag. The noisy log is
        // silenced in logback.xml instead, where the reason is recorded.
        jvmArgs += "--add-opens=java.base/java.nio=ALL-UNNAMED"
        
        nativeDistributions {
            packageName = "InkFlow"
            packageVersion = "1.0.0"

            // Compose's default runtime image is trimmed to what it infers we use,
            // and it infers wrong here. Verified by inspecting `runtime/lib/modules`
            // in the packaged output: both modules were absent, and both kill the
            // packaged app at startup while `gradlew run` (full JDK) is perfectly
            // happy — which is why this only ever showed up in the EXE.
            //
            //  - `java.sql`: without it, `NoClassDefFoundError: java/sql/SQLException`
            //    the moment the SQLite driver is touched, before any window appears.
            //  - `java.naming`: logback reads `logback.xml` through
            //    `JoranConfigurator`, which instantiates JNDI-backed model handlers.
            //    Without the module it is not degraded logging, it is
            //    `ClassNotFoundException: javax.naming.NamingException` ->
            //    "Failed to launch JVM", during class init, before main() runs.
            //
            // `java.logging` because PDFBox and the JRE's own logging bridge expect
            // it; cheap insurance.
            modules("java.sql", "java.naming", "java.logging")

            windows {
                menuGroup = "InkFlow"
                upgradeUuid = "a1b2c3d4-e5f6-7890-abcd-ef1234567890"

                // Without this the EXE, taskbar entry and Alt-Tab all show
                // Compose's default icon, which makes the app look uninstalled
                // next to every other pinned program.
                iconFile.set(layout.projectDirectory.file("src/main/resources/inkflow.ico"))
            }
        }
        
        buildTypes.release {
            proguard {
                isEnabled = false
            }
        }
    }
}

// Helper task: print runtime classpath (used by the E2E sync test script)
tasks.register("printRuntimeClasspath") {
    doLast {
        println(configurations.getByName("runtimeClasspath").files.joinToString(File.pathSeparator))
    }
}

/**
 * jpackage writes `InkFlow.cfg` into `app/` but the launcher looks for it next to
 * `InkFlow.exe`, one level up, so the packaged app dies immediately with
 *
 *   Error opening "...\InkFlow\app\InkFlow.cfg" file: No such file or directory
 *
 * Copying the file up is not enough on its own: its classpath entries are
 * `$APPDIR\<jar>`, and $APPDIR now means the image root while the jars actually
 * live in `app/`, so every entry has to be re-rooted or the launcher finds a
 * config and then loads none of the code.
 *
 * Cheap and deterministic to fix here rather than hunting for a jpackage flag the
 * Compose plugin does not expose.
 *
 * The directory is read from the **task's own project** inside `doLast`, not
 * captured into a script-level `val`. Inside `afterEvaluate` the script instance
 * is already gone, so both of these fail:
 *   - `layout.buildDirectory` -> "this.$this_afterEvaluate is null"
 *   - a top-level `val`       -> "this.this$0 is null"
 * At execution time `Task.project` is valid, so that is the only reference that
 * survives.
 */
afterEvaluate {
    // Appended to the packaging task itself rather than run as a separate task:
    // writing into the app-image directory from a *separate* task makes Gradle
    // treat that file as an unexpected output and fail the build with
    // "Failed to clean up output files for task ':createDistributable'".
    tasks.findByName("createDistributable")?.doLast {
        val appDir = project.layout.buildDirectory
            .dir("compose/binaries/main/app/InkFlow").get().asFile
        val source = File(appDir, "app/InkFlow.cfg")
        val target = File(appDir, "InkFlow.cfg")
        if (!source.exists()) {
            throw GradleException(
                "expected jpackage to produce ${source.absolutePath}; " +
                    "the app-image layout changed and this fixup needs updating"
            )
        }
        // Rewrite $APPDIR -> $APPDIR\app so the classpath points at the jars,
        // which live one level down from the launcher.
        target.writeText(
            source.readText().replace("\$APPDIR", "\$APPDIR\\app"),
            Charsets.US_ASCII
        )
        logger.lifecycle("placed ${target.name} next to the launcher")
    }
}
