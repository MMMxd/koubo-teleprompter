package com.mxd.koubo

import android.app.Application
import android.util.Log
import java.io.File

/** 全局崩溃捕获：堆栈写入 filesDir/crash.txt，下次启动由首页弹窗展示 */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val ver = try {
                    val pi = packageManager.getPackageInfo(packageName, 0)
                    "${pi.versionName} (${pi.longVersionCode})"
                } catch (_: Exception) { "?" }
                File(filesDir, "crash.txt").writeText(
                    "线程: ${t.name}\n" +
                        "版本: $ver\n" +
                        "机型: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} / Android ${android.os.Build.VERSION.RELEASE}\n\n" +
                        Log.getStackTraceString(e),
                    Charsets.UTF_8
                )
            } catch (_: Exception) {
            }
            prev?.uncaughtException(t, e) // 交回系统正常处理
        }
    }
}
