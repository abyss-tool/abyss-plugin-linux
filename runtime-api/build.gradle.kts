import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// 契约模块（DC-101 FR-006）：宿主与消费方共同消费的稳定 ABI ——
// 纯 JVM、零 Android 依赖（FR-001 能力描述 JSON 可纯 JVM 解析并单测）。
// AIDL 接口文件同置于本模块 src/main/aidl（组织内唯一副本，AC-004），
// 由 :app 经 sourceSet 引用编译 —— 禁止拷贝到其它仓。
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}
