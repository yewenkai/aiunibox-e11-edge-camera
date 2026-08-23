package org.e11camera.edge

/**
 * RK3566 只有一个 NPU 核心。人体、姿态和面部模型共用这一把进程内锁，
 * 避免多个调度线程同时调用 rknn_run，减少运行时抖动和偶发失败。
 */
internal object NpuExecutionGate {
    val lock = Any()
}
