package com.mxd.koubo

/** 跟随功能主进程(:speech 消费方)与识别进程(FollowService)之间的广播契约 */
object FollowIpc {
    const val ACTION_PCM = "com.mxd.koubo.FOLLOW_PCM"       // UI → 识别进程：16k 单声道 PCM 帧
    const val ACTION_TEXT = "com.mxd.koubo.FOLLOW_TEXT"     // 识别进程 → UI：累计识别文本
    const val ACTION_READY = "com.mxd.koubo.FOLLOW_READY"   // 识别进程 → UI：模型加载完成
    const val ACTION_ERROR = "com.mxd.koubo.FOLLOW_ERROR"   // 识别进程 → UI：初始化/运行失败
    const val ACTION_ALIVE = "com.mxd.koubo.FOLLOW_ALIVE"   // 识别进程 → UI：进程存活心跳
    const val EXTRA_PCM = "pcm"
    const val EXTRA_TEXT = "text"
    const val EXTRA_MSG = "msg"
}
