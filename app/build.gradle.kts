import groovy.json.JsonSlurper
import org.gradle.api.GradleException
import java.io.File

plugins {
    id("com.android.application")
}

// 脚本（assets/script.js）是用「签名证书 DER 逐字节异或 + GZIP」加密的，
// 所以签名用的密钥必须和 CA 侧 app/signatures/*.signature 里的证书一致。
// 这里统一用 app/signatures/release.keystore（alias: testkey）：
//   debug 包  -> 由 Gradle 用它签名，配合 signatures/debug.signature
//   release 包 -> 保持未签名（app-release-unsigned.apk），由 CA 的
//                 cabuild shellBuildRelease（zipalign + apksigner）用它签名
val caKeystore = file("signatures/release.keystore")
val caPasswordFile = file("signatures/release.password")

// 优量汇（GDT）的两个组件是配套使用的，必须有一起有、没有一起没有：
//   - 广告 SDK：com.qq.e.union:union（Maven）
//   - 转化（回传）SDK：app/libs/GDTActionSDK.min.1.9.8.aar（本地，不入库）
// 本地没有那个 aar 时两个都不加载（也就不会出现「投放广告但没有转化回传」的半套状态），
// 此时用 app/src/stub/gdt 里的空实现编译，并让 BuildConfig.GDT_ENABLED=false 跳过广告逻辑。
// 优量汇（GDT）是可选组件：
//  - 默认看本地 aar 在不在
//  - 也可以用 -Pca.withGdt=false 强制关掉（不装广告 SDK，APK 从 4.13 MB 降到 1.42 MB，
//    且不再依赖只有 arm 版本的 libturingad.so —— 所有架构都能跑）
val gdtRequested = (findProperty("ca.withGdt") ?: "true").toString() != "false"
val gdtAvailable = gdtRequested && file("libs/GDTActionSDK.min.1.9.8.aar").exists()
val tdAvailable = file("libs/SaaS_TalkingDataSDK_Android_V5.0.40.jar").exists()
// 加速库：CA 仓库的 caaccel/build.sh 产出。缺失时只是不启用 Java 加速，
// JS 侧的 Accel.available() 会自动回退到等价实现，所以是可选项。
val caaccelAvailable = file("libs/caaccel.jar").exists()

// Rhino 兼容编译模式（Rhino 1.9 在 minSdk 21 下的 dex 兼容，见 docs/migration-kotlin-quickjs.md）：
//   true  = 调试包也走 R8 的 dex 流程。Rhino 1.9 的 SlotMapOwner$ThreadedAccess
//           用了 MethodHandle.invoke，minSdk 21 下 D8 不做兼容转换，不开 R8 会
//           直接 "Error while dexing"
//   false = 调试包退回普通的 debuggable 构建（只有把 Rhino 换掉之后才能关）
// 切换方式：gradle.properties 里改 ca.rhinoCompat，或命令行 -Pca.rhinoCompat=false
val rhinoCompat = (findProperty("ca.rhinoCompat") ?: "true").toString() != "false"


