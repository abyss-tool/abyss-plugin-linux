import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// :asset —— abyss-asset-v1 清单解析 / 分卷双层校验 / 断点续传 / 配额 LRU
// （ARC-100 §4：纯 JVM；Android 下载实现随 M1 接线注入，LIN-003）。
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    api(project(":runtime-api"))
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}
