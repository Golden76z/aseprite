plugins {
    id("com.android.application")
}

val repositoryRoot = rootProject.projectDir.parentFile
val hostBuildDir = rootProject.layout.buildDirectory.dir("host-tools")
val skiaDir = providers.gradleProperty("aseprite.skiaDir")
    .orElse(repositoryRoot.resolve(".deps/skia").absolutePath)
val skiaLibraryDir = providers.gradleProperty("aseprite.skiaLibraryDir")
    .orElse(skiaDir.map { "$it/out/android-arm64" })

// Ship the same source data used by CMake copy_data, plus its license documents.
val runtimeAssets = layout.buildDirectory.dir("generated/runtimeAssets")
val prepareRuntimeAssets by tasks.registering(Sync::class) {
    into(runtimeAssets)
    from(repositoryRoot.resolve("data")) { into("runtime/data") }
    from(repositoryRoot) {
        include("README.md", "AUTHORS.md", "EULA.txt", "docs/LICENSES.md")
        into("runtime/data")
    }
    from(skiaDir.map { "$it/third_party/externals/icu/flutter/icudtl.dat" }) { into("runtime") }
    doLast {
        val root = runtimeAssets.get().asFile
        val runtime = root.resolve("runtime")
        root.resolve("runtime-files.txt").writeText(
            runtime.walkTopDown().filter { it.isFile }
                .map { it.relativeTo(runtime).invariantSeparatorsPath }
                .sorted().joinToString("\n", postfix = "\n")
        )
    }
}

android {
    buildFeatures { buildConfig = true }
    sourceSets.getByName("main").assets.directories.add(runtimeAssets.get().asFile.absolutePath)
    namespace = "org.aseprite.android"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "org.aseprite.android"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1-build-milestone"
        ndk {
            abiFilters += "arm64-v8a"
        }
        externalNativeBuild {
            cmake {
                targets += "aseprite"
                arguments += listOf(
                    "-DGEN_EXE=${hostBuildDir.get().file("bin/gen").asFile.absolutePath}",
                    "-DSKIA_DIR=${file(skiaDir.get()).absolutePath}",
                    "-DSKIA_LIBRARY_DIR=${file(skiaLibraryDir.get()).absolutePath}",
                    "-DANDROID_STL=c++_shared",
                    "-DCMAKE_EXPORT_COMPILE_COMMANDS=ON",
                    "-DLAF_BACKEND=skia",
                    "-DLAF_WITH_CLIP=ON",
                    "-DLAF_WITH_EXAMPLES=OFF",
                    "-DENABLE_TESTS=OFF",
                    "-DENABLE_BENCHMARKS=OFF",
                    "-DENABLE_NEWS=OFF",
                    "-DENABLE_UPDATER=OFF",
                    "-DENABLE_DRM=OFF",
                    "-DENABLE_SCRIPTING=OFF",
                    "-DENABLE_WEBSOCKET=OFF",
                    "-DENABLE_STEAM=OFF",
                    "-DENABLE_SENTRY=OFF",
                    "-DENABLE_DESKTOP_INTEGRATION=OFF",
                    "-DENABLE_QT_THUMBNAILER=OFF",
                    "-DENABLE_I18N_STRINGS=OFF",
                    "-DENABLE_TRIAL_MODE=OFF",
                )
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = repositoryRoot.resolve("CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

val sdkDirectory = androidComponents.sdkComponents.sdkDirectory
val configureHostGen by tasks.registering(Exec::class) {
    group = "build"
    description = "Configure the Linux host code generator without the Android toolchain"
    doFirst {
        val cmakeBin = sdkDirectory.get().dir("cmake/3.22.1/bin").asFile
        commandLine(
            cmakeBin.resolve("cmake"),
            "-S", rootProject.file("host-tools"),
            "-B", hostBuildDir.get().asFile,
            "-G", "Ninja",
            "-DCMAKE_MAKE_PROGRAM=${cmakeBin.resolve("ninja")}",
            "-DCMAKE_BUILD_TYPE=Release",
        )
    }
}

val buildHostGen by tasks.registering(Exec::class) {
    group = "build"
    description = "Build gen for Linux before configuring any Android native variant"
    dependsOn(configureHostGen)
    doFirst {
        commandLine(
            sdkDirectory.get().file("cmake/3.22.1/bin/cmake").asFile,
            "--build", hostBuildDir.get().asFile,
            "--target", "gen", "--parallel", "4",
        )
    }
}

tasks.configureEach {
    if (name.startsWith("merge") && name.endsWith("Assets")) {
        dependsOn(prepareRuntimeAssets)
    }
    if (name.startsWith("configureCMake")) {
        dependsOn(buildHostGen)
    }
}
