import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

// :session —— RuntimeSession 状态机 / 引用计数 / 限额 / 工作区路径断言
// （ARC-100 §4：纯 JVM；LIN-004 会话生命周期）。
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    api(project(":runtime-api"))
    testImplementation(libs.junit)
}
