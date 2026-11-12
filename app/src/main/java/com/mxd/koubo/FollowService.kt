package com.mxd.koubo

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/**
 * 识别引擎隔离进程（:speech）：sherpa native 失败时 exit 只死这个进程，
 * 主进程的悬浮窗/提词界面安然无恙，用户重新点一次跟随即可重试。
 */
class FollowService : Service() {

    private var asr: FollowAsr? = null
    private var receiver: BroadcastReceiver? = null
    private val main = Handler(Looper.getMainLooper())
    private var hb: Runnable? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        asr = FollowAsr(this) { t ->
            sendBroadcast(
                Intent(FollowIpc.ACTION_TEXT).setPackage(packageName)
                    .putExtra(FollowIpc.EXTRA_TEXT, t)
            )
        }
        val br = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                if (i?.action != FollowIpc.ACTION_PCM) return
                val b = i.getByteArrayExtra(FollowIpc.EXTRA_PCM) ?: return
                val sb = ShortArray(b.size / 2)
                java.nio.ByteBuffer.wrap(b).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(sb)
                Thread {
                    try { asr?.feed(sb) } catch (_: Exception) {}
                }.start()
            }
        }
        receiver = br
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(br, IntentFilter(FollowIpc.ACTION_PCM), Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(br, IntentFilter(FollowIpc.ACTION_PCM))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val a = asr ?: return START_NOT_STICKY
        Thread {
            a.init(
                onReady = {
                    // 就绪后每 2 秒发一次存活心跳，主进程以此判断引擎是否被 exit 杀死
                    val h = object : Runnable {
                        override fun run() {
                            if (asr == null) return
                            sendBroadcast(Intent(FollowIpc.ACTION_ALIVE).setPackage(packageName))
                            main.postDelayed(this, 2000)
                        }
                    }
                    main.post(h)
                    main.post {
                        sendBroadcast(Intent(FollowIpc.ACTION_READY).setPackage(packageName))
                    }
                },
                onError = { m ->
                    main.post {
                        sendBroadcast(
                            Intent(FollowIpc.ACTION_ERROR).setPackage(packageName)
                                .putExtra(FollowIpc.EXTRA_MSG, m)
                        )
                    }
                }
            )
        }.start()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        receiver?.let { try { unregisterReceiver(it) } catch (_: Exception) {} }
        receiver = null
        hb?.let { main.removeCallbacks(it) }
        hb = null
        asr?.close()
        asr = null
        super.onDestroy()
    }
}
