package com.xero.ca.script.engine

import android.util.Log
import com.faendir.rhino_android.RhinoAndroidHelper
import com.xero.ca.ScriptFileStream
import org.mozilla.javascript.Context
import org.mozilla.javascript.Function
import org.mozilla.javascript.NativeJavaObject
import org.mozilla.javascript.RhinoException
import org.mozilla.javascript.Script
import org.mozilla.javascript.ScriptRuntime
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.StackStyle
import org.mozilla.javascript.lc.type.TypeInfoFactory
import java.io.File
import java.io.IOException

/**
 * [JsEngine] 的 Rhino 实现：把原来散在 `ScriptManager` / `ScriptInterface` 里的
 * Rhino 细节集中到这一个类，行为与迁移前完全一致。
 */
class RhinoEngine : JsEngine {

    override val name: String = "Rhino"

    override fun enterContext(): JsContext {
        val cx = RhinoAndroidHelper().enterContext()
        cx.setOptimizationLevel(-1)
        cx.setLanguageVersion(Context.VERSION_ES6)
        return JsContext(cx)
    }

    override fun exitContext() {
        Context.exit()
    }

    override fun initScope(context: JsContext, scriptInterface: Any): JsScope {
        val cx = context.impl as Context
        val wrapFactory = cx.wrapFactory
        val scope = cx.initStandardObjects()
        scope.put(
            "ScriptInterface",
            scope,
            wrapFactory.wrapAsJavaObject(cx, scope, scriptInterface, scriptInterface.javaClass)
        )
        return JsScope(scope)
    }

    override fun compile(
        context: JsContext,
        source: String,
        sourceName: String?,
        cacheFile: File?,
    ): JsScript {
        val cx = context.impl as Context
        if (cacheFile == null) {
            return JsScript(cx.compileString(source, sourceName, 0, null))
        }
        val hash = ScriptFileStream.getStringHash(source)
        if (cacheFile.exists()) {
            try {
                return JsScript(ScriptFileStream.readScript(cacheFile.path, hash) as Script)
            } catch (e: Exception) {
                Log.e("Script", "Loading cacheScript failed", e)
            }
        }
        val script = cx.compileString(source, sourceName, 0, null)
        try {
            ScriptFileStream.writeScript(cacheFile.path, hash, script)
        } catch (e: IOException) {
            Log.e("Script", "Write cacheScript failed", e)
        }
        return JsScript(script)
    }

    override fun readScriptCache(cacheFile: File, hash: ByteArray?): JsScript? =
        try {
            JsScript(ScriptFileStream.readScript(cacheFile.path, hash) as Script)
        } catch (e: Exception) {
            Log.e("Script", "Loading cacheScript failed", e)
            null
        }

    override fun writeScriptCache(cacheFile: File, hash: ByteArray, script: JsScript) {
        ScriptFileStream.writeScript(cacheFile.path, hash, script.impl)
    }

    override fun execute(context: JsContext, script: JsScript, scope: Any?): Any? {
        val cx = context.impl as Context
        val s = asScriptable(scope) ?: throw IllegalStateException("执行脚本时缺少作用域")
        return (script.impl as Script).exec(cx, s)
    }

    override fun callFunction(
        context: JsContext,
        scope: Any?,
        name: String,
        args: Array<Any?>,
    ): Any? {
        val cx = context.impl as Context
        val s = asScriptable(scope) ?: return null
        val obj = s.get(name, s)
        if (obj !is Function) return null
        return try {
            obj.call(cx, s, s, args)
        } catch (ex: Exception) {
            // 和迁移前一致：钩子里的异常直接忽略
            null
        }
    }

    override fun wrapAsScriptable(obj: Any?, scope: Any?): Any? =
        NativeJavaObject(
            asScriptable(scope),
            obj,
            TypeInfoFactory.GLOBAL.create(Scriptable::class.java)
        )

    override fun readICode(iCode: ByteArray, hash: ByteArray?): JsScript? {
        val script = ScriptFileStream.readICode(iCode, hash) as? Script ?: return null
        return JsScript(script)
    }

    override fun writeICode(script: JsScript, hash: ByteArray): ByteArray =
        ScriptFileStream.writeICode(script.impl, hash)

    /** 脚本侧传来的作用域可能是原生 Scriptable，也可能被包成 [JsScope]。 */
    private fun asScriptable(any: Any?): Scriptable? = when (any) {
        null -> null
        is JsScope -> asScriptable(any.impl)
        is Scriptable -> any
        is NativeJavaObject -> asScriptable(any.unwrap())
        else -> null
    }


    companion object {
        init {
            // 和迁移前的 ScriptManager 静态初始化一致：V8 风格堆栈 + 补 emptyArgs
            RhinoException.setStackStyle(StackStyle.V8)
            if (Context.emptyArgs == null) {
                try {
                    val field = Context::class.java.getDeclaredField("emptyArgs")
                    field.isAccessible = true
                    field.set(null, ScriptRuntime.emptyArgs)
                } catch (e: NoSuchFieldException) {
                    Log.e("CADebug", e.toString())
                } catch (e: IllegalAccessException) {
                    Log.e("CADebug", e.toString())
                }
            }
        }
    }
}
