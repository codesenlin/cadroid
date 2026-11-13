plugins {
    `java-library`
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

// PEWS-API 是桌面项目，直接在 Android 工程里编译时要把 GUI / 旧 WebSocket
// 适配层换成我们自己的版本。这里先把上游源码同步到 build 目录，再排除
// 需要替换的文件，避免在 subtree 目录里改上游源码。
val preparePewsUpstream = tasks.register<Sync>("preparePewsUpstream") {
    from("../PEWS-API/src") {
        exclude(
            "mcpews/listeners/ServerGUI.java",
            "mcpews/listeners/TextToBlockListener.java",
            "mcpews/util/ChatFormatCode.java",
            "mcpews/WSEncrypt.java",
            "mcpews/MCSocketServer.java"
        )
    }
    into(layout.buildDirectory.dir("pews-upstream-src"))
}

sourceSets {
    main {
        java.srcDir(layout.buildDirectory.dir("pews-upstream-src"))
    }
}

tasks.named("compileJava") {
    dependsOn(preparePewsUpstream)
}

dependencies {
    implementation(libs.java.websocket)
    implementation(libs.gson)
}
