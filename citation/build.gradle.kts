import java.security.MessageDigest

plugins {
    // Citation is now a *library* consumed by the Operations Sandbox container app (:app).
    // It keeps its package, reader, and ingestion; it just no longer owns a launcher or Application.
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Citation — the reading surface that ingests content, lets you read it, and pulls notes from it
// while preserving each note's source. It is hosted inside the Operations Sandbox container app
// (:app) alongside LifeOps; the two still talk only over the sync seam in :core. Everything
// framework-independent (content model, keys, dedup, EPUB parse, anchors, notes, sync) lives in
// :core and is JVM-tested; this module adds Room storage, the Compose reader, and WorkManager
// ingestion jobs on top.
android {
    namespace = "com.citation.app"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        // Code shrinking is the consuming app's (:app) responsibility.
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    buildFeatures {
        compose = true
    }

    sourceSets {
        getByName("main") {
            // The native voice runtime, fetched above rather than committed.
            jniLibs.srcDir(layout.buildDirectory.dir("sherpa-onnx/jniLibs"))
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// --- The on-device neural voice's native runtime ------------------------------------------------
//
// sherpa-onnx ships its Android libraries as a tarball of `.so` files rather than to a Maven
// repository, and they are 25 MB per ABI — too much to carry in the repository, and not something a
// build should silently do without. So the small Java API (188 KB, in `libs/`) is committed and the
// natives are fetched here, pinned by version *and* checksum.
//
// The fetch is deliberately **optional**. If it is skipped or the download fails, the module still
// compiles — the API classes come from the committed jar — and at runtime `System.loadLibrary`
// fails, which `NeuralSynthesizers`/`Narrator` already treat as "no neural runtime" and answer by
// reading with the platform voice. A build without network gives you a working reader, not a broken
// one. Pass `-Pcitation.skipNativeVoices` to skip it explicitly.
val sherpaVersion = "1.13.7"
val sherpaSha256 = "51e47621f53ed60e0c669afc8269964243f189845faa8a583ad6c0a810c8d1fb"
val sherpaAbis = listOf("arm64-v8a", "armeabi-v7a")
val sherpaJniDir = layout.buildDirectory.dir("sherpa-onnx/jniLibs")

val fetchNeuralVoiceRuntime by tasks.registering {
    description = "Downloads the sherpa-onnx Android native libraries the neural voice needs."
    val marker = layout.buildDirectory.file("sherpa-onnx/$sherpaVersion.ok")
    outputs.file(marker)
    outputs.dir(sherpaJniDir)
    onlyIf { !project.hasProperty("citation.skipNativeVoices") }
    doLast {
        val out = sherpaJniDir.get().asFile
        val archive = File(temporaryDir, "sherpa-onnx-v$sherpaVersion-android.tar.bz2")
        val url = "https://huggingface.co/csukuangfj2/sherpa-onnx-libs/resolve/main/" +
            "sherpa-onnx-v$sherpaVersion-android.tar.bz2"
        try {
            if (!archive.isFile || sha256(archive) != sherpaSha256) {
                logger.lifecycle("Fetching the neural voice runtime (~45 MB, once per version)…")
                archive.parentFile.mkdirs()
                uri(url).toURL().openStream().use { input ->
                    archive.outputStream().use { output -> input.copyTo(output) }
                }
            }
            val actual = sha256(archive)
            check(actual == sherpaSha256) {
                "sherpa-onnx $sherpaVersion did not verify (expected $sherpaSha256, got $actual)"
            }
            out.deleteRecursively()
            out.mkdirs()
            // Only the two libraries the Java API actually loads; the c-api/cxx-api pair in the
            // archive is for native callers and would add megabytes to every APK for nothing.
            val wanted = sherpaAbis.flatMap { abi ->
                listOf("./jniLibs/$abi/libsherpa-onnx-jni.so", "./jniLibs/$abi/libonnxruntime.so")
            }.toSet()
            providers.exec {
                commandLine(listOf("tar", "-xjf", archive.absolutePath, "-C", out.absolutePath, "--strip-components=2") + wanted)
            }.result.get().assertNormalExitValue()
            marker.get().asFile.writeText(sherpaSha256)
        } catch (e: Exception) {
            // Never fail the build over this: the reader works without a neural voice.
            logger.warn("Could not fetch the neural voice runtime; Citation will read with the " +
                "device's own speech engine instead. (${'$'}{e.message})")
            out.mkdirs()
            marker.get().asFile.writeText("skipped")
        }
    }
}

fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { stream ->
        val buffer = ByteArray(1 shl 16)
        while (true) {
            val read = stream.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { b: Byte -> "%02x".format(b) }
}

tasks.named("preBuild") { dependsOn(fetchNeuralVoiceRuntime) }

dependencies {
    // The sherpa-onnx Java API. Committed rather than fetched because it is small and because a
    // failure to reach a host must never be a failure to compile.
    implementation(files("libs/sherpa-onnx-jvm-$sherpaVersion.jar"))

    // The suite's shared appearance — one theme, one store, this app's colour identity in it.
    implementation(project(":suiteui"))
    implementation(project(":core"))
    // The Operations Sandbox backup format/engine (pure JVM). Citation supplies a BackupContributor.
    implementation(project(":backupkit"))
    // The vault contract (pure JVM). Citation mirrors its catalogue sign-ins and its library card
    // into the Secrets vault and reads through to it when its own Keystore-backed store comes up
    // empty — which on a restored phone is always. The dependency is on the *contract*, never on
    // :secrets; the implementation is found through a registration the sandbox makes at start-up.
    implementation(project(":vaultkit"))

    // PDF text extraction for the reflow track (see data/pdf/PdfPageText). The same PDFBox-Android
    // port :logistics already uses for the Walmart order import, so the app carries it either way.
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.gson)
    // Encrypted-at-rest storage (Android Keystore) for the library card + PIN.
    implementation(libs.androidx.security.crypto)
    debugImplementation(libs.androidx.ui.tooling)

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