android {
    namespace = "com.xero.ca"
    compileSdk = 37

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        // 语言级别 21（Gradle 守护进程本身跑 JDK 25，见 gradle-daemon-jvm.properties）
        sourceCompatibility = JavaVersion.VERSION_25
        targetCompatibility = JavaVersion.VERSION_25
    }

    signingConfigs {
        if (caKeystore.exists() && caPasswordFile.exists()) {
            create("ca") {
                storeFile = caKeystore
                storePassword = caPasswordFile.readText().trim()
                keyAlias = "testkey"
                keyPassword = caPasswordFile.readText().trim()
                // 附加 V3 签名（APK Signature Scheme v3），v1/v2 一并保留以兼容旧系统
                // Gradle 10 起必须用赋值语法；旧写法 enableV1Signing true 已弃用
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    defaultConfig {
        applicationId = "com.xero.ca"
        minSdk = 21
        targetSdk = 33
        versionCode = 20765
        versionName = "1.2.16"
        buildConfigField("int", "SHELL_VERSION", "26")
        // 优量汇广告 SDK 是否已装配（本地有 GDTActionSDK aar 时为 true），见文件开头的说明
        buildConfigField("boolean", "GDT_ENABLED", gdtAvailable.toString())
    }
    buildTypes {
        getByName("debug") {
            isDebuggable = true
            // Rhino 兼容编译模式（见文件开头的 rhinoCompat）：调试包也走 R8。
            // 不开启的话 assembleDebug 会直接报 "Error while dexing"。
            isMinifyEnabled = rhinoCompat
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
                // 没有 signatures/ 时退回 Android 默认调试签名（此时需自行重新生成 debug.signature）
            signingConfig = signingConfigs.findByName("ca") ?: signingConfigs.getByName("debug")
            isJniDebuggable = true
        }
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            isDebuggable = false
            isJniDebuggable = false
            }
    }
    sourceSets {
        getByName("main") {
            // 缺哪个本地依赖就把对应的空实现加进来，保证 clone 下来没有本地文件也能构建：
            //   src/stub/talkingdata  统计 SDK（TalkingData）缺失时
            //   src/stub/gdt          广告/转化 SDK 缺失时（两个一起缺席）
            if (!tdAvailable) {
                java.directories.add("src/stub/talkingdata")
                kotlin.directories.add("src/stub/talkingdata")
            }
            if (!gdtAvailable) {
                java.directories.add("src/stub/gdt")
                kotlin.directories.add("src/stub/gdt")
            }
            // 三份配置只当数据源，由 app/build.gradle.kts 合并生成 com/xero/ca/gen/Secret.kt
            kotlin.directories.add(layout.buildDirectory.dir("generated/source/secret").get().asFile.path)
        }
    }
    buildFeatures {
        viewBinding = false
        buildConfig = true
    }
    dependenciesInfo {
        includeInApk = true
        includeInBundle = true
    }
}


dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    // 本地依赖不入库（见 app/libs/.gitignore）；缺失时用 src/stub 下的空实现编译（见 sourceSets）
    if (tdAvailable) {
        implementation(files("libs/SaaS_TalkingDataSDK_Android_V5.0.40.jar"))
    }

    if (gdtAvailable) {
        // 广告 SDK 与转化 SDK 配套：有 aar 就两个都加载，没有就两个都不加载
        implementation(files("libs/GDTActionSDK.min.1.9.8.aar"))
        implementation(libs.gdt.union)
    }

    // 命令助手高性能加速库（源码在 CA 仓库的 caaccel/，用 caaccel/build.sh 构建）
    // 可选：jar 不在时不引入，Rhino 里 Packages 解析失败，JS 侧自动走回退实现
    if (caaccelAvailable) {
        implementation(files("libs/caaccel.jar"))
    }


    implementation(libs.androidx.annotation)
    // SharedPreferences.edit { } 等 KTX 扩展（Preference.kt 用到）
    implementation(libs.androidx.core.ktx)
    // support-v4 之前引了但代码里没用到，迁 AndroidX 时直接去掉
    implementation(libs.dalvik.dx)
    implementation(libs.zip4j)
    implementation(libs.java.websocket)
    implementation(project(":pews-api-wrapper"))
    // Rhino 1.9.x 的优化器依赖 jdk.dynalink（JDK 专属模块），Android 上跑不了，
    // 这也是官方的 rhino-runtime 停在 1.7.15.1 的原因
    // 1.8+ 起 rhino-runtime 停更，只能换完整版 rhino
    implementation(libs.rhino)
    implementation(libs.rhino.engine)
    implementation(libs.rhino.kotlin)
    // E4X（XML 字面量）支持：1.8 起从核心拆成独立模块，老脚本可能用到
    implementation(libs.rhino.xml)
    // QuickJS 侧载加速 worker 的引擎绑定（16KB page 对齐的 .so，Apache-2.0）
    implementation(libs.quickjs.kt.android)
}

// 密钥/账号配置：三份候选文件（本机/默认/模板）逐条合并，逻辑见 app/build.gradle.kts
tasks.named("preBuild") {
    dependsOn("generateSecret")
}

// ===== Secret 配置合并（原 secret-config 逻辑） =====
// 密钥/账号配置的逐条合并逻辑（由 app/build.gradle.kts 通过 apply(from = ...) 引入）。
//
// 三份候选 JSON（都在 app/secrets/ 下）：
//   1. local.json      本机私人配置（.gitignore 忽略，可选）
//   2. standard.json   默认配置（原作者公开的那份值）
//   3. template.json   模板（全空，兜底）
//
// 合并规则是「逐条」而不是整份：
//   对每一个 getXxx()，按 私人 -> 默认 -> 模板 的顺序找第一个「非空」的值；
//   某一条在私人文件里是空的，就单独回退到默认的对应值，不影响其它条目；
//   三条都空就用空字符串（= 该功能关闭）。
//
// 生成结果：build/generated/source/secret/com/xero/ca/gen/Secret.kt
// （放在 gen 包下，避免和私人文件名 com/xero/ca/Secret 抢同一个类名）


