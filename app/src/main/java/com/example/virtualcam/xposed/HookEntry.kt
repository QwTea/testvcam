package com.example.virtualcam.xposed

import android.app.Application
import com.example.virtualcam.logVcam
import com.example.virtualcam.prefs.ModulePrefs
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

// GREP: HOOK_ENTRY
class HookEntry : IXposedHookLoadPackage {

    private val initializedPackages = mutableSetOf<String>()

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName == "com.example.virtualcam") return
        if (!initializedPackages.add(lpparam.packageName)) return
        try {
            XposedHelpers.findAndHookMethod(
                "android.app.Application",
                lpparam.classLoader,
                "onCreate",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val app = param.thisObject as Application
                        install(app, lpparam.classLoader)
                    }
                }
            )
        } catch (err: Throwable) {
            logVcam("HookEntry: application hook failed ${err.message}")
        }
    }

    private fun install(app: Application, classLoader: ClassLoader) {
        val context = app.applicationContext
        val prefs = ModulePrefs.getInstance(context)
        val settings = prefs.read()
        if (!settings.enabled) {
            logVcam("VirtualCam disabled for ${context.packageName}")
            return
        }
        FakeFrameInjector.initialize(context)
        FakeFrameInjector.installHooks(classLoader)
        FakeFrameInjector.warmUp()
        CameraXHooks.install(classLoader)
        logVcam("VirtualCam initialized in ${context.packageName}")
    }
}
