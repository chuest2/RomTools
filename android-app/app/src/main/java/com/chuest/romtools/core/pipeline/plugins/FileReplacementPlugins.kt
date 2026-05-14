package com.chuest.romtools.core.pipeline.plugins

import com.chuest.romtools.core.Logger
import com.chuest.romtools.core.Plugins
import com.chuest.romtools.core.pipeline.PipelineContext
import com.chuest.romtools.core.pipeline.PipelinePlugin
import java.io.File

/** Replace 5 prebuilt MIUI APKs with cleaned-up copies from assets/files/app/. */
object ReplaceApksPlugin : PipelinePlugin {
    override val id = Plugins.REPLACE_APKS
    override val label = "replaceApks"
    override val title = "替换内置 APK"
    override val description =
        "AnalyticsCore / MIUISystemUIPlugin / MiuiHome / MIUIPackageInstaller / MIUISecurityCenter"

    override fun run(ctx: PipelineContext) {
        Logger.n("Replacing APKs")
        val mapping = listOf(
            "AnalyticsCore"        to "product/product/app/AnalyticsCore",
            "MIUISystemUIPlugin"   to "product/product/app/MIUISystemUIPlugin",
            "MiuiHome"             to "product/product/priv-app/MiuiHome",
            "MIUIPackageInstaller" to "product/product/priv-app/MIUIPackageInstaller",
            "MIUISecurityCenter"   to "product/product/priv-app/MIUISecurityCenter",
        )
        for ((srcName, dstRel) in mapping) {
            val src = File(ctx.filesRoot, "app/$srcName")
            val dst = File(ctx.work, dstRel)
            if (!src.isDirectory) { Logger.w("missing replacement: $src"); continue }
            dst.deleteRecursively()
            src.copyRecursively(dst, overwrite = true)
        }
    }
}

/** Apply assets/files/config/removeFiles to delete unwanted services / apps from the rootfs. */
object RemoveFilesPlugin : PipelinePlugin {
    override val id = Plugins.REMOVE_FILES
    override val label = "removeFiles"
    override val title = "精简系统文件"
    override val description = "按 files/config/removeFiles 列表清理无用 APP/服务"

    override fun run(ctx: PipelineContext) {
        val list = File(ctx.filesRoot, "config/removeFiles")
        if (!list.isFile) return
        list.useLines { seq ->
            seq.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.forEach { rel ->
                val target = File(ctx.work, rel)
                if (target.exists()) {
                    Logger.n("Delete ${target.relativeTo(ctx.work)}")
                    target.deleteRecursively()
                }
            }
        }
    }
}
