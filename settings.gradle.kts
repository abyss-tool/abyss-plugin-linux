pluginManagement {
    repositories {
        // NIA 同款纪律：google() 只解析 Google 系 group，防误拉非预期构件
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

// 前置检查：JDK 不满足直接人话报错（与主仓 CI setup-java temurin 17 对齐）
check(JavaVersion.current().isCompatibleWith(JavaVersion.VERSION_17)) {
    "abyss-plugin-linux requires JDK 17+ (current: ${JavaVersion.current()}). " +
        "Set JAVA_HOME to a JDK 17+ installation. CI reference: .github/workflows/plugin-ci.yml (setup-java temurin 17)."
}

rootProject.name = "abyss-plugin-linux"

// ── 模块地图（ARC-100 §4，纪律：每个 include 必须有 build.gradle.kts
//    与至少一个源文件，禁止空模块）───────────────────────────────────
// :app             插件 APK：LinuxRuntimeService / 前台服务 / 权限声明 / AIDL 编译
// :runtime-api     纯 JVM 契约：ILinuxRuntime/ILinuxSession 数据模型 + 能力描述
//                  JSON + 统一错误码表（宿主与消费方 compileOnly 的单一事实源）
// M1 起逐里程碑加入（见 ROADMAP.md / ARC-100 §4）：
// :session         纯 JVM：RuntimeSession 状态机 / 引用计数 / 限额 / 工作区断言
// :asset           纯 JVM + Android 下载实现：abyss-asset-v1 清单 / 分卷校验 / 配额
// :probe           纯 JVM + JNI：ptrace / memlock / arch / 存储探测与降级决策
// :shizuku         Android 库：唯一授权检测与 shell 上下文（Shizuku AAR 只进此模块）
// :domain-proot    Android 库 + 原生库：proot-distro-nolib 嵌入 / Alpine rootfs 解包
// :domain-qemu     Android 库 + 原生库：QEMU TCG / qcow2 / VNC 回环 / 快照 / 9p 挂载
include(":app")
include(":runtime-api")
