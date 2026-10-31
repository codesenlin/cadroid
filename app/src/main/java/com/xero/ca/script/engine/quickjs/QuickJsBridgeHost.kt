package com.xero.ca.script.engine.quickjs

import com.quickjs.JSArray
import com.quickjs.JSFunction
import com.quickjs.JSObject
import com.quickjs.JSValue
import org.json.JSONArray
import org.json.JSONObject
import java.lang.reflect.Array as JArray
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.IdentityHashMap

/**
 * QuickJS 侧的 Java 桥宿主（E2 第一步）。
 *
 * 设计：所有值都带类型标签在 JSON 里传递——
 *   {t:"null"} / {t:"b",v:true} / {t:"i",v:123} / {t:"d",v:1.5}
 *   {t:"s",v:"..."} / {t:"h",v:handle} / {t:"c",v:"java.lang.String"} / {t:"a",v:[...]}
 *
 * handle 是宿主注册表里的 Java 对象 id；Class 用类名引用。
 *
 * 注意：这是最小实现，不支持 Java 泛型、注解、反射代理对象（Proxy）、
 * 也不能把 JS 对象直接传给 Java 方法——这些是 E2 后续要补的。
 */
class QuickJsBridgeHost(scriptInterface: Any) {

    /** 引擎注入：在 JS 线程上调用一个 JS 函数（fn, argsJson）→ Java 值 */
    var jsCall: ((JSFunction, String) -> Any?)? = null

    /** 引擎注入：用于把 JS 值转成字符串等（JS 线程上调用） */
    var jsContext: com.quickjs.JSContext? = null

    private val lock = Any()

    /** 高频桥日志开关：默认关闭（每次 Java 调用都打日志会显著拖慢真实脚本）。 */
    private val VERBOSE = false
    private val byId = LinkedHashMap<Int, Any>()
    private val byObj = IdentityHashMap<Any, Int>()
    private var nextId = 1

    val scriptInterfaceHandle: Int = register(scriptInterface)

    /** 把一个 Java 对象登记到 handle 表（给引擎包装 JS 值用）。 */
    fun registerValue(obj: Any): Int = register(obj)

    /** handle → Java 对象。 */
    fun resolve(handle: Int): Any? = synchronized(lock) { byId[handle] }

    /**
     * 用 JS 对象实现一个 Java 接口（Rhino 的 `new java.lang.Runnable({...})`）。
     *
     * 回调时：Java 方法参数 → JSON 编码 → [callJs] 调 JS 函数 → 返回值转回 Java。
     *
     * @return 代理对象的 handle
     */
    fun newAdapter(className: String, impl: JSObject): Int {
        val cls = classOf(className) ?: throw ClassNotFoundException(className)
        if (!cls.isInterface) throw IllegalArgumentException("不是接口: $className")
        val proxy = java.lang.reflect.Proxy.newProxyInstance(cls.classLoader, arrayOf(cls)) { _, method, args ->
            val call = jsCall ?: throw IllegalStateException("jsCall 未注入")
            if (method.name == "toString" && method.parameterCount == 0) {
                "JSAdapter(${impl.hashCode()})"
            } else if (method.name == "hashCode" && method.parameterCount == 0) {
                impl.hashCode()
            } else if (method.name == "equals" && method.parameterCount == 1) {
                false
            } else {
                val fn: JSFunction? = when (impl) {
                    is JSFunction -> impl // new Runnable(function(){...})：单方法接口直接用函数
                    else -> try { impl.get(method.name) as? JSFunction } catch (t: Throwable) { null }
                }
                if (fn != null) {
                    val result = call(fn, encodeArgs(args))
                    if (result == null) defaultValue(method.returnType) else result
                } else {
                    // Rhino 里没实现的方法就是 undefined，不能崩
                    defaultValue(method.returnType)
                }
            }
        }
        return register(proxy)
    }

    /** 把 Java 参数数组编码成 prelude 能 decode 的 JSON（null/b/i/d/s/h/a）。 */
    private fun encodeArgs(args: Array<Any?>?): String {
        val arr = JSONArray()
        for (a in (args ?: emptyArray())) arr.put(encode(a))
        return arr.toString()
    }

    /** 原始值 RPC：call/new 的参数字面量（含 JS 函数/对象）走这里。 */
    fun raw(request: String, args: JSArray?): String {
        return try {
            val req = JSONObject(request)
            val result = when (req.getString("op")) {
                "call" -> callRawTyped(req, args)
                "new" -> handleEncode(newRaw(req.getString("c"), args)!!)
                "put" -> {
                    val obj = value(req.getInt("h")) ?: throw NullPointerException("null handle")
                    val name = req.getString("n")
                    val raw = args ?: throw IllegalArgumentException("put 缺少参数")
                    val f = findFieldCached(obj.javaClass, name, false)
                    if (f != null) {
                        f.set(obj, convertRaw(raw, 0, f.type))
                    } else {
                        val setter = beanSetterCached(obj.javaClass, name)
                            ?: throw NoSuchFieldException(obj.javaClass.name + "." + name)
                        setter.isAccessible = true
                        setter.invoke(obj, convertRaw(raw, 0, setter.parameterTypes[0]))
                    }
                    retNull()
                }
                else -> throw IllegalArgumentException("raw 不支持: " + req.getString("op"))
            }
            JSONObject().put("ok", true).put("v", result).toString()
        } catch (t: Throwable) {
            val cause = if (t is java.lang.reflect.InvocationTargetException && t.targetException != null) t.targetException else t
            val targetInfo = try {
                val req = JSONObject(request)
                val objInfo = if (req.has("h")) " obj=" + (resolve(req.getInt("h"))?.javaClass?.name ?: "null") else ""
                val argInfo = try {
                    if (req.has("a")) "" else ""
                } catch (t3: Throwable) { "" }
                objInfo
            } catch (t2: Throwable) { "" }
            val argDetail = try {
                val arr = args
                if (arr == null) "" else " args=" + (0 until arr.length()).joinToString(",") { i ->
                    val t = arr.getType(i)
                    val v = try {
                        when (t) {
                            JSValue.TYPE.STRING -> arr.getString(i)
                            JSValue.TYPE.INTEGER, JSValue.TYPE.DOUBLE -> arr.getDouble(i).toString()
                            JSValue.TYPE.BOOLEAN -> arr.getBoolean(i).toString()
                            else -> (arr.getObject(i)?.toString() ?: "null")
                        }
                    } catch (e: Throwable) { "?" }
                    t.name + ":" + v
                }
            } catch (t4: Throwable) { "" }
            if (VERBOSE) android.util.Log.w("Script", "raw 失败: " + request + targetInfo + argDetail + " -> " + cause, cause)
            JSONObject().put("ok", false).put("err", cause.javaClass.name)
                .put("msg", cause.message ?: cause.toString()).toString()
        }
    }

