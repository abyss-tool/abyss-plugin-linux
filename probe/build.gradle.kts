import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

// :probe —— ptrace / memlock / arch / 存储 / libc 探测与降级决策
// （ARC-100 §4：纯 JVM + JNI 探测；JNI 随 M1 实装，决策先行纯 JVM 单测）。
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    api(project(":runtime-api"))
    testImplementation(libs.junit)
}
