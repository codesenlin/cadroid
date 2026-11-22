package com.xero.ca.script

import android.content.res.AssetManager

/** CHelper AAR 未集成时的空实现。 */
object CHelperBridge {
    fun isAvailable(assets: AssetManager): Boolean = false
    fun nativeAvailable(): Boolean = false
    fun query(assets: AssetManager, pack: String, command: String, cursor: Int): String = ""
    fun probe(assets: AssetManager, pack: String, command: String): String = ""
    fun structure(assets: AssetManager, pack: String, command: String): String = ""
    fun applySuggestion(
        assets: AssetManager,
        pack: String,
        command: String,
        cursor: Int,
        which: Int,
    ): Array<String> = arrayOf("", "0", "0")
}
