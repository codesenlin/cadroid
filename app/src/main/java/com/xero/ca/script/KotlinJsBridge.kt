package com.xero.ca.script

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.lang.reflect.Array as JavaArray

/**
 * 给 Kotlin/JS 侧用的 Java 互操作桥。
 *
 * 普通调用仍可直接走 Rhino 的 java.*；这里集中处理反射、动态类名、
 * 构造器和静态方法，避免 Kotlin/JS 代码到处写 js("new ...")。
 *
 * 参数统一用 JSON 数组传给 Java，避免 Rhino 对 Kotlin vararg / Array 的适配差异。
 */
class KotlinJsBridge(private val context: Context) {

    fun context(): Context = context

    fun classOf(className: String): Class<*> =
        Class.forName(className, false, context.classLoader)

    fun newArray(className: String, length: Int): Any {
        val component = primitiveType(className) ?: Class.forName(className, false, context.classLoader)
        return JavaArray.newInstance(component, length)
    }

    fun newInstance(className: String, argsJson: String?): Any? {
        val args = parseArgs(argsJson)
        val type = Class.forName(className, true, context.classLoader)
        val ctor = type.constructors.firstOrNull {
            it.parameterTypes.size == args.size && fits(it.parameterTypes, args)
        } ?: throw IllegalArgumentException("No constructor on $className for ${args.size} args")
        return ctor.newInstance(*convertArgs(ctor.parameterTypes, args))
    }

    fun callStatic(className: String, methodName: String, argsJson: String?): Any? {
        val args = parseArgs(argsJson)
        val type = Class.forName(className, true, context.classLoader)
        val method = type.methods.firstOrNull {
            it.name == methodName && it.parameterTypes.size == args.size && fits(it.parameterTypes, args)
        } ?: throw IllegalArgumentException("No method $className.$methodName for ${args.size} args")
        return method.invoke(null, *convertArgs(method.parameterTypes, args))
    }

    private fun parseArgs(argsJson: String?): List<Any?> {
        if (argsJson.isNullOrBlank()) return emptyList()
        val array = JSONArray(argsJson)
        return (0 until array.length()).map { i ->
            when (array.get(i)) {
                JSONObject.NULL -> null
                else -> array.get(i)
            }
        }
    }

    private fun fits(types: Array<Class<*>>, args: List<Any?>): Boolean =
        types.size == args.size && types.indices.all { convert(args[it], types[it]) != Unconvertible }

    private fun convertArgs(types: Array<Class<*>>, args: List<Any?>): Array<Any?> =
        Array(types.size) { i -> convert(args[i], types[i]).takeUnless { it === Unconvertible } ?: error("Cannot convert arg $i to ${types[i]}") }

    private fun convert(value: Any?, target: Class<*>): Any? {
        if (value == null) {
            return if (target.isPrimitive) primitiveDefault(target) else null
        }
        if (target.isInstance(value)) return value
        return when (target) {
            java.lang.Integer.TYPE, Integer::class.java -> (value as? Number)?.toInt()
            java.lang.Long.TYPE, java.lang.Long::class.java -> (value as? Number)?.toLong()
            java.lang.Short.TYPE, java.lang.Short::class.java -> (value as? Number)?.toShort()
            java.lang.Byte.TYPE, java.lang.Byte::class.java -> (value as? Number)?.toByte()
            java.lang.Double.TYPE, java.lang.Double::class.java -> (value as? Number)?.toDouble()
            java.lang.Float.TYPE, java.lang.Float::class.java -> (value as? Number)?.toFloat()
            java.lang.Boolean.TYPE, java.lang.Boolean::class.java -> value as? Boolean
            java.lang.Character.TYPE, Character::class.java -> (value as? String)?.firstOrNull()
            String::class.java -> value.toString()
            else -> Unconvertible
        } ?: Unconvertible
    }

    private fun primitiveDefault(type: Class<*>): Any = when (type) {
        java.lang.Boolean.TYPE -> false
        java.lang.Character.TYPE -> '\u0000'
        java.lang.Byte.TYPE -> 0.toByte()
        java.lang.Short.TYPE -> 0.toShort()
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Float.TYPE -> 0f
        java.lang.Double.TYPE -> 0.0
        else -> 0
    }

    private fun primitiveType(name: String): Class<*>? = when (name) {
        "boolean" -> java.lang.Boolean.TYPE
        "byte" -> java.lang.Byte.TYPE
        "char" -> java.lang.Character.TYPE
        "short" -> java.lang.Short.TYPE
        "int" -> java.lang.Integer.TYPE
        "long" -> java.lang.Long.TYPE
        "float" -> java.lang.Float.TYPE
        "double" -> java.lang.Double.TYPE
        else -> null
    }

    private object Unconvertible
}
