package com.maciasdxv.igglass

import android.app.Activity
import android.os.Bundle
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Punto de entrada del módulo (declarado en assets/xposed_init).
 *
 * Fase 2: al reanudarse cada Activity, coloca la píldora de vidrio sobre la
 * pestaña activa de la barra inferior. El volcado de vistas de la Fase 1
 * queda disponible con `setprop debug.igglass.dump 1`.
 *
 * Los hooks se anclan en android.app.Activity (clase del sistema), nunca en
 * clases ofuscadas de Instagram.
 */
class HookEntry : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != IG_PACKAGE) return
        // Instagram tiene procesos auxiliares (:mqtt, etc.); solo nos interesa la UI.
        if (lpparam.processName != IG_PACKAGE) return

        GlassLog.i("módulo cargado en ${lpparam.processName}")
        safe("hook Activity.onCreate") { hookOnCreate() }
        safe("hook Activity.onResume") { hookOnResume() }
        safe("hook View.setLayoutParams") { hookSetLayoutParams() }
        safe("hook ViewGroup.measureChildWithMargins") { hookMeasureChildWithMargins() }
        safe("hook View.onScrollChanged") { hookOnScrollChanged() }
        safe("hook SurfaceView") { hookSurfaceViews() }
    }

    /** Lleva la cuenta de las SurfaceView (videos) para [VideoBlur]. */
    private fun hookSurfaceViews() {
        XposedHelpers.findAndHookMethod(
            SurfaceView::class.java, "onAttachedToWindow",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    safe("SurfaceView adjunta") { VideoBlur.Registry.attached(param.thisObject as SurfaceView) }
                }
            },
        )
        XposedHelpers.findAndHookMethod(
            SurfaceView::class.java, "onDetachedFromWindow",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    safe("SurfaceView separada") { VideoBlur.Registry.detached(param.thisObject as SurfaceView) }
                }
            },
        )
    }

    /** Ver [ScrollWatcher]. */
    private fun hookOnScrollChanged() {
        val int = Int::class.javaPrimitiveType
        XposedHelpers.findAndHookMethod(
            View::class.java, "onScrollChanged", int, int, int, int,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    safe("onScrollChanged") {
                        ScrollWatcher.onScrollChanged(
                            param.thisObject as View,
                            param.args[1] as Int,
                            param.args[3] as Int,
                        )
                    }
                }
            },
        )
    }


    /** Ver [LayoutGuard.beforeMeasureChild]. */
    private fun hookMeasureChildWithMargins() {
        XposedHelpers.findAndHookMethod(
            ViewGroup::class.java, "measureChildWithMargins",
            View::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    safe("measureChildWithMargins") {
                        (param.args[0] as? View)?.let(LayoutGuard::beforeMeasureChild)
                    }
                }
            },
        )
    }

    /** Ver [LayoutGuard]: reescribe márgenes de Instagram antes de que se apliquen. */
    private fun hookSetLayoutParams() {
        XposedHelpers.findAndHookMethod(
            View::class.java, "setLayoutParams", ViewGroup.LayoutParams::class.java,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    safe("setLayoutParams") {
                        LayoutGuard.beforeSetLayoutParams(
                            param.thisObject as View,
                            param.args[0] as? ViewGroup.LayoutParams,
                        )
                    }
                }
            },
        )
    }

    private fun hookOnCreate() {
        XposedHelpers.findAndHookMethod(
            Activity::class.java, "onCreate", Bundle::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    safe("onCreate") {
                        GlassLog.i("onCreate: ${param.thisObject.javaClass.name}")
                    }
                }
            },
        )
    }

    private fun hookOnResume() {
        XposedHelpers.findAndHookMethod(
            Activity::class.java, "onResume",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    safe("onResume") {
                        val activity = param.thisObject as Activity
                        if (Config.isOff) {
                            GlassLog.i("desactivado por debug.igglass.off")
                            return@safe
                        }
                        GlassLog.i("onResume: ${activity.javaClass.name}")
                        val decor = activity.window?.decorView ?: return@safe
                        decor.post { safe("píldora") { TabBarGlass.attach(activity) } }
                        if (Config.dumpEnabled) {
                            // Esperar a que Instagram infle y coloque sus vistas.
                            decor.postDelayed({
                                safe("volcado") { ViewDumper.dump(activity) }
                            }, DUMP_DELAY_MS)
                        }
                    }
                }
            },
        )
    }

    private companion object {
        const val IG_PACKAGE = "com.instagram.android"
        const val DUMP_DELAY_MS = 2000L
    }
}
