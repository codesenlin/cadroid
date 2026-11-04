// 密钥/账号配置的逐条合并逻辑（由 app/build.gradle.kts 通过 apply(from = ...) 引入）。
//
// 三份候选文件（都在 src/main/java/com/xero/ca/config/ 下）：
//   1. local/Secret.java      本机私人配置（.gitignore 忽略，可选）
//   2. standard/Secret.java   默认配置（原作者公开的那份值）
//   3. template/Secret.java   模板（全空，兜底）
//
// 合并规则是「逐条」而不是整份：
//   对每一个 getXxx()，按 私人 -> 默认 -> 模板 的顺序找第一个「非空」的值；
//   某一条在私人文件里是空的，就单独回退到默认的对应值，不影响其它条目；
//   三条都空就用空字符串（= 该功能关闭）。
//
// 支持的写法（模板就是这么写的，自己写私人配置时也请保持）：
//   public static String getXxx() { return "字面量"; }
//   public static String getXxx() { return CONST; }              // CONST 为本文件里的 private static final String
//   public static byte[] getVerifyKey() { return Base64.decode(CONST, Base64.DEFAULT); }
//
// 生成结果：build/generated/source/secret/com/xero/ca/gen/Secret.java
// （放在 gen 包下，避免和私人文件名 com/xero/ca/Secret.java 抢同一个类名）

import org.gradle.api.GradleException
import java.io.File

val secretStages = listOf(
    "local（本机）" to file("src/main/java/com/xero/ca/config/local/Secret.java"),
    "standard（默认）" to file("src/main/java/com/xero/ca/config/standard/Secret.java"),
    "template（模板）" to file("src/main/java/com/xero/ca/config/template/Secret.java")
)

// 解析一份配置源文件：返回 [getterName: [type: 'String'|'byte[]', value: '...']]
fun parseSecretSource(f: File): MutableMap<String, MutableMap<String, String>> {
    val src = f.readText()
    val consts = mutableMapOf<String, String>()
    val literalRegex = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"")

    // 收集本文件里的字符串常量
    // 支持单行与「多行字符串拼接」，例如 HOTFIX_PUBLIC_KEY = "..." + "..." + "...";
    val constRegex = Regex("""(?s)private\s+static\s+final\s+String\s+(\w+)\s*=\s*(.+?);""")
    for (m in constRegex.findAll(src)) {
        consts[m.groupValues[1]] = literalRegex.findAll(m.groupValues[2])
            .map { it.groupValues[1] }
            .joinToString("")
    }

    val values = mutableMapOf<String, MutableMap<String, String>>()
    val getterRegex = Regex("""(?s)public\s+static\s+(String|byte\[\])\s+(\w+)\s*\(\s*\)\s*\{\s*return\s+(.+?);\s*\}""")
    for (m in getterRegex.findAll(src)) {
        val type = m.groupValues[1]
        val name = m.groupValues[2]
        val body = m.groupValues[3].trim()
        val resolved: String? = if (type == "String") {
            val literal = literalRegex.matchEntire(body)
            if (literal != null) literal.groupValues[1] else consts[body]
        } else {
            val b64 = Regex("""Base64\.decode\(\s*(\w+)\s*,""").find(body)
            val key = b64?.groupValues?.get(1)
            if (key != null) consts[key] else null
        }
        if (resolved == null) {
            throw GradleException("解析不了 $f 里的 $name()，请保持模板里的写法（返回字面量，或返回本文件的 private static final String 常量）")
        }
        values[name] = mutableMapOf("type" to type, "value" to resolved)
    }

    if (values.isEmpty()) {
        // 空文件（0 字节 / 只有注释）= 没配置，跳过；有内容却解析不出东西才报错
        val stripped = src
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("^\\s*//.*", RegexOption.MULTILINE), "")
            .trim()
        if (stripped.isNotEmpty()) {
            throw GradleException("$f 里没解析到配置项，请从模板复制后修改（保持 public static getXxx() { return ...; } 的写法）")
        }
    }
    return values
}

// 逐条合并：private > default > template，空字符串视为「没配」
val secretMerged = linkedMapOf<String, MutableMap<String, String>>()
val secretPickedFrom = mutableMapOf<String, String>()
for ((stageName, stageFile) in secretStages) {
    if (!stageFile.exists()) continue
    val parsed = parseSecretSource(stageFile)
    for ((name, entry) in parsed) {
        val type = entry["type"] ?: continue
        val value = entry["value"] ?: continue
        val already = secretMerged[name]
        if (already == null) {
            // 先登记类型（取第一个有该条目的文件）
            secretMerged[name] = mutableMapOf("type" to type, "value" to "")
        }
        if (secretMerged[name]!!["value"]!!.isEmpty() && value.isNotEmpty()) {
            secretMerged[name] = mutableMapOf("type" to type, "value" to value)
            secretPickedFrom[name] = stageName
        }
    }
}
if (!secretMerged.containsKey("getVerifyKey")) {
    throw GradleException("三份配置里都没有 getVerifyKey()，至少 template/Secret.java 必须有")
}

val secretGenFile = layout.buildDirectory.file("generated/source/secret/com/xero/ca/gen/Secret.java")

tasks.register("generateSecret") {
    val stages = secretStages.map { it.second }.filter { it.exists() }
    inputs.files(stages)
    outputs.file(secretGenFile)
    doLast {
        val out = secretGenFile.get().asFile
        out.parentFile.mkdirs()
        val sb = StringBuilder()
        sb.append("package com.xero.ca.gen;\n\n")
        sb.append("import android.util.Base64;\n\n")
        sb.append("/**\n")
        sb.append(" * 由 app/secret-config.gradle.kts 在构建时生成，不要手改。\n")
        sb.append(" * 逐条合并 local（本机） > standard（默认） > template（模板），空条目自动回退。\n")
        sb.append(" */\n")
        sb.append("public class Secret {\n")
        for ((name, entry) in secretMerged) {
            val from = secretPickedFrom[name] ?: "template（模板，空实现）"
            sb.append("    // $name 来自 $from\n")
            if (entry["type"] == "String") {
                sb.append("    public static String $name() {\n        return \"${entry["value"]}\";\n    }\n\n")
            } else {
                sb.append("    public static byte[] $name() {\n        return Base64.decode(\"${entry["value"]}\", Base64.DEFAULT);\n    }\n\n")
            }
        }
        // 去掉最后一个多余的空行
        val text = sb.toString().trimEnd('\n') + "\n"
        out.writeText(text + "}\n")
        logger.lifecycle("Secret 配置（逐条合并）:")
        secretMerged.keys.sorted().forEach { name ->
            val from = secretPickedFrom[name] ?: "template（空）"
            val shown = if (secretMerged[name]!!["value"]!!.isEmpty()) "（空）" else "（已配置）"
            logger.lifecycle("  $name: $from $shown")
        }
        logger.lifecycle("  生成: " + out)
    }
}
