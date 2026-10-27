package com.doge.ipdf

import android.content.Context
import android.view.KeyEvent
import java.lang.reflect.Proxy

/**
 * Reflection bridge to Xiaomi's official stylus touch-film SDK (PenEngine):
 * com.miui.penengine.touchfilm.MiuiTouchFilmUtils.
 *
 * The SDK is an AAR distributed through the HyperOS developer platform, not Maven, so the app
 * compiles without it. Drop PenEngine-release_*.aar into app/libs and the bridge starts working;
 * when the class is absent every call is a no-op.
 */
object MiuiTouchFilm {
    private const val UTILS_CLASS = "com.miui.penengine.touchfilm.MiuiTouchFilmUtils"
    private const val NESTED_LISTENER = "$UTILS_CLASS\$TouchFilmListener"
    private const val TOP_LEVEL_LISTENER = "com.miui.penengine.touchfilm.TouchFilmListener"

    private var utilsClass: Class<*>? = null
    private var listener: Any? = null

    var switchBrushAndEraser = -1
        private set
    var switchToPreviousBrush = -1
        private set
    var showColorWheel = -1
        private set
    var showBrushSettings = -1
        private set
    var slideUp = -1
        private set
    var slideDown = -1
        private set

    val available: Boolean get() = utilsClass != null

    /** Must be called from Activity.onResume so the SDK re-reads the current pen settings. */
    fun init(context: Context, onFunction: (Int) -> Unit): Boolean {
        if (utilsClass != null) return true
        val clazz = runCatching { Class.forName(UTILS_CLASS) }.getOrNull() ?: return false
        val listenerType = runCatching { Class.forName(NESTED_LISTENER) }.getOrNull()
            ?: runCatching { Class.forName(TOP_LEVEL_LISTENER) }.getOrNull()
            ?: return false
        readConstants(clazz)
        val proxy = Proxy.newProxyInstance(listenerType.classLoader, arrayOf(listenerType)) { _, method, args ->
            if (method.name == "onTouchFilmTriggered") {
                val function = args?.firstOrNull() as? Int ?: -1
                if (function >= 0) onFunction(function)
            }
            null
        }
        val initMethod = runCatching {
            clazz.getMethod("init", Context::class.java, listenerType)
        }.getOrNull() ?: return false
        return runCatching {
            initMethod.invoke(null, context.applicationContext, proxy)
            utilsClass = clazz
            listener = proxy
            true
        }.getOrDefault(false)
    }

    /** Forward every key event; true means the SDK consumed it as a pen touch-film gesture. */
    fun onDispatchKeyEvent(event: KeyEvent): Boolean {
        val clazz = utilsClass ?: return false
        return runCatching {
            val method = clazz.getMethod("onDispatchKeyEvent", KeyEvent::class.java)
            method.invoke(null, event) as? Boolean ?: false
        }.getOrDefault(false)
    }

    fun destroy() {
        val clazz = utilsClass ?: return
        runCatching { clazz.getMethod("onDestroy").invoke(null) }
        utilsClass = null
        listener = null
    }

    private fun readConstants(clazz: Class<*>) {
        switchBrushAndEraser = intField(clazz, "SWITCH_BETWEEN_BRUSH_AND_ERASER")
        switchToPreviousBrush = intField(clazz, "SWITCH_TO_PREVIOUS_BRUSH")
        showColorWheel = intField(clazz, "SHOW_COLOR_WHEEL")
        showBrushSettings = intField(clazz, "SHOW_BRUSH_SETTINGS")
        slideUp = intField(clazz, "STYLUS_SLIDE_UP")
        slideDown = intField(clazz, "STYLUS_SLIDE_DOWN")
    }

    private fun intField(clazz: Class<*>, name: String): Int =
        runCatching { clazz.getField(name).getInt(null) }.getOrDefault(-1)
}