val secretFields = linkedMapOf(
    "getVerifyKey" to "byte[]",
    "getLegacyVerifyKey" to "byte[]",
    "getTDToken" to "String",
    "getGDTAppID" to "String",
    "getGDTPosID" to "String",
    "getGiteeClientId" to "String",
    "getGiteeClientSecret" to "String",
    "getGiteeFeedbackToken" to "String",
)

val byteFields = setOf("getVerifyKey", "getLegacyVerifyKey")

val secretStages = listOf(
    "local（本机）" to file("secrets/local.json"),
    "standard（默认）" to file("secrets/standard.json"),
    "template（模板）" to file("secrets/template.json"),
)

fun parseSecretJson(f: File): Map<String, String> {
    if (f.length() == 0L) return secretFields.keys.associateWith { "" }
    val root = try {
        JsonSlurper().parse(f)
    } catch (e: Exception) {
        throw GradleException("解析不了 $f：${e.message}")
    } as? Map<*, *> ?: throw GradleException("$f 必须是 JSON 对象")

    val values = linkedMapOf<String, String>()
    for ((name, _) in secretFields) {
        val raw = root[name] ?: ""
        if (raw !is String) throw GradleException("$f 的 $name 必须是字符串")
        values[name] = raw
    }
    return values
}

fun escapeKotlinString(value: String): String =
    value.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$")

val secretMerged = linkedMapOf<String, String>()
val secretPickedFrom = mutableMapOf<String, String>()
for ((stageName, stageFile) in secretStages) {
    if (!stageFile.exists()) continue
    val parsed = parseSecretJson(stageFile)
    for ((name, value) in parsed) {
        if (secretMerged[name].isNullOrEmpty() && value.isNotEmpty()) {
            secretMerged[name] = value
            secretPickedFrom[name] = stageName
        } else if (!secretMerged.containsKey(name)) {
            secretMerged[name] = ""
        }
    }
}
for ((name, _) in secretFields) {
    if (!secretMerged.containsKey(name)) secretMerged[name] = ""
}
if (secretMerged["getVerifyKey"].isNullOrEmpty()) {
    throw GradleException("三份配置里都没有非空的 getVerifyKey，至少 template.json 必须有")
}

val secretGenFile = layout.buildDirectory.file("generated/source/secret/com/xero/ca/gen/Secret.kt")

tasks.register("generateSecret") {
    val stages = secretStages.map { it.second }.filter { it.exists() }
    inputs.files(stages)
    outputs.file(secretGenFile)
    doLast {
        val out = secretGenFile.get().asFile
        out.parentFile.mkdirs()
        val sb = StringBuilder()
        sb.append("package com.xero.ca.gen\n\n")
        sb.append("import android.util.Base64\n\n")
        sb.append("/**\n")
        sb.append(" * 由 app/build.gradle.kts 在构建时生成，不要手改。\n")
        sb.append(" * 逐条合并 local（本机） > standard（默认） > template（模板），空条目自动回退。\n")
        sb.append(" */\n")
        sb.append("object Secret {\n")
        for ((name, type) in secretFields) {
            val from = secretPickedFrom[name] ?: "template（模板，空实现）"
            val value = escapeKotlinString(secretMerged[name] ?: "")
            sb.append("    // $name 来自 $from\n")
            if (type == "byte[]") {
                sb.append("    @JvmStatic\n")
                sb.append("    fun $name(): ByteArray = Base64.decode(\"$value\", Base64.DEFAULT)\n\n")
            } else {
                sb.append("    @JvmStatic\n")
                sb.append("    fun $name(): String = \"$value\"\n\n")
            }
        }
        val text = sb.toString().trimEnd('\n') + "\n}\n"
        out.writeText(text)
        logger.lifecycle("Secret 配置（逐条合并）:")
        secretFields.keys.sorted().forEach { name ->
            val from = secretPickedFrom[name] ?: "template（空）"
            val shown = if (secretMerged[name].isNullOrEmpty()) "（空）" else "（已配置）"
            logger.lifecycle("  $name: $from $shown")
        }
        logger.lifecycle("  生成: $out")
    }
}
