import org.gradle.api.tasks.Delete

buildscript {
    // AGP 9 的内置 Kotlin 版本绑在 AGP 自身（9.4.1 → Kotlin 2.2.10），
    // 没有可配置项，只能强制覆盖编译器版本。失败就删掉这段。
    configurations.named("classpath") {
        resolutionStrategy {
            force("org.jetbrains.kotlin:kotlin-gradle-plugin:${libs.versions.kotlin.get()}")
            force("org.jetbrains.kotlin:kotlin-compiler-embeddable:${libs.versions.kotlin.get()}")

            // CVE 依赖不再 force：下面 AGP/Kotlin classpath 先 exclude 旧模块，
            // 再由显式 classpath 统一补一份修复版本，避免同一坐标出现多份声明。
        }
    }

    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        // AGP 的传递依赖里会声明旧版本；只在 resolutionStrategy.force 里抬版本，
        // GitHub 依赖图仍记录旧坐标。这里从 AGP 依赖树里 exclude 旧模块，再显式
        // 加回修复版本，SBOM/Dependabot 才不会继续报旧版本。
        classpath(libs.android.gradle.plugin) {
            exclude(group = "com.google.guava", module = "guava")
            exclude(group = "org.apache.commons", module = "commons-lang3")
            exclude(group = "org.bitbucket.b_c", module = "jose4j")
            exclude(group = "org.bouncycastle", module = "bcpkix-jdk18on")
            exclude(group = "org.bouncycastle", module = "bcprov-jdk18on")
            exclude(group = "org.bouncycastle", module = "bcutil-jdk18on")
            exclude(group = "org.jdom", module = "jdom2")
        }

        // Kotlin 编译器也会把 guava/commons/BC 等带进 buildscript 依赖图，
        // 单独再排一次旧模块，避免 GitHub 依赖图从 Kotlin 路径继续记录旧坐标。
        classpath(libs.kotlin.gradle.plugin) {
            exclude(group = "com.google.guava", module = "guava")
            exclude(group = "org.apache.commons", module = "commons-lang3")
            exclude(group = "org.bouncycastle", module = "bcpkix-jdk18on")
            exclude(group = "org.bouncycastle", module = "bcprov-jdk18on")
            exclude(group = "org.bouncycastle", module = "bcutil-jdk18on")
        }
        // 被上面 exclude 的模块：显式补上修复版本
        classpath(libs.guava)
        classpath(libs.commons.lang3)
        classpath(libs.jose4j)
        classpath(libs.bcpkix.jdk18on)
        classpath(libs.jdom2)
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
    }
}

tasks.register<Delete>("clean") {
    // Gradle 9 起 Project.buildDir 弃用，改用 layout.buildDirectory（Provider）
    delete(rootProject.layout.buildDirectory)
}
