// abyss-plugin-linux 根构建脚本 —— 版本统一收敛在 gradle/libs.versions.toml
// （与主仓 Abyss-Tools 同源矩阵：AGP 8.13.2 / Kotlin 2.3.21 / JDK 17）。
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.parcelize) apply false
}
