import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

// :domain-qemu —— QEMU TCG / qcow2 / VNC 回环 / 快照 / 9p 挂载
// （ARC-100 §4：Android 库 + 原生库；qemu-system-aarch64 原生引擎随 M3
// 实装，档位校验 / VNC 策略 / argv 组装先行纯 JVM 单测）。
android {
    namespace = "com.abyss.plugin.linux.domain.qemu"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    api(project(":runtime-api"))
    testImplementation(libs.junit)
}
