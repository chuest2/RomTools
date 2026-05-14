package com.chuest.romtools.core.pipeline

import com.chuest.romtools.core.pipeline.plugins.APatchPlugin
import com.chuest.romtools.core.pipeline.plugins.KernelSuPlugin
import com.chuest.romtools.core.pipeline.plugins.MiuiSystemUIPlugin
import com.chuest.romtools.core.pipeline.plugins.MmsVerificationCodeAutoCopyPlugin
import com.chuest.romtools.core.pipeline.plugins.ModifyPropsPlugin
import com.chuest.romtools.core.pipeline.plugins.PersonalAssistantPlugin
import com.chuest.romtools.core.pipeline.plugins.PowerKeeperPlugin
import com.chuest.romtools.core.pipeline.plugins.PreventThemeRecoveryPlugin
import com.chuest.romtools.core.pipeline.plugins.RemoveAvbPlugin
import com.chuest.romtools.core.pipeline.plugins.RemoveFilesPlugin
import com.chuest.romtools.core.pipeline.plugins.RemoveSignVerifyPlugin
import com.chuest.romtools.core.pipeline.plugins.ReplaceApksPlugin
import com.chuest.romtools.core.pipeline.plugins.SettingsPlugin
import com.chuest.romtools.core.pipeline.plugins.ThemeManagerPlugin

/**
 * Static catalog of every [PipelinePlugin], split into phase buckets that `RomPipeline.run()`
 * iterates in order.
 *
 * - [systemPatches]  : run after erofs unpack, before erofs repack.
 *                      Touch the system / vendor / product / system_ext partition trees.
 * - [bootPatches]    : run after super.img + vbmeta replacement, on boot.img / init_boot.img.
 *                      Mutually exclusive: KernelSU vs. APatch. Pipeline enforces the check.
 */
object PluginRegistry {
    val systemPatches: List<PipelinePlugin> = listOf(
        RemoveAvbPlugin,
        RemoveSignVerifyPlugin,
        ReplaceApksPlugin,
        RemoveFilesPlugin,
        ThemeManagerPlugin,
        PreventThemeRecoveryPlugin,
        PersonalAssistantPlugin,
        MmsVerificationCodeAutoCopyPlugin,
        PowerKeeperPlugin,
        SettingsPlugin,
        MiuiSystemUIPlugin,
        ModifyPropsPlugin,
    )

    val bootPatches: List<PipelinePlugin> = listOf(
        KernelSuPlugin,
        APatchPlugin,
    )

    val all: List<PipelinePlugin> = systemPatches + bootPatches

    fun byId(id: String): PipelinePlugin? = all.firstOrNull { it.id == id }
}