    /** JS 原始值 → Java 参数（按目标类型转换，函数可以自动适配成接口）。 */
    private fun convertRaw(array: JSArray, index: Int, type: Class<*>): Any? {
        val t = array.getType(index)
        return when (t) {
            JSValue.TYPE.NULL, JSValue.TYPE.UNDEFINED, JSValue.TYPE.UNKNOWN -> if (type.isPrimitive) {
                when (type) {
                    Int::class.javaPrimitiveType -> 0
                    Long::class.javaPrimitiveType -> 0L
                    Short::class.javaPrimitiveType -> 0.toShort()
                    Byte::class.javaPrimitiveType -> 0.toByte()
                    Char::class.javaPrimitiveType -> '\u0000'
                    Float::class.javaPrimitiveType -> 0f
                    Double::class.javaPrimitiveType -> 0.0
                    Boolean::class.javaPrimitiveType -> false
                    else -> null
                }
            } else null
            JSValue.TYPE.INTEGER, JSValue.TYPE.DOUBLE -> {
                val v = array.getDouble(index)
                if (type == java.lang.String::class.java || type == java.lang.CharSequence::class.java) {
                    return if (v == Math.floor(v) && !v.isInfinite()) v.toLong().toString() else v.toString()
                }
                when (type) {
                    Int::class.javaPrimitiveType, java.lang.Integer::class.java -> v.toInt()
                    Long::class.javaPrimitiveType, java.lang.Long::class.java -> v.toLong()
                    Short::class.javaPrimitiveType, java.lang.Short::class.java -> v.toInt().toShort()
                    Byte::class.javaPrimitiveType, java.lang.Byte::class.java -> v.toInt().toByte()
                    Char::class.javaPrimitiveType, java.lang.Character::class.java -> v.toInt().toChar()
                    Float::class.javaPrimitiveType, java.lang.Float::class.java -> v.toFloat()
                    else -> v
                }
            }
            JSValue.TYPE.BOOLEAN -> {
                val b = array.getBoolean(index)
                if (type == java.lang.String::class.java || type == java.lang.CharSequence::class.java) {
                    b.toString()
                } else b
            }
            JSValue.TYPE.STRING -> array.getString(index)
            JSValue.TYPE.JS_ARRAY -> {
                if (type == java.lang.String::class.java || type == java.lang.CharSequence::class.java) {
                    val a = array.getArray(index)
                    val parts = ArrayList<String>()
                    for (i in 0 until a.length()) parts.add(a.getString(i) ?: "null")
                    return parts.joinToString(",")
                }
                if (type.isArray) {
                    val arr = array.getArray(index)
                    val comp = type.componentType
                    val out = JArray.newInstance(comp, arr.length())
                    for (i in 0 until arr.length()) {
                        JArray.set(out, i, convertRaw(arr, i, comp))
                    }
                    out
                } else array.getArray(index)
            }
            JSValue.TYPE.JS_FUNCTION -> {
                val fn = array.getObject(index) as JSFunction
                if (type == java.lang.Class::class.java) {
                    val name = fn.get("__name") as? String
                        ?: throw IllegalArgumentException("不是 Java 类: " + fn)
                    return classOf(name) ?: throw ClassNotFoundException(name)
                }
                if (type == java.lang.Object::class.java) {
                    return fn
                }
                if (type.isInterface) {
                    val adapted = newFunctionAdapter(type, fn)
                    android.util.Log.i("Script", "convertFn->interface " + type.name + " => " + adapted.javaClass.name)
                    return adapted
                } else if (type == java.lang.String::class.java || type == java.lang.CharSequence::class.java) {
                    jsToString(fn)
                } else {
                    throw IllegalArgumentException("Java 参数不是接口，不能接收 JS 函数: " + type.name)
                }
            }
            JSValue.TYPE.JS_OBJECT -> {
                val obj = array.getObject(index)
                val handleVal = try { obj.get("__handle") } catch (t: Throwable) { null }
                if (handleVal is Number) {
                    val java = resolve(handleVal.toInt())
                    if (java != null) {
                        if (type.isPrimitive) {
                            return when {
                                java is Number && type == Int::class.javaPrimitiveType -> java.toInt()
                                java is Number && type == Long::class.javaPrimitiveType -> java.toLong()
                                java is Number && type == Short::class.javaPrimitiveType -> java.toInt().toShort()
                                java is Number && type == Byte::class.javaPrimitiveType -> java.toInt().toByte()
                                java is Number && type == Float::class.javaPrimitiveType -> java.toFloat()
                                java is Number && type == Double::class.javaPrimitiveType -> java.toDouble()
                                java is Number && type == Char::class.javaPrimitiveType -> java.toInt().toChar()
                                java is Boolean && type == Boolean::class.javaPrimitiveType -> java
                                java is Number -> java
                                else -> null
                            }
                        }
                        if (type.isInstance(java)) return java
                    }
                }
                if (type == java.lang.String::class.java || type == java.lang.CharSequence::class.java) {
                    return jsToString(obj)
                }
                if (type.isInterface) {
                    return newObjectAdapter(type, obj)
                }
                val _unused = handleVal
                if (handleVal is Number) {
                    val java = resolve(handleVal.toInt())
                    if (java != null && type.isInstance(java)) java
                    else throw IllegalArgumentException("对象类型不匹配: 期望 " + type.name)
                } else if (type == java.lang.Object::class.java) {
                    obj
                } else {
                    throw IllegalArgumentException("不能用 JS 对象作为参数: " + type.name)
                }
            }
            else -> throw IllegalArgumentException("不支持的参数类型: " + t)
        }
    }

    /** 用 JS 的 String(value) 把任意 JS 值转成字符串（Rhino 的隐式 toString）。 */
    private fun jsToString(value: JSObject): String {
        val ctx = jsContext ?: throw IllegalStateException("jsContext 未注入")
        val arr = JSArray(ctx)
        arr.push(value)
        return ctx.executeStringFunction("String", arr)
    }

