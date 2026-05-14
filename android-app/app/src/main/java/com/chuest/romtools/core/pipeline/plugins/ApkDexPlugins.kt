package com.chuest.romtools.core.pipeline.plugins

import com.chuest.romtools.core.DexPatcher
import com.chuest.romtools.core.Plugins
import com.chuest.romtools.core.pipeline.PipelineContext
import com.chuest.romtools.core.pipeline.PipelinePlugin

/** MiuiMms.apk — copy SMS verification codes to the clipboard automatically. */
object MmsVerificationCodeAutoCopyPlugin : PipelinePlugin {
    override val id = Plugins.MMS_VCODE_AUTOCOPY
    override val label = "mmsVerificationCodeAutoCopy"
    override val title = "短信验证码自动复制"
    override val description = "MiuiMms.apk：识别到 is_verification_code 时调 ClipboardManager"

    override fun run(ctx: PipelineContext) {
        val pkg = "Lcom/android/mms/transaction/"
        ctx.patchApkDex(
            "product/product/priv-app/MiuiMms/MiuiMms.apk",
            "MiuiMms",
            compilerFilter = "speed",
        ) { cls ->
            if (!cls.type.startsWith(pkg)) cls
            else DexPatcher.mapMethods(cls) { m ->
                DexPatcher.insertInvokeStaticBeforeStringConst(
                    m,
                    stringValue = "is_verification_code",
                    calleeOwner = "Lh7/e;",
                    calleeName = "a",
                    calleeParam = "Ljava/lang/CharSequence;",
                    argReg = 2,
                )
            }
        }
    }
}

/** PowerKeeper.apk — disable cloud sync of throttling/performance profiles and DisplayFrameSetting. */
object PowerKeeperPlugin : PipelinePlugin {
    override val id = Plugins.POWER_KEEPER
    override val label = "powerKeeperPatch"
    override val title = "去除电量与性能云控"
    override val description = "PowerKeeper.apk：禁用 startCloudSyncData 与 DisplayFrameSetting"

    override fun run(ctx: PipelineContext) {
        val cloudCls = "Lcom/miui/powerkeeper/cloudcontrol/LocalUpdateUtils;"
        val displayCls = "Lcom/miui/powerkeeper/statemachine/DisplayFrameSetting;"
        ctx.patchApkDex(
            "system/system/system/app/PowerKeeper/PowerKeeper.apk",
            "PowerKeeper",
            compilerFilter = "speed",
        ) { cls ->
            when (cls.type) {
                cloudCls -> DexPatcher.replaceMethodBody(
                    cls,
                    match = { it.name == "startCloudSyncData" },
                    build = { DexPatcher.returnVoid(it) },
                )
                displayCls -> DexPatcher.replaceMethodBodies(
                    cls,
                    listOf<Pair<(com.android.tools.smali.dexlib2.iface.Method) -> Boolean,
                            (com.android.tools.smali.dexlib2.iface.Method) -> com.android.tools.smali.dexlib2.iface.MethodImplementation>>(
                        { it.name == "isFeatureOn" && it.returnType == "Z" } to { DexPatcher.returnIntConst(it, 0) },
                        { it.name == "setScreenEffect" && it.returnType == "V" } to { DexPatcher.returnVoid(it) },
                    ),
                )
                else -> cls
            }
        }
    }
}

/** MiuiSystemUI.apk — speed the status-bar network speed refresh from 4 s → 1 s. */
object MiuiSystemUIPlugin : PipelinePlugin {
    override val id = Plugins.MIUI_SYSTEMUI
    override val label = "miuiSystemUIPatch"
    override val title = "状态栏网速刷新 1s"
    override val description = "MiuiSystemUI.apk：NetworkSpeedController 0xfa0 → 0x3e8"

    override fun run(ctx: PipelineContext) {
        val pkg = "Lcom/android/systemui/statusbar/policy/"
        ctx.patchApkDex(
            "system_ext/system_ext/priv-app/MiuiSystemUI/MiuiSystemUI.apk",
            "MiuiSystemUI",
            compilerFilter = "speed",
        ) { cls ->
            if (!cls.type.startsWith(pkg)) return@patchApkDex cls
            val tail = cls.type.removePrefix(pkg).removeSuffix(";")
            if ('/' in tail || !tail.startsWith("NetworkSpeedController")) return@patchApkDex cls
            DexPatcher.replaceConstWide16(cls, registerA = 0, oldValue = 0xfa0, newValue = 0x3e8)
        }
    }
}
