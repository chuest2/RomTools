package com.chuest.romtools.core.pipeline.plugins

import com.chuest.romtools.core.Plugins
import com.chuest.romtools.core.SmaliPatcher
import com.chuest.romtools.core.pipeline.PipelineContext
import com.chuest.romtools.core.pipeline.PipelinePlugin
import java.io.File

/**
 * build.prop and product/etc/device_features XMLs — disable memory extension, enable HFR / Dolby
 * / HiFi feature flags.
 */
object ModifyPropsPlugin : PipelinePlugin {
    override val id = Plugins.MODIFY_PROPS
    override val label = "modifyProps"
    override val title = "关闭内存扩展 + 启用 HFR/Dolby/HiFi"
    override val description =
        "build.prop persist.miui.extm.enable=0 + device_features 多项开关"

    override fun run(ctx: PipelineContext) {
        val work = ctx.work
        for (rel in listOf("system_ext/system_ext/etc/build.prop", "product/product/etc/build.prop")) {
            SmaliPatcher.replaceLiteral(File(work, rel), "persist.miui.extm.enable=1", "persist.miui.extm.enable=0")
        }
        val dfeats = File(work, "product/product/etc/device_features")
        dfeats.listFiles { f -> f.isFile && f.name.endsWith(".xml") }?.forEach { f ->
            for (flag in listOf("support_hfr_video_pause", "support_dolby", "support_video_hfr_mode", "support_hifi")) {
                SmaliPatcher.replaceLiteral(
                    f,
                    "<bool name=\"$flag\">false</bool>",
                    "<bool name=\"$flag\">true</bool>"
                )
            }
        }
    }
}
