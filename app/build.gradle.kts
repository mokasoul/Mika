import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    // 插件 manifest/注册表用 kotlinx.serialization(依赖已经在 libs 里,core 也在用)
    alias(libs.plugins.kotlin.serialization)
}

// 关于页上的构建编号:当前提交的短哈希;没有 git(如从源码包编译)时写 dev
val gitHash: String = runCatching {
    providers.exec { commandLine("git", "rev-parse", "--short=7", "HEAD") }.standardOutput.asText.get().trim()
}.getOrNull()?.takeIf { it.isNotEmpty() } ?: "dev"

// 固定签名:更新包要能覆盖安装,每次构建必须是同一个证书。
// 证书与口令放在 ~/.android/ 下,不进仓库;没有它时退回默认(调试包能装、发布包未签名)。
val bridgeSigning = Properties().apply {
    val f = File(System.getProperty("user.home"), ".android/mika-bridge.properties")
    if (f.isFile) f.inputStream().use { load(it) }
}
val bridgeKeystore = File(System.getProperty("user.home"), ".android/mika-bridge.jks")

android {
    namespace = "com.wangzan101.mika"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.wangzan101.mika"
        minSdk = 29
        targetSdk = 35
        versionCode = 63
        versionName = "0.1.62"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "GIT_HASH", "\"$gitHash\"")
        // 关于页版本号后的 Beta 标记;出正式版时改成 false
        buildConfigField("boolean", "BETA", "true")
        buildConfigField("boolean", "ADB_LOGS", "false")
    }

    // 沙箱程序只有 arm64 版本(jniLibs/arm64-v8a);APK 不限 ABI,别的设备照常安装、显示「此设备不支持沙箱」。
    // 沙箱程序要从 nativeLibraryDir 直接执行,安装时必须解到磁盘
    packaging {
        jniLibs { useLegacyPackaging = true }
    }

    // 调试包与发布包共用这一个固定证书:先装的包和后到的更新包签名一致才能覆盖安装。
    signingConfigs {
        if (bridgeSigning.isNotEmpty() && bridgeKeystore.isFile) {
            create("bridge") {
                storeFile = bridgeKeystore
                storePassword = bridgeSigning.getProperty("storePassword")
                keyAlias = bridgeSigning.getProperty("keyAlias")
                keyPassword = bridgeSigning.getProperty("keyPassword")
            }
        }
    }

    // 发布用的包(perf、release)按架构再出一份:除了全架构包,另出只带 arm64-v8a 原生库的小包。
    // 两个包代码、资源、版本号、签名都一样,只差 lib/ 下的其他架构;各自是完整 APK,不是一起安装的拆分包。
    // 产物:app-universal-<类型>.apk、app-arm64-v8a-<类型>.apk。拆分对所有构建类型生效,
    // 所以只在这次构建要出 perf/release 包时打开,单独构建调试包时产物仍是 app-debug.apk。
    splits {
        abi {
            isEnable = gradle.startParameter.taskNames.any { Regex("(?i)(perf|release)").containsMatchIn(it) }
            reset()
            include("arm64-v8a")
            isUniversalApk = true
        }
    }
    // 自带的运行环境(assets/runtime/rootfs.tar.xz,tools/sandbox/build-runtime.py 生成)已是 xz,不再压一遍
    // 壳模式瘦身:ignoreAssetsPattern 排除内置 Linux 沙箱的 rootfs(约 56 MB,占包体 47%)。
    // 源码树里的 app/src/main/assets/runtime/rootfs.tar.xz 保持原样,
    // 所以 BundledRuntimeTest(读的是源码树而非 APK)照常通过;
    // App 侧 RuntimePreparer.ensure() 会捕获缺文件异常,降级范围仅限沙箱(终端)功能。
    androidResources {
        noCompress += "xz"
        ignoreAssetsPattern = "!rootfs.tar.xz"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // 评测任务集(仓库根目录 eval/tasks)打进测试包的 assets,真机评测跑器从那里读题
    sourceSets.getByName("androidTest").assets.srcDir(rootProject.file("eval/tasks"))

    buildTypes {
        // 调试包也挂固定证书,这样「手动装的第一个包」和之后从 GitHub 更新下来的包签名一致
        getByName("debug") { signingConfigs.findByName("bridge")?.let { signingConfig = it } }
        getByName("release") { signingConfigs.findByName("bridge")?.let { signingConfig = it } }
        // 真机验收时并排安装,保留发行版和用户数据;默认构建标识不变。
        if (providers.gradleProperty("devInstall").isPresent) getByName("debug") {
            applicationIdSuffix = ".dev"
            resValue("string", "app_name", "Mika Dev")
        }
        // 看流畅度用的包:和发布版一样不可调试(调试包不做预先编译,界面慢好几倍,手感不作数),
        // 用本机调试签名,能直接覆盖调试包、保留数据;可被性能工具采样。./gradlew :app:assemblePerf
        create("perf") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            isProfileable = true
            matchingFallbacks += "release"
            // 真机比较滑动手感也用优化包,仍覆盖 .dev 测试版并保留它的数据。
            if (providers.gradleProperty("devInstall").isPresent) {
                applicationIdSuffix = ".dev"
                resValue("string", "app_name", "Mika Dev")
            }
        }
        // 本地日志检查包：并排安装，允许 adb run-as，首次启动即记录详细 Trace。
        create("logs") {
            initWith(getByName("perf"))
            applicationIdSuffix = ".logs"
            versionNameSuffix = "-logs"
            isDebuggable = true
            isProfileable = false
            resValue("string", "app_name", "Mika Logs")
            buildConfigField("boolean", "ADB_LOGS", "true")
            matchingFallbacks += "release"
        }
    }

    buildFeatures {
        compose = true
        aidl = true
        buildConfig = true
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core"))
    implementation(libs.protobuf.lite)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.window)
    implementation(libs.androidx.webkit)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.xz)
    implementation(libs.commonmark)
    implementation(libs.commonmark.ext.gfm.tables)
    implementation(libs.commonmark.ext.gfm.strikethrough)
    // 两种识别模型都随 APK 安装；首次使用不依赖 Play 服务下载。
    implementation(libs.mlkit.text.recognition)
    implementation(libs.mlkit.text.recognition.chinese)
    // 微信绑定二维码只在本机编码，不调用在线图片服务。
    implementation(libs.zxing.core)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    // 沙箱路径契约(core 的 testFixtures):AndroidSandbox 在 JVM 与真机上都跑同一套
    testImplementation(testFixtures(project(":core")))
    androidTestImplementation(testFixtures(project(":core")))

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    // Older transitive Espresso reflects on InputManager.getInstance(), removed on newer devices.
    androidTestImplementation(libs.androidx.test.espresso)
    debugImplementation(libs.compose.ui.test.manifest)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    // Compose BOM 的旧传递版本在 Android 17 上调用已移除的 InputManager.getInstance。
    androidTestImplementation(libs.androidx.test.espresso)
    androidTestImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.junit)
}

// LicensesTest 读仓库的 licenses/THIRD_PARTY_NOTICES.md:让它参与测试的增量判断
tasks.withType<Test>().configureEach {
    inputs.file(rootProject.file("licenses/THIRD_PARTY_NOTICES.md")).withPropertyName("thirdPartyNotices")
}