    /** JS 对象实现接口：Java 调方法时按名字从 impl 上取 JS 函数执行；没实现的方法返回默认值。 */
    private fun newObjectAdapter(type: Class<*>, impl: JSObject): Any {
        val call = jsCall ?: throw IllegalStateException("jsCall 未注入")
        return java.lang.reflect.Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args ->
            if (method.name == "toString" && method.parameterCount == 0) {
                "JSObject(${impl.hashCode()})"
            } else if (method.name == "hashCode" && method.parameterCount == 0) {
                impl.hashCode()
            } else if (method.name == "equals" && method.parameterCount == 1) {
                false
            } else {
                val fn = try { impl.get(method.name) } catch (t: Throwable) { null }
                if (fn is JSFunction) {
                    val result = call(fn, encodeArgs(args))
                    if (result == null) defaultValue(method.returnType) else result
                } else {
                    defaultValue(method.returnType)
                }
            }
        }
    }

    /** 接口方法返回类型的默认值（JS 侧没实现时用，对应 Rhino 的 undefined）。 */
    private fun defaultValue(type: Class<*>): Any? = when {
        type == Void.TYPE -> null
        type == Boolean::class.javaPrimitiveType -> false
        type == Int::class.javaPrimitiveType -> 0
        type == Long::class.javaPrimitiveType -> 0L
        type == Short::class.javaPrimitiveType -> 0.toShort()
        type == Byte::class.javaPrimitiveType -> 0.toByte()
        type == Float::class.javaPrimitiveType -> 0f
        type == Double::class.javaPrimitiveType -> 0.0
        type == Char::class.javaPrimitiveType -> '\u0000'
        else -> null
    }

    /** 单方法接口（SAM）的函数适配器；多方法接口暂时不支持。 */
    private fun newFunctionAdapter(type: Class<*>, fn: JSFunction): Any {
        val call = jsCall ?: throw IllegalStateException("jsCall 未注入")
        return java.lang.reflect.Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args ->
            // Object 的方法不能转发给 JS 函数！toString/hashCode 是 Java 侧调试/日志
            // 会调用的（我们的 invoke 日志、Logcat 都会碰），转发过去等于凭空执行 JS
            when {
                method.name == "toString" && method.parameterCount == 0 -> "JSAdapter(${fn.hashCode()})"
                method.name == "hashCode" && method.parameterCount == 0 -> fn.hashCode()
                method.name == "equals" && method.parameterCount == 1 -> false
                else -> {
                    val result = call(fn, encodeArgs(args))
                    if (result == null) defaultValue(method.returnType) else result
                }
            }
        }
    }

    private fun callRawTyped(req: JSONObject, args: JSArray?): JSONObject {
        val name = req.getString("n")
        val n = rawArgs(args)
        return if (req.has("c")) {
            // 静态调用：c = 类名
            val cls = classOf(req.getString("c")) ?: throw ClassNotFoundException(req.getString("c"))
            val methods = methodsNamed(cls, name, true, n)
            val m = pickRaw(methods, args, n)
                ?: throw NoSuchMethodException(cls.name + "." + name + " ($n 参数)")
            m.isAccessible = true
            encodeTyped(m.invoke(null, *buildRawArgs(m.parameterTypes, m.isVarArgs, args, n)), m.returnType)
        } else {
            // 实例调用：h = 宿主对象（哪怕它是 java.lang.Class 的实例，也按实例方法调）
            val obj = value(req.getInt("h")) ?: throw NullPointerException("null handle")
            val cls = obj.javaClass
            val methods = methodsNamed(cls, name, false, n)
            val m = pickRaw(methods, args, n)
            if (m == null) {
                val sameName = cls.methods.filter { it.name == name }
                if (VERBOSE) android.util.Log.w("Script", "rawCall 找不到方法: " + cls.name + "." + name +
                    " n=" + n + " 候选=" + methods.size + " 同名方法=" + sameName.map { it.parameterTypes.joinToString(",") { t -> t.name } })
            }
            if (m != null) {
                m.isAccessible = true
                var stage = "build"
            try {
                val callArgs = buildRawArgs(m.parameterTypes, m.isVarArgs, args, n)
                if (name == "setAdapter") {
                    if (VERBOSE) android.util.Log.i("Script", "setAdapter 参数类: " +
                        callArgs.joinToString(",") { a -> a?.javaClass?.name ?: "null" } +
                        " 声明类型: " + m.parameterTypes.joinToString(",") { it.name })
                }
                stage = "invoke" 
                val argTypes = callArgs.joinToString(",") { a ->
                    if (a == null) "null" else a.javaClass.name
                }
                if (VERBOSE) android.util.Log.i("Script", "invoke " + m.declaringClass.name + "." + m.name +
                    " 参数=" + m.parameterTypes.joinToString(",") { it.name } +
                    " 实际=" + argTypes + " varargs=" + m.isVarArgs +
                    " 值=" + callArgs.joinToString(" | ") { a ->
                        when (a) {
                            null -> "null"
                            is Array<*> -> a.contentToString()
                            else -> a.toString()
                        }
                    })
                encodeTyped(m.invoke(obj, *callArgs), m.returnType)
            } catch (t: Throwable) {
                android.util.Log.w("Script", "invoke 失败[阶段=" + stage + "]: " + m.declaringClass.name + "." + m.name +
                    " 参数=" + m.parameterTypes.joinToString(",") { it.name }, t)
                throw t
            }
            } else {
                // Rhino 也允许 obj.staticMethod() 这种写法
                val staticMethods = methodsNamed(cls, name, true, n)
                val sm = pickRaw(staticMethods, args, n)
                    ?: throw NoSuchMethodException(cls.name + "." + name + " ($n 参数)")
                sm.isAccessible = true
                encodeTyped(sm.invoke(null, *buildRawArgs(sm.parameterTypes, sm.isVarArgs, args, n)), sm.returnType)
            }
        }
    }

    private fun rawArgs(args: JSArray?): Int = args?.length() ?: 0

    private fun callRawStatic(className: String, name: String, args: JSArray?): Any? {
        val cls = classOf(className) ?: throw ClassNotFoundException(className)
        val n = rawArgs(args)
        val methods = cls.methods.filter {
            it.name == name && Modifier.isStatic(it.modifiers) &&
                (it.parameterCount == n || (it.isVarArgs && n >= it.parameterCount - 1))
        }
        val m = pickRaw(methods, args, n) ?: throw NoSuchMethodException("$className.$name ($n 参数)")
        val callArgs = buildRawArgs(m.parameterTypes, m.isVarArgs, args, n)
        return m.invoke(null, *callArgs)
    }

    private fun callRawInstance(handle: Int, name: String, args: JSArray?): Any? {
        val obj = value(handle) ?: throw NullPointerException("null handle")
        val n = rawArgs(args)
        val methods = obj.javaClass.methods.filter {
            it.name == name && !Modifier.isStatic(it.modifiers) &&
                (it.parameterCount == n || (it.isVarArgs && n >= it.parameterCount - 1))
        }
        val m = pickRaw(methods, args, n) ?: throw NoSuchMethodException(obj.javaClass.name + "." + name + " ($n 参数)")
        m.isAccessible = true
        return m.invoke(obj, *buildRawArgs(m.parameterTypes, m.isVarArgs, args, n))
    }

    private fun newRaw(className: String, args: JSArray?): Any? {
        val cls = classOf(className) ?: throw ClassNotFoundException(className)
        val n = rawArgs(args)
        val ckey = cls.name + "#" + n
        val ctors = ctorCache[ckey] ?: cls.declaredConstructors.filter {
            it.parameterCount == n || (it.isVarArgs && n >= it.parameterCount - 1)
        }.also { ctorCache[ckey] = it }
        val c = pickRaw(ctors, args, n) ?: throw NoSuchMethodException("$className 构造器 ($n 参数)")
        c.isAccessible = true
        return c.newInstance(*buildRawArgs(c.parameterTypes, c.isVarArgs, args, n))
    }

    private fun <T : Executable> pickRaw(candidates: List<T>, args: JSArray?, n: Int): T? {
        var best: T? = null
        var bestScore = -1
        for (c in candidates) {
            var score = 0
            var ok = true
            val types = c.parameterTypes
            val fixed = if (c.isVarArgs) types.size - 1 else types.size
            for (i in 0 until fixed) {
                val sc = rawScore(args, i, types[i])
                if (sc < 0) { ok = false; break }
                score += sc
            }
            if (ok && c.isVarArgs) {
                val comp = types[types.size - 1].componentType
                if (isArrayFilledVararg(args, fixed, n)) {
                    val arr = args!!.getArray(fixed)
                    for (i in 0 until arr.length()) {
                        val sc = rawScore(arr, i, comp)
                        if (sc < 0) { ok = false; break }
                        score += sc
                    }
                } else {
                    for (i in fixed until n) {
                        val sc = rawScore(args, i, comp)
                        if (sc < 0) { ok = false; break }
                        score += sc
                    }
                }
            }
            if (ok && score > bestScore) { bestScore = score; best = c }
        }
        return best
    }

    private fun rawScore(args: JSArray?, index: Int, type: Class<*>): Int {
        if (args == null) return -1
        return when (args.getType(index)) {
            JSValue.TYPE.NULL, JSValue.TYPE.UNDEFINED, JSValue.TYPE.UNKNOWN -> if (type.isPrimitive) -1 else 1
            JSValue.TYPE.INTEGER, JSValue.TYPE.DOUBLE -> when {
                type == Double::class.javaPrimitiveType || type == Float::class.javaPrimitiveType -> 4
                type == Int::class.javaPrimitiveType || type == Long::class.javaPrimitiveType -> 4
                type == java.lang.Number::class.java || type == java.lang.Object::class.java -> 2
                type.isPrimitive -> 1
                else -> -1
            }
            JSValue.TYPE.BOOLEAN -> if (type == Boolean::class.javaPrimitiveType || type == java.lang.Boolean::class.java) 4 else -1
            JSValue.TYPE.STRING -> when {
                type == java.lang.String::class.java -> 4
                type == java.lang.CharSequence::class.java || type == java.lang.Object::class.java -> 2
                else -> -1
            }
            JSValue.TYPE.JS_FUNCTION -> when {
                type == java.lang.Class::class.java -> 2
                type.isInterface -> 3
                type == java.lang.String::class.java || type == java.lang.CharSequence::class.java -> 1
                type == java.lang.Object::class.java -> 1
                else -> -1
            }
            JSValue.TYPE.JS_ARRAY -> if (type.isArray || type == java.lang.Object::class.java) 2 else -1
            JSValue.TYPE.JS_OBJECT -> {
                val objForNull = args.getObject(index)
                if (objForNull == null) {
                    return if (type.isPrimitive) -1 else 1
                }
                if (type == java.lang.String::class.java || type == java.lang.CharSequence::class.java) {
                    1
                } else {
                    val obj = objForNull
                    val h = try { obj.get("__handle") } catch (t: Throwable) { null }
                    if (h is Number) {
                        val java = resolve(h.toInt())
                        when {
                            java == null -> -1
                            type.isInstance(java) -> 4
                            type.isPrimitive && java is Number -> 2
                            type.isPrimitive && java is Boolean -> 2
                            type.isPrimitive && java is Char -> 2
                            else -> -1
                        }
                    } else if (type == java.lang.Object::class.java) 1
                    else if (type.isInterface) 3
                    else -1
                }
            }
            else -> -1
        }
    }

    /** varargs 的最后一个参数直接传了一个 JS 数组（如 getMethod(name, [Class...])） */
    private fun isArrayFilledVararg(args: JSArray?, fixed: Int, n: Int): Boolean =
        args != null && n == fixed + 1 && args.getType(fixed) == JSValue.TYPE.JS_ARRAY

    private fun buildRawArgs(types: Array<Class<*>>, isVarArgs: Boolean, args: JSArray?, n: Int): Array<Any?> {
        if (!isVarArgs) return Array(types.size) { convertRaw(args!!, it, types[it]) }
        val fixed = types.size - 1
        val out = arrayOfNulls<Any?>(types.size)
        for (i in 0 until fixed) out[i] = convertRaw(args!!, i, types[i])
        val comp = types[fixed].componentType
        if (isArrayFilledVararg(args, fixed, n)) {
            val src = args!!.getArray(fixed)
            val arr = JArray.newInstance(comp, src.length())
            for (i in 0 until src.length()) JArray.set(arr, i, convertRaw(src, i, comp))
            out[fixed] = arr
        } else {
            val tail = (n - fixed).coerceAtLeast(0)
            val tailArr = JArray.newInstance(comp, tail)
            for (i in 0 until tail) JArray.set(tailArr, i, convertRaw(args!!, fixed + i, comp))
            out[fixed] = tailArr
        }
        return out
    }

    private val methodCache = java.util.concurrent.ConcurrentHashMap<String, List<Method>>()
    private val fieldCache = java.util.concurrent.ConcurrentHashMap<String, Any>()
    private val beanGetterCache = java.util.concurrent.ConcurrentHashMap<String, Any>()
    private val beanSetterCache = java.util.concurrent.ConcurrentHashMap<String, Any>()
    private val hasMethodCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    private val ctorCache = java.util.concurrent.ConcurrentHashMap<String, List<java.lang.reflect.Constructor<*>>>()
    private val NONE = Any()

    private fun findFieldCached(cls: Class<*>, name: String, static: Boolean): Field? {
        val key = cls.name + "#" + name + "#" + static
        val hit = fieldCache[key]
        if (hit != null) return if (hit === NONE) null else hit as Field
        val f = findField(cls, name, static)
        fieldCache[key] = f ?: NONE
        return f
    }

    private fun beanGetterCached(cls: Class<*>, name: String): Method? {
        val key = cls.name + "#" + name
        val hit = beanGetterCache[key]
        if (hit != null) return if (hit === NONE) null else hit as Method
        val m = findBeanGetter(cls, name)
        beanGetterCache[key] = m ?: NONE
        return m
    }

    private fun beanSetterCached(cls: Class<*>, name: String): Method? {
        val key = cls.name + "#" + name
        val hit = beanSetterCache[key]
        if (hit != null) return if (hit === NONE) null else hit as Method
        val m = findBeanSetter(cls, name)
        beanSetterCache[key] = m ?: NONE
        return m
    }

    private fun hasMethodCached(cls: Class<*>, name: String, static: Boolean): Boolean {
        val key = cls.name + "#" + name + "#" + static
        hasMethodCache[key]?.let { return it }
        val r = hasMethod(cls, name, static)
        hasMethodCache[key] = r
        return r
    }

    private fun methodsNamed(cls: Class<*>, name: String, static: Boolean, n: Int): List<Method> {
        val key = cls.name + "#" + name + "#" + static + "#" + n
        methodCache[key]?.let { return it }
        val list = cls.methods.filter {
            it.name == name && Modifier.isStatic(it.modifiers) == static && isRealMethod(it) &&
                (it.parameterCount == n || (it.isVarArgs && n >= it.parameterCount - 1))
        }
        methodCache[key] = list
        return list
    }

    fun call(request: String): String {
        return try {
            val req = JSONObject(request)
            val result = when (req.getString("op")) {
                "init" -> JSONObject().put("ok", true).put("handle", scriptInterfaceHandle)
                "primitive" -> {
                    val obj = value(req.getInt("h")) ?: throw NullPointerException("null handle")
                    when (obj) {
                        is Int -> retInt(obj)
                        is Long -> JSONObject().put("t", "i").put("v", obj)
                        is Short -> retInt(obj.toInt())
                        is Byte -> retInt(obj.toInt())
                        is Double -> JSONObject().put("t", "d").put("v", obj)
                        is Float -> JSONObject().put("t", "d").put("v", obj.toDouble())
                        is Boolean -> retBool(obj)
                        is CharSequence -> retStr(obj.toString())
                        is Char -> retStr(obj.toString())
                        else -> throw IllegalStateException("不是基础类型: " + obj.javaClass.name)
                    }
                }
                "log" -> {
                    android.util.Log.i("Script", "JS bridge: " + req.optString("m"))
                    retNull()
                }
                "classExists" -> retBool(classOf(req.getString("c")) != null)
                "classRef" -> {
                    val cls = classOf(req.getString("c")) ?: throw ClassNotFoundException(req.getString("c"))
                    JSONObject().put("t", "i").put("v", register(cls))
                }
                "classInfo" -> retClassInfo(req.getString("c"))
                "new" -> retValue(create(req.getString("c"), req.optJSONArray("a")))
                "getStatic" -> {
                    val cls = classOf(req.getString("c")) ?: throw ClassNotFoundException(req.getString("c"))
                    val n = req.getString("n")
                    val f = findFieldCached(cls, n, true)
                    if (f != null) {
                        encodeTyped(f.get(null), f.type)
                    } else if (hasMethodCached(cls, n, true)) {
                        methodMarker()
                    } else {
                        // 嵌套类：android.widget.AbsListView.LayoutParams → AbsListView$LayoutParams
                        val nested = classOf(cls.name + "$" + n)
                        if (nested != null) JSONObject().put("t", "c").put("v", nested.name)
                        else throw NoSuchFieldException(cls.name + "." + n)
                    }
                }
                "putStatic" -> {
                    setStatic(req.getString("c"), req.getString("n"), req.optJSONObject("v"))
                    retNull()
                }
                "get" -> {
                    val obj = value(req.getInt("h")) ?: throw NullPointerException("null handle")
                    val n = req.getString("n")
                    val f = findFieldCached(obj.javaClass, n, false)
                    if (f != null) {
                        encodeTyped(f.get(obj), f.type)
                    } else {
                        // Rhino 允许 obj.STATIC_FIELD 这种写法
                        val sf = findFieldCached(obj.javaClass, n, true)
                        if (sf != null) {
                            encodeTyped(sf.get(null), sf.type)
                        } else {
                            // JavaBean 属性：view.tag ↔ getTag()/setTag()
                            val getter = beanGetterCached(obj.javaClass, n)
                            if (getter != null) {
                                getter.isAccessible = true
                                encodeTyped(getter.invoke(obj), getter.returnType)
                            } else if (hasMethodCached(obj.javaClass, n, false) || hasMethodCached(obj.javaClass, n, true)) {
                                methodMarker()
                            } else {
                                throw NoSuchFieldException(obj.javaClass.name + "." + n)
                            }
                        }
                    }
                }
                "put" -> {
                    setField(req.getInt("h"), req.getString("n"), req.optJSONObject("v"))
                    retNull()
                }
                "call" -> retValue(
                    if (req.has("c")) callStatic(req.getString("c"), req.getString("n"), req.optJSONArray("a"))
                    else callInstance(req.getInt("h"), req.getString("n"), req.optJSONArray("a"))
                )
                "className" -> retStr(className(req.getInt("h")))
                "isInstance" -> retBool(isInstance(req.getInt("h"), req.getString("c")))
                "classOf" -> retClassObject(req.getString("c"))
                "arrayLength" -> retInt(JArray.getLength(arrayValue(req.getInt("h"))))
                "arrayGet" -> retValue(arrayGet(req.getInt("h"), req.getInt("i")))
                "arraySet" -> {
                    arraySet(req.getInt("h"), req.getInt("i"), req.optJSONObject("v"))
                    retNull()
                }
                "release" -> {
                    synchronized(lock) { byId.remove(req.getInt("h"))?.let { byObj.remove(it) } }
                    retNull()
                }
                else -> error("unknown op: " + req.getString("op"))
            }
            if (result.has("handle") && result.has("ok")) result.toString()
            else JSONObject().put("ok", true).put("v", result).toString()
        } catch (t: Throwable) {
            val cause = if (t is java.lang.reflect.InvocationTargetException && t.targetException != null) t.targetException else t
            JSONObject()
                .put("ok", false)
                .put("err", cause.javaClass.name)
                .put("msg", cause.message ?: cause.toString())
                .toString()
        }
    }

    // ---------- 值编解码 ----------

    private fun retNull(): JSONObject = JSONObject().put("t", "null")

    private fun retBool(v: Boolean): JSONObject = JSONObject().put("t", "b").put("v", v)

    private fun retInt(v: Int): JSONObject = JSONObject().put("t", "i").put("v", v.toLong())

    private fun retStr(v: String): JSONObject = JSONObject().put("t", "s").put("v", v)

    private fun retClassObject(name: String): JSONObject {
        val cls = classOf(name) ?: throw ClassNotFoundException(name)
        return JSONObject().put("t", "h").put("v", register(cls)).put("cls", "java.lang.Class")
    }

    private fun retClassInfo(name: String): JSONObject {
        val cls = classOf(name) ?: throw ClassNotFoundException(name)
        val info = JSONObject()
        info.put("name", cls.name)
        info.put("interface", cls.isInterface)
        info.put("enum", cls.isEnum)
        info.put("array", cls.isArray)
        info.put("primitive", cls.isPrimitive)
        val supers = JSONArray()
        var s: Class<*>? = cls.superclass
        while (s != null) {
            supers.put(s.name)
            s = s.superclass
        }
        info.put("supers", supers)
        val ifaces = JSONArray()
        for (i in cls.interfaces) ifaces.put(i.name)
        info.put("interfaces", ifaces)
        return JSONObject().put("t", "c").put("v", name).put("info", info)
    }

    private fun retValue(v: Any?): JSONObject = encode(v)

    /** 强制包成宿主对象（构造函数 / 对象类型返回值）。 */
    private fun handleEncode(v: Any): JSONObject =
        JSONObject().put("t", "h").put("v", register(v)).put("cls", v.javaClass.name)

    /**
     * 按声明类型编码：基本类型/字符串/数组走 JS 原生值；
     * 对象类型（包括 Integer/Long/Boolean 这些包装类）包成 handle，
     * 这样脚本才能调用 `.intValue()` 这类方法（Rhino 的 NativeJavaObject 行为）。
     */
    private fun encodeTyped(v: Any?, type: Class<*>): JSONObject {
        if (v == null) return encode(null)
        return when {
            type.isPrimitive -> encode(v)
            type == java.lang.String::class.java || type == java.lang.CharSequence::class.java -> encode(v)
            v.javaClass.isArray -> encode(v)
            type == java.lang.Class::class.java -> encode(v)
            else -> handleEncode(v)
        }
    }

    private fun encode(v: Any?): JSONObject {
        val o = JSONObject()
        when (v) {
            null -> o.put("t", "null")
            is Boolean -> o.put("t", "b").put("v", v)
            is Int, is Short, is Byte -> o.put("t", "i").put("v", (v as Number).toLong())
            is Long -> o.put("t", "i").put("v", v)
            is Float, is Double -> o.put("t", "d").put("v", (v as Number).toDouble())
            is Char -> o.put("t", "s").put("v", v.toString())
            is String -> o.put("t", "s").put("v", v)
            // Class 值统一编码成类名：prelude 的 getClass(name) 有缓存，
            // 于是 `cls == java.lang.Object` 这类比较、以及 getMethod 等实例方法都能正常工作
            is Class<*> -> o.put("t", "c").put("v", v.name)
            else -> if (v.javaClass.isArray) {
                val len = JArray.getLength(v)
                val arr = JSONArray()
                for (i in 0 until len) arr.put(encode(JArray.get(v, i)))
                o.put("t", "a").put("v", arr)
            } else {
                o.put("t", "h").put("v", register(v)).put("cls", v.javaClass.name)
            }
        }
        return o
    }

    private fun value(handle: Int): Any? = synchronized(lock) {
        byId[handle] ?: throw IllegalArgumentException("无效 handle: $handle")
    }

    private fun decode(j: JSONObject?): Any? {
        if (j == null) return null
        return when (j.optString("t", "null")) {
            "null" -> null
            "b" -> j.getBoolean("v")
            "i" -> j.getLong("v")
            "d" -> j.getDouble("v")
            "s" -> j.getString("v")
            "c" -> classOf(j.getString("v"))
            "h" -> value(j.getInt("v"))
            "a" -> {
                val arr = j.getJSONArray("v")
                // 未知目标类型时先包成 Object[]，真正转换在参数匹配时做
                Array(arr.length()) { decode(arr.optJSONObject(it)) }
            }
            else -> throw IllegalArgumentException("未知值类型: " + j.optString("t"))
        }
    }

    private fun register(o: Any): Int = synchronized(lock) {
        byObj[o]?.let { return it }
        val id = nextId++
        byId[id] = o
        byObj[o] = id
        id
    }

    // ---------- 反射 ----------

    private fun classOf(name: String): Class<*>? = try {
        when (name) {
            "boolean" -> Boolean::class.javaPrimitiveType
            "byte" -> Byte::class.javaPrimitiveType
            "char" -> Char::class.javaPrimitiveType
            "short" -> Short::class.javaPrimitiveType
            "int" -> Int::class.javaPrimitiveType
            "long" -> Long::class.javaPrimitiveType
            "float" -> Float::class.javaPrimitiveType
            "double" -> Double::class.javaPrimitiveType
            "void" -> Void.TYPE
            else -> Class.forName(name)
        }
    } catch (e: Throwable) {
        null
    }

    private fun className(handle: Int): String = value(handle)!!.javaClass.name

    private fun isInstance(handle: Int, name: String): Boolean {
        val cls = classOf(name) ?: return false
        return cls.isInstance(value(handle))
    }

    private fun objectOfClass(name: String): Any {
        val cls = classOf(name) ?: throw ClassNotFoundException(name)
        return cls
    }

    private fun getStatic(className: String, name: String): Any? {
        val cls = classOf(className) ?: throw ClassNotFoundException(className)
        val f = findField(cls, name, true) ?: throw NoSuchFieldException("$className.$name")
        return f.get(null)
    }

    private fun setStatic(className: String, name: String, v: JSONObject?) {
        val cls = classOf(className) ?: throw ClassNotFoundException(className)
        val f = findField(cls, name, true) ?: throw NoSuchFieldException("$className.$name")
        f.set(null, convert(v, f.type))
    }

    private fun getField(handle: Int, name: String): Any? {
        val obj = value(handle) ?: throw NullPointerException("null handle")
        val f = findField(obj.javaClass, name, false) ?: throw NoSuchFieldException(obj.javaClass.name + "." + name)
        return f.get(obj)
    }

    private fun setField(handle: Int, name: String, v: JSONObject?) {
        val obj = value(handle) ?: throw NullPointerException("null handle")
        val f = findField(obj.javaClass, name, false) ?: throw NoSuchFieldException(obj.javaClass.name + "." + name)
        f.set(obj, convert(v, f.type))
    }

    private fun methodMarker(): JSONObject = JSONObject().put("t", "method")

    private fun beanSuffix(name: String): String =
        name.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

    /** 过滤 bridge/synthetic 方法：桥接方法参数是泛型擦除版本（如 AdapterView.setAdapter(Adapter)），选中会触发内部强转。 */
    private fun isRealMethod(e: java.lang.reflect.Executable): Boolean =
        !(e is Method && (e.isBridge || e.isSynthetic))

    private fun findBeanGetter(cls: Class<*>, name: String): Method? {
        val suffix = beanSuffix(name)
        for (prefix in listOf("get", "is")) {
            val m = try {
                cls.getMethod(prefix + suffix)
            } catch (e: Throwable) {
                null
            }
            if (m != null && m.parameterCount == 0 && !Modifier.isStatic(m.modifiers) && m.returnType != Void.TYPE) return m
        }
        return null
    }

    private fun findBeanSetter(cls: Class<*>, name: String): Method? {
        val suffix = beanSuffix(name)
        val m = try {
            cls.getMethod("set" + suffix, Any::class.java)
        } catch (e: Throwable) {
            null
        }
        return m?.takeIf { !Modifier.isStatic(it.modifiers) && it.parameterCount == 1 }
            ?: cls.methods.firstOrNull {
                it.name == "set" + suffix && !Modifier.isStatic(it.modifiers) && it.parameterCount == 1
            }
    }

    private fun hasMethod(cls: Class<*>, name: String, static: Boolean): Boolean =
        cls.methods.any { it.name == name && Modifier.isStatic(it.modifiers) == static && isRealMethod(it) }

    private fun findField(cls: Class<*>, name: String, static: Boolean): Field? {
        var c: Class<*>? = cls
        while (c != null) {
            for (f in c.declaredFields) {
                if (f.name == name && Modifier.isStatic(f.modifiers) == static) {
                    f.isAccessible = true
                    return f
                }
            }
            // 接口里的常量字段（如 Spanned.SPAN_INCLUSIVE_EXCLUSIVE）不会被实现类继承，
            // 但 Java 反射的 getFields() 语义里是可见的，这里补上接口递归
            for (i in c.interfaces) {
                val f = findField(i, name, static)
                if (f != null) return f
            }
            c = c.superclass
        }
        return null
    }

    private fun create(className: String, args: JSONArray?): Any? {
        val cls = classOf(className) ?: throw ClassNotFoundException(className)
        val arr = toArgArray(args)
        val ctors = cls.declaredConstructors.filter { matches(it, arr) }
        val ctor = pick(ctors, arr) ?: throw NoSuchMethodException("$className 构造器 (${arr.size} 参数)")
        ctor.isAccessible = true
        return ctor.newInstance(*buildArgs(ctor.parameterTypes, ctor.isVarArgs, arr))
    }

    private fun callStatic(className: String, name: String, args: JSONArray?): Any? {
        val cls = classOf(className) ?: throw ClassNotFoundException(className)
        val arr = toArgArray(args)
        val methods = cls.methods.filter { it.name == name && Modifier.isStatic(it.modifiers) && isRealMethod(it) && matches(it, arr) }
        val m = pick(methods, arr) ?: throw NoSuchMethodException("$className.$name (${arr.size} 参数)")
        return m.invoke(null, *buildArgs(m.parameterTypes, m.isVarArgs, arr))
    }

    private fun callInstance(handle: Int, name: String, args: JSONArray?): Any? {
        val obj = value(handle) ?: throw NullPointerException("null handle")
        val arr = toArgArray(args)
        val methods = obj.javaClass.methods.filter { it.name == name && !Modifier.isStatic(it.modifiers) && isRealMethod(it) && matches(it, arr) }
        val m = pick(methods, arr) ?: throw NoSuchMethodException(obj.javaClass.name + "." + name + " (${arr.size} 参数)")
        m.isAccessible = true
        return m.invoke(obj, *buildArgs(m.parameterTypes, m.isVarArgs, arr))
    }

    /** 参数个数匹配：普通方法要求相等；varargs 允许最后一段为空或更多。 */
    private fun matches(e: Executable, args: List<JSONObject?>): Boolean =
        if (e.isVarArgs) args.size >= e.parameterCount - 1 else e.parameterCount == args.size

    /** 按 varargs 规则把 JSON 参数转成实际调用参数。 */
    private fun buildArgs(types: Array<Class<*>>, isVarArgs: Boolean, arr: List<JSONObject?>): Array<Any?> {
        if (!isVarArgs) return Array(types.size) { convert(arr[it], types[it]) }
        val fixed = types.size - 1
        val out = arrayOfNulls<Any?>(types.size)
        for (i in 0 until fixed) out[i] = convert(arr[i], types[i])
        val comp = types[fixed].componentType
        val tail = (arr.size - fixed).coerceAtLeast(0)
        val tailArr = JArray.newInstance(comp, tail)
        for (i in 0 until tail) JArray.set(tailArr, i, convert(arr[fixed + i], comp))
        out[fixed] = tailArr
        return out
    }

    private fun arrayValue(handle: Int): Any {
        val v = value(handle) ?: throw NullPointerException("null handle")
        if (!v.javaClass.isArray) throw IllegalArgumentException("不是数组: " + v.javaClass.name)
        return v
    }

    private fun arrayGet(handle: Int, i: Int): Any? = JArray.get(arrayValue(handle), i)

    private fun arraySet(handle: Int, i: Int, v: JSONObject?) {
        val arr = arrayValue(handle)
        JArray.set(arr, i, convert(v, arr.javaClass.componentType))
    }

    private fun toArgArray(args: JSONArray?): List<JSONObject?> {
        if (args == null) return emptyList()
        return (0 until args.length()).map { args.optJSONObject(it) }
    }

    private fun <T : Executable> pick(candidates: List<T>, args: List<JSONObject?>): T? {
        if (candidates.isEmpty()) return null
        var best: T? = null
        var bestScore = -1
        for (c in candidates) {
            var score = 0
            var ok = true
            if (c.isVarArgs) {
                val fixed = c.parameterCount - 1
                for (i in 0 until fixed) {
                    val s = scoreOf(args[i], c.parameterTypes[i])
                    if (s < 0) { ok = false; break }
                    score += s
                }
                if (ok) {
                    val comp = c.parameterTypes[fixed].componentType
                    for (i in fixed until args.size) {
                        val s = scoreOf(args[i], comp)
                        if (s < 0) { ok = false; break }
                        score += s
                    }
                }
            } else {
                for (i in c.parameterTypes.indices) {
                    val s = scoreOf(args[i], c.parameterTypes[i])
                    if (s < 0) { ok = false; break }
                    score += s
                }
            }
            if (ok && score > bestScore) { bestScore = score; best = c }
        }
        return best
    }

    private fun scoreOf(arg: JSONObject?, type: Class<*>): Int {
        val t = arg?.optString("t", "null") ?: "null"
        if (t == "null") return if (type.isPrimitive) -1 else 1
        return when (t) {
            "b" -> if (type == Boolean::class.javaPrimitiveType || type == java.lang.Boolean::class.java) 4 else -1
            "i" -> when {
                type == Int::class.javaPrimitiveType -> 4
                type == Long::class.javaPrimitiveType -> 3
                type == Short::class.javaPrimitiveType || type == Byte::class.javaPrimitiveType -> 2
                type == Float::class.javaPrimitiveType || type == Double::class.javaPrimitiveType -> 2
                type == java.lang.Integer::class.java || type == java.lang.Long::class.java -> 3
                type == java.lang.Number::class.java || type == java.lang.Object::class.java -> 1
                else -> -1
            }
            "d" -> when {
                type == Double::class.javaPrimitiveType -> 4
                type == Float::class.javaPrimitiveType -> 3
                type == Int::class.javaPrimitiveType || type == Long::class.javaPrimitiveType -> 2
                type == java.lang.Double::class.java || type == java.lang.Number::class.java
                    || type == java.lang.Object::class.java -> 2
                else -> -1
            }
            "s" -> when {
                type == java.lang.String::class.java -> 4
                type == java.lang.CharSequence::class.java || type == java.lang.Object::class.java -> 2
                else -> -1
            }
            "c" -> if (type == java.lang.Class::class.java || type == java.lang.Object::class.java) 2 else -1
            "h" -> {
                val obj = arg?.let { value(it.getInt("v")) }
                if (obj == null || !type.isInstance(obj)) -1
                else if (obj.javaClass == type) 4
                else if (type.isInterface || type == java.lang.Object::class.java) 3
                else 2
            }
            "a" -> if (type.isArray || type == java.lang.Object::class.java) 2 else -1
            else -> -1
        }
    }

    private fun convertArgs(args: List<JSONObject?>, types: Array<Class<*>>): Array<Any?> =
        Array(types.size) { convert(args[it], types[it]) }

    private fun convert(j: JSONObject?, type: Class<*>): Any? {
        if (j == null) return null
        val t = j.optString("t", "null")
        if (t == "null") return if (type.isPrimitive) 0.toPrimitive(type) else null
        return when (t) {
            "b" -> j.getBoolean("v")
            "i" -> {
                val v = j.getLong("v")
                when (type) {
                    Int::class.javaPrimitiveType, java.lang.Integer::class.java -> v.toInt()
                    Long::class.javaPrimitiveType, java.lang.Long::class.java -> v
                    Short::class.javaPrimitiveType, java.lang.Short::class.java -> v.toInt().toShort()
                    Byte::class.javaPrimitiveType, java.lang.Byte::class.java -> v.toInt().toByte()
                    Char::class.javaPrimitiveType, java.lang.Character::class.java -> v.toInt().toChar()
                    Float::class.javaPrimitiveType, java.lang.Float::class.java -> v.toFloat()
                    Double::class.javaPrimitiveType, java.lang.Double::class.java -> v.toDouble()
                    else -> v
                }
            }
            "d" -> {
                val v = j.getDouble("v")
                when (type) {
                    Float::class.javaPrimitiveType, java.lang.Float::class.java -> v.toFloat()
                    Int::class.javaPrimitiveType, java.lang.Integer::class.java -> v.toInt()
                    Long::class.javaPrimitiveType, java.lang.Long::class.java -> v.toLong()
                    else -> v
                }
            }
            "s" -> j.getString("v")
            "c" -> classOf(j.getString("v"))
            "h" -> value(j.getInt("v"))
            "a" -> {
                val arr = j.getJSONArray("v")
                if (type.isArray) {
                    val comp = type.componentType
                    val out = JArray.newInstance(comp, arr.length())
                    for (i in 0 until arr.length()) JArray.set(out, i, convert(arr.optJSONObject(i), comp))
                    out
                } else {
                    Array(arr.length()) { convert(arr.optJSONObject(it), java.lang.Object::class.java) }
                }
            }
            else -> throw IllegalArgumentException("未知值类型: $t")
        }
    }

    private fun Int.toPrimitive(type: Class<*>): Any = when (type) {
        Int::class.javaPrimitiveType -> 0
        Long::class.javaPrimitiveType -> 0L
        Short::class.javaPrimitiveType -> 0.toShort()
        Byte::class.javaPrimitiveType -> 0.toByte()
        Char::class.javaPrimitiveType -> '\u0000'
        Float::class.javaPrimitiveType -> 0f
        Double::class.javaPrimitiveType -> 0.0
        Boolean::class.javaPrimitiveType -> false
        else -> 0
    }
}
