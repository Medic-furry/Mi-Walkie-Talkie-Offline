package com.medicfurry.walkieoffline.debug

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全局调试日志总线：BleConnector/UI 的关键事件同时写 logcat 和这里，
 * 主页面底部的「调试终端」实时显示，替代弹窗报错，方便真机调试。
 */
object DebugLog {

    private const val MAX_LINES = 300
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines

    @Synchronized
    fun log(msg: String) {
        val line = "${timeFmt.format(Date())}  $msg"
        val cur = _lines.value
        _lines.value = if (cur.size >= MAX_LINES) cur.drop(cur.size - MAX_LINES + 1) + line
        else cur + line
    }

    fun d(tag: String, msg: String) {
        Log.d(tag, msg)
        log(msg)
    }

    fun i(tag: String, msg: String) {
        Log.i(tag, msg)
        log(msg)
    }

    fun w(tag: String, msg: String) {
        Log.w(tag, msg)
        log("⚠ $msg")
    }

    fun clear() {
        _lines.value = emptyList()
    }
}
