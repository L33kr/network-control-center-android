plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.github.l33kr.networkcontrolcenter"
    compileSdk = 35
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "io.github.l33kr.networkcontrolcenter"
        minSdk = 24
        targetSdk = 35
        versionCode = 7
        versionName = "0.6.0-alpha.1"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    sourceSets.getByName("main") {
        jniLibs.srcDir("${rootDir}/third_party/tg-ws-proxy-android/app/src/main/jniLibs")
        jniLibs.srcDir(layout.buildDirectory.dir("generated/hevJniLibs"))
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

val hevSourceDir = rootProject.file("third_party/hev-socks5-tunnel")
val hevJniLibsDir = layout.buildDirectory.dir("generated/hevJniLibs")
val hevObjDir = layout.buildDirectory.dir("intermediates/hevNdk/obj")
val hevProjectDir = layout.buildDirectory.dir("intermediates/hevNdk/project")

val buildHevSocks5Tunnel by tasks.registering(Exec::class) {
    group = "build"
    description = "Build the pinned hev-socks5-tunnel shared library with Android NDK"

    inputs.dir(hevSourceDir)
    inputs.file(file("src/main/jni/Application.mk"))
    outputs.dir(hevJniLibsDir)

    doFirst {
        check(hevSourceDir.resolve("Android.mk").exists()) {
            "hev-socks5-tunnel sources are missing. Run scripts/bootstrap-byedpi.* first."
        }
        hevJniLibsDir.get().asFile.mkdirs()
        hevObjDir.get().asFile.mkdirs()
        hevProjectDir.get().asFile.mkdirs()

        val ndkBuild = android.ndkDirectory.resolve(
            if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
                "ndk-build.cmd"
            } else {
                "ndk-build"
            },
        )
        commandLine(
            ndkBuild.absolutePath,
            "-C",
            hevSourceDir.absolutePath,
            "-j${Runtime.getRuntime().availableProcessors().coerceAtLeast(1)}",
            "APP_BUILD_SCRIPT=${hevSourceDir.resolve("Android.mk").absolutePath}",
            "NDK_APPLICATION_MK=${project.file("src/main/jni/Application.mk").absolutePath}",
            "NDK_LIBS_OUT=${hevJniLibsDir.get().asFile.absolutePath}",
            "NDK_OUT=${hevObjDir.get().asFile.absolutePath}",
        )
    }
}

tasks.configureEach {
    if (name.startsWith("merge") && name.endsWith("JniLibFolders")) {
        dependsOn(buildHevSocks5Tunnel)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.compose.ui:ui:1.7.8")
    implementation("androidx.compose.ui:ui-tooling-preview:1.7.8")
    implementation("androidx.compose.material3:material3:1.3.1")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("net.java.dev.jna:jna:5.14.0@aar")
    debugImplementation("androidx.compose.ui:ui-tooling:1.7.8")
    debugImplementation("androidx.compose.ui:ui-test-manifest:1.7.8")
}
