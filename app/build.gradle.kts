import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.rikka.tools.refine)
}
var isCIBuild: Boolean = System.getenv("CI").toBoolean()

// isCIBuild = true // 没有c++源码时开启CI构建, push前关闭

android {
    namespace = "io.github.aoguai.sesameag"
    compileSdk = 37
    buildToolsVersion = "37.0.0"
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = true
        }
    }
    // 使用providers API来支持配置缓存
    val gitCommitCount: Int =
        providers
            .exec {
                commandLine("git", "rev-list", "--count", "HEAD")
            }.standardOutput.asText
            .get()
            .trim()
            .toIntOrNull() ?: 1
    val appVersionName: String = providers.gradleProperty("appVersion").orElse("0.0.1").get()
    val officialSigningCertSha256 =
        providers
            .gradleProperty("officialSigningCertSha256")
            .orNull
            ?.trim()
            .orEmpty()
    require(officialSigningCertSha256.isEmpty() || officialSigningCertSha256.matches(Regex("[0-9A-F]{64}"))) {
        "officialSigningCertSha256 must be an uppercase, colon-free SHA-256 certificate fingerprint"
    }
    defaultConfig {
        vectorDrawables.useSupportLibrary = true
        applicationId = "io.github.aoguai.sesameag"
        minSdk = 29
        targetSdk = 36

        val buildDate =
            SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
                .apply {
                    timeZone = TimeZone.getTimeZone("GMT+8")
                }.format(Date())

        val buildTime =
            SimpleDateFormat("HH:mm:ss", Locale.CHINA)
                .apply {
                    timeZone = TimeZone.getTimeZone("GMT+8")
                }.format(Date())

        versionCode = gitCommitCount
        versionName = appVersionName

        buildConfigField("String", "BUILD_DATE", "\"$buildDate\"")
        buildConfigField("String", "BUILD_TIME", "\"$buildTime\"")
        buildConfigField("String", "OFFICIAL_SIGNING_CERT_SHA256", "\"$officialSigningCertSha256\"")
        if (isCIBuild) {
            ndk {
                abiFilters.addAll(listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64"))
            }
        }
    }

    testOptions {
        unitTests.all {
            it.enabled = false
        }
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
        compose = true
        aidl = true
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = false // 关闭脱糖
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    signingConfigs {
        getByName("debug") {
        }
    }

    buildTypes {
        getByName("debug") {
            isDebuggable = true
            versionNameSuffix = "-debug"
            isShrinkResources = false
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
        getByName("release") {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    sourceSets {
        getByName("main") {
            jniLibs.setSrcDirs(listOf("src/main/jniLibs"))
        }
    }
    val cmakeFile = file("src/main/cpp/CMakeLists.txt")
    if (!isCIBuild && cmakeFile.exists()) {
        externalNativeBuild {
            cmake {
                path = cmakeFile
//                version = "4.1.2"  //不要随意改这个了答应我
                ndkVersion = "29.0.14206865" // 这个也是 答应我就这样吧
            }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

composeCompiler {
    // Keep release builds away from Compose stack-trace/tooling metadata that triggers mapping generation.
    generateFunctionKeyMetaClasses.set(false)
    includeSourceInformation.set(false)
    includeTraceMarkers.set(false)
}

tasks.configureEach {
    if (name == "produceReleaseComposeMapping") {
        enabled = false
    }
}

dependencies {
    // Shizuku 相关依赖 - 用于获取系统级权限
    implementation(libs.rikka.shizuku.api) // Shizuku API
    implementation(libs.rikka.shizuku.provider) // Shizuku 提供者
    implementation(libs.rikka.refine) // Rikka 反射工具
//    implementation(libs.rikka.hidden.stub)
    // implementation(libs.ui.tooling.preview.android)
    implementation(libs.cmd.android)
    implementation(libs.androidx.ui.text.google.fonts)
    implementation(libs.material3) // 用于通过 Shizuku 执行命令

    // Compose 相关依赖 - 现代化 UI 框架
    val composeBom = platform("androidx.compose:compose-bom:2025.12.00") // Compose BOM 版本管理
    implementation(composeBom)

    testImplementation(composeBom)
    testImplementation(libs.junit)
    androidTestImplementation(composeBom)
    implementation(libs.androidx.material3) // Material 3 设计组件
    implementation(libs.androidx.ui.tooling.preview) // UI 工具预览
    debugImplementation(libs.androidx.ui.tooling) // 调试时的 UI 工具
    implementation(libs.androidx.material.icons.extended) // Material 3 图标

    // 生命周期和数据绑定
    implementation(libs.androidx.lifecycle.viewmodel.compose) // Compose ViewModel 支持

    // JSON 序列化
    implementation(libs.kotlinx.serialization.json) // Kotlin JSON 序列化库

    // Kotlin 协程依赖 - 异步编程（纯协程调度）
    implementation(libs.kotlinx.coroutines.core) // 协程核心库
    implementation(libs.kotlinx.coroutines.android) // Android 协程支持

    // 数据观察和 HTTP 服务
    implementation(libs.androidx.lifecycle.livedata.ktx) // LiveData KTX 扩展
    implementation(libs.androidx.runtime.livedata) // Compose LiveData 运行时
    implementation(libs.nanohttpd) // 轻量级 HTTP 服务器

    // UI 布局和组件
    implementation(libs.androidx.constraintlayout) // 约束布局

    implementation(libs.activity.compose) // Compose Activity 支持

    // Android 核心库
    implementation(libs.core.ktx) // Android KTX 核心扩展
    implementation(libs.kotlin.stdlib) // Kotlin 标准库
    implementation(libs.slf4j.api) // SLF4J 日志 API
    implementation(libs.logback.android) // Logback Android 日志实现
    implementation(libs.appcompat) // AppCompat 兼容库
    implementation(libs.recyclerview) // RecyclerView 列表组件
    implementation(libs.viewpager2) // ViewPager2 页面滑动
    implementation(libs.material) // Material Design 组件
    implementation(libs.webkit) // WebView 组件

    // 仅编译时依赖 - Xposed 相关
    compileOnly(libs.libxposed.api) // Xposed API 102 https://github.com/libxposed/api
    implementation(libs.libxposed.service) // https://github.com/libxposed/service

    // 代码生成和工具库
    implementation(libs.okhttp) // OkHttp 网络请求库
    implementation(libs.jackson.kotlin) // Jackson Kotlin 支持

    // 核心库脱糖和系统 API 访问
//    coreLibraryDesugaring(libs.desugar)            // Java 8+ API 脱糖支持

    implementation(libs.hiddenapibypass) // 隐藏 API 访问绕过

    // Jackson JSON 处理库
    implementation(libs.jackson.core)
    implementation(libs.jackson.databind)
    implementation(libs.jackson.annotations)
}
