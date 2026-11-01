package io.github.aoguai.sesameag.hook

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import io.github.aoguai.sesameag.util.Log
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object PageStructureSnapshotter {
    private const val TAG = "PageStructureSnapshotter"

    private val installed = AtomicBoolean(false)

    @Volatile
    private var resumedActivityRef: WeakReference<Activity>? = null

    fun install(application: Application) {
        if (!installed.compareAndSet(false, true)) {
            return
        }
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

            override fun onActivityStarted(activity: Activity) = Unit

            override fun onActivityResumed(activity: Activity) {
                resumedActivityRef = WeakReference(activity)
            }

            override fun onActivityPaused(activity: Activity) = Unit

            override fun onActivityStopped(activity: Activity) = Unit

            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

            override fun onActivityDestroyed(activity: Activity) {
                if (resumedActivityRef?.get() === activity) {
                    resumedActivityRef = null
                }
            }
        })
        Log.runtime(TAG, "已安装页面结构快照 Activity 监听")
    }

    fun captureOnce(reason: String): String {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return captureOnMainThread(reason)
        }

        val latch = CountDownLatch(1)
        var result = "页面结构抓取失败：主线程不可用"
        val handler = ApplicationHook.mainHandler
        if (handler == null) {
            Log.capture(TAG, result)
            return result
        }
        handler.post {
            result = captureOnMainThread(reason)
            latch.countDown()
        }
        // 页面结构必须在主线程读取；后台最多等待两秒获得快照，避免主线程不可用时无限阻塞。
        if (!latch.await(2, TimeUnit.SECONDS)) {
            result = "页面结构抓取失败：主线程执行超时"
            Log.capture(TAG, result)
        }
        return result
    }

    private fun captureOnMainThread(reason: String): String {
        val activity = resumedActivityRef?.get()
        if (activity == null) {
            val message = "页面结构抓取失败：无前台 Activity"
            Log.capture(TAG, message)
            return message
        }

        val snapshot = buildString {
            append("Page Snapshot")
            append(" reason=")
            append(reason)
            append('\n')
            append("Activity: ")
            append(activity.javaClass.name)
            append('\n')
            append("TaskId: ")
            append(activity.taskId)
            append('\n')
            append("Fragments:\n")
            if (!appendFragments(activity, this)) {
                append("  (none)\n")
            }
            append("ViewTree:\n")
            val decorView = activity.window?.decorView
            if (decorView == null) {
                append("  decorView=null\n")
            } else {
                appendViewTree(decorView, this, depth = 0)
            }
        }

        Log.capture(TAG, snapshot)
        return "页面结构快照已写入 capture 日志"
    }

    private fun appendFragments(activity: Activity, builder: StringBuilder): Boolean {
        var appended = false
        val nativeManager = runCatching {
            findMethod(activity.javaClass, "getFragmentManager", 0)?.invoke(activity)
        }.getOrNull()
        appended = appendFragmentManager(nativeManager, builder, "  ", 0) || appended

        val supportManager = runCatching {
            findMethod(activity.javaClass, "getSupportFragmentManager", 0)?.invoke(activity)
        }.getOrNull()
        appended = appendFragmentManager(supportManager, builder, "  ", 0) || appended
        return appended
    }

    private fun appendFragmentManager(
        fragmentManager: Any?,
        builder: StringBuilder,
        indent: String,
        depth: Int
    ): Boolean {
        if (fragmentManager == null) {
            return false
        }
        val fragments = runCatching {
            @Suppress("UNCHECKED_CAST")
            findMethod(fragmentManager.javaClass, "getFragments", 0)?.invoke(fragmentManager) as? List<Any?>
        }.getOrNull()?.filterNotNull().orEmpty()
        if (fragments.isEmpty()) {
            return false
        }
        for (fragment in fragments) {
            appendFragment(fragment, builder, indent, depth)
        }
        return true
    }

    private fun appendFragment(fragment: Any, builder: StringBuilder, indent: String, depth: Int) {
        builder.append(indent)
        builder.append(fragment.javaClass.name)
        builder.append(" added=")
        builder.append(invokeBoolean(fragment, "isAdded"))
        builder.append(" resumed=")
        builder.append(invokeBoolean(fragment, "isResumed"))
        builder.append(" hidden=")
        builder.append(invokeBoolean(fragment, "isHidden"))
        builder.append('\n')

        val childManager = runCatching {
            findMethod(fragment.javaClass, "getChildFragmentManager", 0)?.invoke(fragment)
        }.getOrNull()
        appendFragmentManager(childManager, builder, "$indent  ", depth + 1)
    }

    private fun appendViewTree(view: View, builder: StringBuilder, depth: Int) {
        val indent = "  ".repeat(depth + 1)
        builder.append(indent)
        builder.append(view.javaClass.name)
        builder.append(" id=")
        builder.append(resolveViewId(view))
        builder.append(" visibility=")
        builder.append(resolveVisibility(view.visibility))
        builder.append(" bounds=")
        builder.append(view.left)
        builder.append(',')
        builder.append(view.top)
        builder.append(',')
        builder.append(view.right)
        builder.append(',')
        builder.append(view.bottom)
        builder.append(" size=")
        builder.append(view.width)
        builder.append('x')
        builder.append(view.height)
        builder.append('\n')

        if (view !is ViewGroup) {
            return
        }
        for (index in 0 until view.childCount) {
            appendViewTree(view.getChildAt(index), builder, depth + 1)
        }
    }

    private fun resolveViewId(view: View): String {
        if (view.id == View.NO_ID) {
            return "NO_ID"
        }
        return runCatching {
            view.resources.getResourceName(view.id)
        }.getOrElse {
            "0x${Integer.toHexString(view.id)}"
        }
    }

    private fun resolveVisibility(visibility: Int): String {
        return when (visibility) {
            View.VISIBLE -> "VISIBLE"
            View.INVISIBLE -> "INVISIBLE"
            View.GONE -> "GONE"
            else -> visibility.toString()
        }
    }

    private fun invokeBoolean(target: Any, methodName: String): Boolean {
        return runCatching {
            findMethod(target.javaClass, methodName, 0)?.invoke(target) as? Boolean
        }.getOrNull() ?: false
    }

    private fun findMethod(targetClass: Class<*>, name: String, parameterCount: Int): Method? {
        val methods = linkedSetOf<Method>()
        var current: Class<*>? = targetClass
        while (current != null) {
            methods.addAll(current.declaredMethods)
            current = current.superclass
        }
        methods.addAll(targetClass.methods)
        return methods.firstOrNull { it.name == name && it.parameterCount == parameterCount }?.apply {
            isAccessible = true
        }
    }
}
