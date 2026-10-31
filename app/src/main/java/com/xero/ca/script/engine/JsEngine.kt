package com.xero.ca.script.engine

import java.io.File

/**
 * 引擎无关的运行时句柄。
 *
 * 脚本主引擎目前只有 Rhino（[RhinoEngine]）；QuickJS 不再做完整主引擎，
 * 只作为纯计算侧载 worker 使用（[QuickJsWorker]，JSON 进出、不接 Java 反射桥）。
 * 这层抽象保留，方便以后需要时再接新的主引擎实现。
 */

/** 进入线程后的引擎上下文（Rhino 的 Context）。 */
class JsContext(internal val impl: Any?)

/** 全局作用域（Rhino 的 Scriptable 全局对象）。 */
class JsScope(internal val impl: Any?) {
    companion object {
        /** 把脚本侧传来的作用域（可能是引擎原生类型）包一层。 */
        fun of(impl: Any?): JsScope = JsScope(impl)
    }
}

/** 编译后的脚本（Rhino 的 Script，可序列化到磁盘做缓存）。 */
class JsScript(internal val impl: Any?)

/**
 * 脚本引擎抽象。
 *
 * 约定：
 *  - 所有 `scope` 参数都可能是引擎原生对象（脚本传进来的），实现自己负责解包
 *  - 编译缓存（`script.obj` / iCode）属于引擎细节，放在实现里
 */
interface JsEngine {

    /** 引擎名，日志/排错用。 */
    val name: String

    /** 在当前线程进入引擎上下文（Rhino 是 Context.enter + 参数设置）。 */
    fun enterContext(): JsContext

    /** 退出当前线程的引擎上下文（Rhino 是 Context.exit）。 */
    fun exitContext()

    /** 创建全局作用域，并把桥接对象注入成全局变量 `ScriptInterface`。 */
    fun initScope(context: JsContext, scriptInterface: Any): JsScope

    /**
     * 编译源码。`cacheFile` 非空时尝试用磁盘缓存：
     * 命中就直接返回缓存产物，未命中则编译并写回。
     */
    fun compile(context: JsContext, source: String, sourceName: String?, cacheFile: File?): JsScript

    /** 读磁盘上的编译缓存；失败返回 null（调用方回退到 [compile]）。 */
    fun readScriptCache(cacheFile: File, hash: ByteArray?): JsScript?

    /** 写编译缓存。 */
    fun writeScriptCache(cacheFile: File, hash: ByteArray, script: JsScript)

    /** 执行编译产物。`scope` 为空时用全局作用域。 */
    fun execute(context: JsContext, script: JsScript, scope: Any?): Any?

    /** 直接执行一段源码。 */
    fun executeSource(context: JsContext, source: String, sourceName: String?, scope: Any?): Any? =
        execute(context, compile(context, source, sourceName, null), scope)

    /** 调用全局函数；不存在或不是函数时返回 null。 */
    fun callFunction(context: JsContext, scope: Any?, name: String, args: Array<Any?>): Any?

    /** 把 Java 对象包装成脚本可直接调用的值。 */
    fun wrapAsScriptable(obj: Any?, scope: Any?): Any?

    /** 读 iCode（Rhino 的字节码缓存格式）。 */
    fun readICode(iCode: ByteArray, hash: ByteArray?): JsScript?

    /** 写 iCode。 */
    fun writeICode(script: JsScript, hash: ByteArray): ByteArray

}
