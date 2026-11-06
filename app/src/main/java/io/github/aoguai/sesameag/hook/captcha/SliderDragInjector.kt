package io.github.aoguai.sesameag.hook.captcha

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import io.github.aoguai.sesameag.util.Log

/**
 * 把一条轨迹注入成真实触摸事件。
 *
 * 关键教训（2026-11-06 实测）：
 * 早先在主线程用 `for` 循环连续 `dispatchTouchEvent`，虽然 MOVE 的 `eventTime` 递增到 3 秒，
 * 但**真实耗时只有 267ms**（633px 行程 ≈ 2370px/s）。JS 侧和风控侧都能感知真实到达时间，
 * 于是被判定为机器轨迹、页面直接刷新验证码。
 *
 * 现在改为**逐帧 postDelayed 派发**：既让真实时间真的走完，又不阻塞主线程（不冻结渲染）。
 */
object SliderDragInjector {
    private const val TAG = "CaptchaAutoSolver"
    private const val FRAME_MS = 14L

    /**
     * 派发一次完整拖动。
     *
     * @param track 相对起点的轨迹点 `[dx, dy]`，最后一个点应为终点。
     * @param onFinished 在主线程回调（true 表示事件链路全部被消费）。
     */
    fun drag(
        target: View,
        startX: Float,
        startY: Float,
        plan: SliderCaptchaSolver.DragPlan,
        onFinished: ((Boolean) -> Unit)? = null,
    ) {
        val track = plan.points
        if (track.isEmpty()) {
            onFinished?.invoke(false)
            return
        }
        val handler = Handler(Looper.getMainLooper())
        val random = kotlin.random.Random(System.nanoTime())
        fun frameDelay(): Long =
            plan.frameMsMin + random.nextLong(0L, (plan.frameMsMax - plan.frameMsMin).coerceAtLeast(1L))
        fun pauseDelay(): Long =
            plan.pauseMsMin + random.nextLong(0L, (plan.pauseMsMax - plan.pauseMsMin).coerceAtLeast(1L))
        handler.post {
            val downTime = SystemClock.uptimeMillis()
            var consumed = target.dispatchTouchEvent(
                MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, startX, startY, 0),
            )
            var index = 0
            var lastX = 0f

            val step = object : Runnable {
                override fun run() {
                    if (index >= track.size) {
                        // 松手前在终点停留，模拟"对齐确认"
                        val last = track.last()
                        handler.postDelayed({
                            consumed = target.dispatchTouchEvent(
                                MotionEvent.obtain(
                                    downTime,
                                    SystemClock.uptimeMillis(),
                                    MotionEvent.ACTION_UP,
                                    startX + last[0],
                                    startY + last[1],
                                    0,
                                ),
                            ) && consumed
                            onFinished?.invoke(consumed)
                        }, plan.endHoldMs)
                        return
                    }
                    val point = track[index]
                    consumed = target.dispatchTouchEvent(
                        MotionEvent.obtain(
                            downTime,
                            SystemClock.uptimeMillis(),
                            MotionEvent.ACTION_MOVE,
                            startX + point[0],
                            startY + point[1],
                            0,
                        ),
                    ) && consumed
                    // 同一坐标重复 = 手指短暂停顿，给更长的真实间隔
                    val isPause = kotlin.math.abs(point[0] - lastX) < 0.01f
                    lastX = point[0]
                    index++
                    handler.postDelayed(this, if (isPause) pauseDelay() else frameDelay())
                }
            }
            // 起手按住 110~170ms 再移动
            handler.postDelayed(step, 110L + (track.size % 4) * 15L)
        }
    }

    /** 同步版本，仅用于自检/调试：会阻塞调用线程。 */
    fun dragBlocking(
        target: View,
        startX: Float,
        startY: Float,
        plan: SliderCaptchaSolver.DragPlan,
    ): Boolean {
        if (plan.points.isEmpty()) return false
        val latch = java.util.concurrent.CountDownLatch(1)
        var ok = false
        drag(target, startX, startY, plan) {
            ok = it
            latch.countDown()
        }
        latch.await(15, java.util.concurrent.TimeUnit.SECONDS)
        return ok
    }

    internal fun log(message: String) {
        Log.record(TAG, message)
    }
}
