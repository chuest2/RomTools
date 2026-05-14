package com.chuest.romtools.core.pipeline.plugins

import com.chuest.romtools.core.DexPatcher
import com.chuest.romtools.core.Logger
import com.chuest.romtools.core.Plugins
import com.chuest.romtools.core.pipeline.PipelineContext
import com.chuest.romtools.core.pipeline.PipelinePlugin
import java.io.File

/**
 * miui-services.jar — strip DrmBroadcast call sites (theme recovery) and force
 * isNavigationStatus() → true everywhere under com/android/server/.
 */
object PreventThemeRecoveryPlugin : PipelinePlugin {
    override val id = Plugins.PREVENT_THEME_RECOVERY
    override val label = "preventThemeRecovery"
    override val title = "防主题被还原"
    override val description = "miui-services.jar：屏蔽 DrmBroadcast，固定 navigation status"

    override fun run(ctx: PipelineContext) {
        Logger.n("Patching miui-services.jar via dexlib2")
        val work = ctx.work
        val tmp = File(work, "tmp"); tmp.deleteRecursively(); tmp.mkdirs()
        val src = File(work, "system_ext/system_ext/framework/miui-services.jar")
        val patched = File(tmp, "miui-services.jar")

        val amsType = "Lcom/android/server/am/ActivityManagerServiceImpl;"
        val drmClass = "Lmiui/drm/DrmBroadcast;"

        DexPatcher.patchJar(src, patched) { cls ->
            when {
                cls.type == amsType -> DexPatcher.mapMethods(cls) { m ->
                    DexPatcher.removeCallSitesToClass(m, drmClass)
                }
                cls.type.startsWith("Lcom/android/server/") -> DexPatcher.replaceMethodBody(
                    cls,
                    match = { it.name == "isNavigationStatus" && it.returnType == "Z" },
                    build = { DexPatcher.returnIntConst(it, 1) },
                )
                else -> cls
            }
        }

        src.delete()
        File(work, "system_ext/system_ext/framework/oat/arm64")
            .listFiles { f -> f.name.startsWith("miui-services") }
            ?.forEach { it.delete() }
        ctx.zipalignAndInstall(patched, File(work, "system_ext/system_ext/framework/miui-services.jar"))
        ctx.runDex2Oat(
            dexFile = "system_ext/system_ext/framework/miui-services.jar",
            oatRel  = "system_ext/system_ext/framework/oat/arm64/miui-services.odex",
            compilerFilter = "everything",
        )
        tmp.deleteRecursively()
    }
}
