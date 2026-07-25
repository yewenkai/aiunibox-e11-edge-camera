package org.e11camera.edge

import android.util.Log
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicInteger

/**
 * 云台电机控制器。
 * 硬件：TMI8150B 步进驱动芯片（SPI），通过 vendor 自带的 ipcamera_test 控制。
 * 参数：每圈 2986 步，速度 30~854 步/秒。仅水平 pan（无俯仰）。
 *
 * 所有命令在单线程队列上串行执行，避免并发转动冲突。
 */
class MotorController {
    companion object {
        private const val TAG = "MotorController"
        private const val TEST_BIN = "/vendor/bin/hw/ipcamera_test"
        private const val MAX_PENDING_ACTIONS = 4
        private const val SCREEN_LEFT_HARDWARE_DIR = 1
        private const val SCREEN_RIGHT_HARDWARE_DIR = 0
        /** 每圈步数 */
        const val STEPS_PER_CIRCLE = 2986
    }

    private val exec: ExecutorService = Executors.newSingleThreadExecutor()

    private val pendingActions = AtomicInteger(0)

    /**
     * 让观看画面向左移动。
     * 用户确认的最终映射：对应硬件方向 1。
     */
    fun turnLeft(steps: Int, speed: Int): Boolean =
        rotate(SCREEN_LEFT_HARDWARE_DIR, steps, speed)

    /** 让观看画面向右移动；用户确认的最终映射为硬件方向 0。 */
    fun turnRight(steps: Int, speed: Int): Boolean =
        rotate(SCREEN_RIGHT_HARDWARE_DIR, steps, speed)

    /** hardwareDir 只作为厂商底层方向值；用户侧语义以画面移动方向为准。 */
    @Synchronized
    private fun rotate(hardwareDir: Int, steps: Int, speedIn: Int): Boolean {
        if (exec.isShutdown || pendingActions.get() >= MAX_PENDING_ACTIONS) {
            Log.w(TAG, "电机队列已满或控制器已关闭，忽略请求")
            return false
        }
        val speed = speedIn.coerceIn(30, 854)
        // 日常点动最多半圈，避免异常参数造成绕线或撞限位。
        val s = steps.coerceIn(1, STEPS_PER_CIRCLE / 2)
        // -n 禁用厂商工具的异步回调等待。未加 -n 时，短点动完成后进程仍会
        // 停留约 5 秒，busy 无法释放，表现为只能转动一次。
        val cmd = "$TEST_BIN -S $speed -d $hardwareDir -s $s -r -n"
        pendingActions.incrementAndGet()
        try {
            exec.submit {
                try {
                    ShellUtil.execSu(cmd)
                } finally {
                    pendingActions.decrementAndGet()
                }
            }
        } catch (_: RejectedExecutionException) {
            pendingActions.decrementAndGet()
            return false
        }
        return true
    }

    /** 读取传感器位置数据 */
    fun getPosition(): String = ShellUtil.execSu("$TEST_BIN -g")

    fun isBusy(): Boolean = pendingActions.get() > 0

    fun shutdown() {
        exec.shutdownNow()
        pendingActions.set(0)
    }

    /** 步数 → 角度字符串 */
    fun stepsToAngle(steps: Int): String {
        val deg = steps * 360.0 / STEPS_PER_CIRCLE
        return String.format("%.1f°", deg)
    }

    /** 角度 → 步数 */
    fun angleToSteps(angle: Double): Int = Math.round(angle * STEPS_PER_CIRCLE / 360.0).toInt()
}
