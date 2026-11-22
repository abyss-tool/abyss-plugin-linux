import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

// :shizuku —— 唯一授权检测与 shell 上下文（ARC-100 §4：Android 库；
// Shizuku AAR 只进本模块 —— 授权绑定随 M1 Android 侧接线落地，
// 指令模板 / 授权状态机 / 注入防护先行纯 JVM 单测）。
android {
    namespace = "com.abyss.plugin.linux.shizuku"
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
    // Shizuku AAR（uid2000 shell 上下文）—— M1 接线时启用：
    // compileOnly(libs.shizuku.api)
    testImplementation(libs.junit)
}
