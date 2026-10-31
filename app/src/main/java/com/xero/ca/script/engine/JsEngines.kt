package com.xero.ca.script.engine

import android.content.Context
import android.util.Log

/**
 * 引擎选择。
 *
 * 生产只跑 Rhino：反射/UI 密集的脚本逻辑用 Rhino 最快（JS 直接 JNI 绑定 Java）。
 * 需要纯 JS 计算加速时，走 [QuickJsWorker] 侧载到 QuickJS（JSON 进 / JSON 出，
 * 不接 Java 反射桥），见 modules/utils/QuickAccel.js。
 */
object JsEngines {

    private const val TAG = "Script"

    fun select(context: Context): JsEngine {
        Log.i(TAG, "脚本引擎: ${RhinoEngine().name}")
        return RhinoEngine()
    }
}
