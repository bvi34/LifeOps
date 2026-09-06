plugins {
    // Advisor — the suite's private, on-device assistant. Like LifeOps, Citation and Logistics it is
    // a *library* module consumed by the Operations Sandbox container app (:app); it owns no launcher
    // or Application of its own. It is the suite's RAG layer: it reads the other apps' own data (in
    // the same process, gated by per-app permissions the user grants) and answers questions grounded
    // in that data. The language model itself is a **placeholder** today — a small local model
    // (~2–4B params, GGUF/Q4) is the intended drop-in. Everything runs offline; there is no network.
    //
    // Its retrieval/permission/prompt logic lives, framework-free, under `logic/` and is JVM
    // unit-tested (no emulator), the same discipline as :backupkit and Citation's :core.
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Opt-in native build: the on-device Qwen3-4B backend (llama.cpp, libadvisor-llm.so) is compiled only
// when `-Padvisor.buildNativeLlm=true` is passed (or set in gradle.properties). It needs the Android
// NDK + CMake and fetches llama.cpp at configure time, so it stays off by default — a plain build
// ships without the .so and Advisor falls back to its deterministic placeholder engine at runtime.
val buildNativeLlm = (providers.gradleProperty("advisor.buildNativeLlm").orNull ?: "false").toBoolean()

// Opt-in GPU offload: build ggml's Vulkan backend and offload the model's layers to the device GPU
// (e.g. an Adreno). OFF by default because it adds host build-tool requirements (glslc + a host C++
// compiler for llama.cpp's vulkan-shaders-gen) that a plain CPU build doesn't need — so enabling it
// can't silently break the working CPU build. Requires advisor.buildNativeLlm too.
val buildGpu = (providers.gradleProperty("advisor.gpu").orNull ?: "false").toBoolean()

// The ARM ISA baseline ggml is compiled against. Android is a cross-compile, so ggml can't probe the
// device and — left alone — emits no `-march` at all, falling back to plain armv8-a kernels without
// dotprod/i8mm. That costs several times the prefill speed on a modern phone. The default targets
// ARMv8.6-class cores (Snapdragon 8 Gen 1 / 8+ Gen 1 and newer); override it for older arm64 hardware,
// where an unsupported extension is a SIGILL rather than a graceful fallback:
//   -Padvisor.cpuArch=armv8.2-a+dotprod+fp16
val advisorCpuArch = providers.gradleProperty("advisor.cpuArch").orNull ?: "armv8.2-a+dotprod+i8mm+fp16"

// Load the weights with mmap (file-backed, evictable) instead of reading them into anonymous memory.
// Off by default: file-backed weights are what the kernel drops first under pressure, and a phone that
// re-reads 2.3 GiB from flash per forward pass never finishes a prompt. -Padvisor.mmap=true to compare.
val advisorMmap = (providers.gradleProperty("advisor.mmap").orNull ?: "false").toBoolean()

android {
    namespace = "com.advisor.app"
    compileSdk = 35

    // Pin the NDK only when someone asks for a specific one. Left unset — the normal case on a
    // developer machine — AGP uses whatever NDK it defaults to and installs it if it has to, so a
    // local build is unaffected by this line. CI *does* pin it (`-Padvisor.ndkVersion=...`), because
    // a runner image's default NDK changes without warning and llama.cpp is exactly the kind of
    // code that notices: a release has to be built with the toolchain that was tested.
    providers.gradleProperty("advisor.ndkVersion").orNull?.let { ndkVersion = it }

    defaultConfig {
        minSdk = 26
        // Code shrinking is the consuming app's (:app) responsibility — but two things in this module
        // are reached only from native code, by name, and a shrinker cannot see that. These rules
        // travel with the module so :app keeps them without having to know why.
        consumerProguardFiles("consumer-rules.pro")

        if (buildNativeLlm) {
            // A 4B Q4 model is only realistic on 64-bit ARM; don't bloat other ABIs with the weights' runtime.
            ndk { abiFilters += "arm64-v8a" }
            externalNativeBuild {
                cmake {
                    // Both languages: ggml's hot compute is C, and `cppFlags` covers C++ only.
                    // CMakeLists pins the real optimization level via add_compile_options (which
                    // clang sees last); these are the baseline it builds on.
                    cppFlags += "-O2"
                    cFlags += "-O2"
                    // Hand the GPU choice to CMakeLists (which turns on GGML_VULKAN + a build-time offload flag).
                    arguments += "-DADVISOR_GPU=${if (buildGpu) "ON" else "OFF"}"
                    // Hand the ISA baseline to CMakeLists (which forwards it to ggml's GGML_CPU_ARM_ARCH).
                    arguments += "-DADVISOR_CPU_ARCH=$advisorCpuArch"
                    arguments += "-DADVISOR_MMAP=${if (advisorMmap) "ON" else "OFF"}"
                }
            }
        }
    }

    if (buildNativeLlm) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "3.22.1"
            }
        }
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
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    // The suite's shared appearance — one theme, one store, this app's colour identity in it.
    implementation(project(":suiteui"))
    // The other suite apps: Advisor's knowledge sources read their databases (same process) to build
    // the retrieval corpus. It never writes to them — it is a read-only consumer of their data.
    implementation(project(":lifeops"))
    implementation(project(":citation"))
    implementation(project(":logistics"))
    implementation(project(":health"))
    implementation(project(":people"))
    implementation(project(":project"))
    implementation(project(":maintenance"))
    implementation(project(":repository"))
    // The Operations Sandbox backup format/engine (pure JVM). Advisor supplies a BackupContributor
    // for its own store (granted permissions + saved conversations).
    implementation(project(":backupkit"))

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
    implementation(libs.kotlinx.coroutines.android)
    // Identity-based data is persisted as a portable JSON file (IdentityStore), read/written here.
    implementation(libs.gson)
    debugImplementation(libs.androidx.ui.tooling)

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
