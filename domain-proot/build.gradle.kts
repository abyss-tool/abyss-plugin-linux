import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

// :domain-proot —— PRoot 引擎嵌入 / Alpine rootfs 解包 / 启动链路
// （ARC-100 §4：Android 库 + 原生库；proot-distro-nolib 原生库随 M1
// 资产管线实装，argv 组装 / 会话环境先行纯 JVM 单测）。
android {
    namespace = "com.abyss.plugin.linux.domain.proot"
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
