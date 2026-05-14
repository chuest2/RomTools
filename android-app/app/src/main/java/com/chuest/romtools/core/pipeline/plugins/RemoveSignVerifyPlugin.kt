package com.chuest.romtools.core.pipeline.plugins

import com.chuest.romtools.core.DexPatcher
import com.chuest.romtools.core.Logger
import com.chuest.romtools.core.Plugins
import com.chuest.romtools.core.pipeline.PipelineContext
import com.chuest.romtools.core.pipeline.PipelinePlugin
import java.io.File

/**
 * services.jar — remove Android 14 boot signature check, screenshot delay, and the logcat
 * access permission dialog. Pure dexlib2 (no smali round-trip).
 */
object RemoveSignVerifyPlugin : PipelinePlugin {
    override val id = Plugins.REMOVE_SIGN_VERIFY
    override val label = "removeSignVerify"
    override val title = "去除安卓 14 开机签名校验"
    override val description =
        "改 services.jar：getMinimumSignatureSchemeVersionForTargetSdk + 截屏延迟 + 日志访问弹窗"

    override fun run(ctx: PipelineContext) {
        Logger.n("Patching services.jar via dexlib2")
        val work = ctx.work
        val tmp = File(work, "tmp"); tmp.deleteRecursively(); tmp.mkdirs()
        val src = File(work, "system/system/system/framework/services.jar")
        val patched = File(tmp, "services.jar")

        val pwmType = "Lcom/android/server/policy/PhoneWindowManager;"
        val lamType = "Lcom/android/server/logcat/LogcatManagerService;"
        val pmPkg = "Lcom/android/server/pm/"
        val pmParsingPkg = "Lcom/android/server/pm/pkg/parsing/"
        val calleeName = "getMinimumSignatureSchemeVersionForTargetSdk"

        DexPatcher.patchJar(src, patched) { cls ->
            when {
                cls.type == pwmType -> DexPatcher.replaceMethodBody(
                    cls,
                    match = { it.name == "getScreenshotChordLongPressDelay" && it.parameters.isEmpty() && it.returnType == "J" },
                    build = { DexPatcher.returnWideZero(it) },
                )
                cls.type == lamType -> DexPatcher.replaceMethodBody(
                    cls,
                    match = { it.name == "onLogAccessRequested" },
                    build = { DexPatcher.returnVoid(it) },
                )
                DexPatcher.isImmediateChildOf(cls.type, pmPkg) ||
                    DexPatcher.isImmediateChildOf(cls.type, pmParsingPkg) ->
                    DexPatcher.mapMethods(cls) { m -> DexPatcher.neutralizeCallSites(m, calleeName) }
                else -> cls
            }
        }

        src.delete()
        File(work, "system/system/system/framework/oat/arm64")
            .listFiles { f -> f.name.startsWith("services") }
            ?.forEach { it.delete() }

        ctx.zipalignAndInstall(
            built = patched,
            dst = File(work, "system/system/system/framework/services.jar"),
        )
        ctx.runDex2Oat(
            dexFile = "system/system/system/framework/services.jar",
            oatRel  = "system/system/system/framework/oat/arm64/services.odex",
            artRel  = "system/system/system/framework/oat/arm64/services.art",
            profile = "system/system/system/framework/services.jar.prof",
            compilerFilter = "everything",
        )
        tmp.deleteRecursively()
    }
}
