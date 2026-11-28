import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.parcelize)
}

android {
    namespace = "com.abyss.plugin.linux"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.abyss.plugin.linux"
        minSdk = 26
        // targetSdk 钉 28（与主壳同款纪律）：Linux 引擎是 Termux 血统的
        // 执行类应用 —— untrusted_app_29+ 域的 W^X 强制会击杀 PRoot 的
        // execve。本项目经 GitHub Releases 侧载分发，不受 Play 政策约束
        //（详见主仓 docs/ANDROID_COMPAT.md / N-CMP-001 / SEC-007）。
        targetSdk = 28
        versionCode = 2
        versionName = "0.3.0"
    }

    // ── AIDL 契约编译：单一事实源在 :runtime-api/src/main/aidl ──────────
    // 本模块只编译不拷贝（DC-101 FR-006 / AC-004：ILinuxRuntime.aidl 在
    // 组织内仅存在于 :runtime-api）。宿主与构建器未来以 compileOnly
    // 构件消费同一目录。
    sourceSets.getByName("main").aidl.srcDir("../runtime-api/src/main/aidl")

    buildFeatures {
        aidl = true
    }

    // ── 发布签名（M1 接入 secrets 后生效；本仓 skeleton 期不持有材料）──
    // CI：GitHub Actions secrets（ABYSS_KEYSTORE_B64 解码临时文件 + 口令
    // 环境变量）；材料缺失时 release 降级为未签名产物，编译门禁照常。
    val releaseKeystoreFile = (project.findProperty("abyssKeystoreFile") as String?)
        ?: System.getenv("ABYSS_KEYSTORE_FILE")
    val releaseStorePassword = (project.findProperty("abyssKeystorePassword") as String?)
        ?: System.getenv("ABYSS_KEYSTORE_PASSWORD")
    val releaseKeyAlias = (project.findProperty("abyssKeyAlias") as String?)
        ?: System.getenv("ABYSS_KEY_ALIAS") ?: "meng411722"
    val releaseKeyPassword = (project.findProperty("abyssKeyPassword") as String?)
        ?: System.getenv("ABYSS_KEY_PASSWORD")
    val releaseSigningReady = releaseKeystoreFile != null && file(releaseKeystoreFile).exists() &&
        !releaseStorePassword.isNullOrBlank() && !releaseKeyPassword.isNullOrBlank()

    signingConfigs {
        if (releaseSigningReady) {
            create("release") {
                storeFile = file(releaseKeystoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                // keystore 为 PKCS12(python cryptography 产,30 年自签)
                storeType = "PKCS12"
                // minSdk 26：v2/v3 全覆盖，v1 关闭（与主仓同款决策，评审 #14）
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            signingConfig = if (releaseSigningReady) signingConfigs.getByName("release") else null
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    lint {
        // targetSdk=28 是刻意工程决策（W^X）；侧载渠道语义不适用，显式关闭
        disable += "ExpiredTargetSdkVersion"
    }

    // 16KB page size（Android 15 起部分设备强制）：本骨架尚无 .so；引入
    // PRoot/QEMU 原生库时按 N-CMP-003 16KB 对齐标准打包（LIN-001 C-7
    // CI 以 zipalign/readelf 门禁复核）。
    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
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
    implementation(project(":runtime-api"))
    testImplementation(libs.junit)
}
