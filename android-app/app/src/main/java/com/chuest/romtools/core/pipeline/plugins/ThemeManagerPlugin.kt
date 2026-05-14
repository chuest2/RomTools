package com.chuest.romtools.core.pipeline.plugins

import com.chuest.romtools.core.DexPatcher
import com.chuest.romtools.core.Plugins
import com.chuest.romtools.core.pipeline.PipelineContext
import com.chuest.romtools.core.pipeline.PipelinePlugin

/** MIUIThemeManager.apk — block ads, bypass paid verification, unlock paid widgets. */
object ThemeManagerPlugin : PipelinePlugin {
    override val id = Plugins.THEME_MANAGER
    override val label = "themeManagerPatch"
    override val title = "主题破解"
    override val description = "MIUIThemeManager.apk：去广告、绕过付费校验、解锁付费小组件"

    override fun run(ctx: PipelineContext) {
        val ad = "Lcom/android/thememanager/basemodule/ad/model/"
        val resModel = "Lcom/android/thememanager/basemodule/resource/model/"
        val presenter = "Lcom/android/thememanager/module/detail/presenter/"
        val detailView = "Lcom/android/thememanager/module/detail/view/"
        val mamlEdit = "Lcom/miui/maml/widget/edit/"
        val onlineDetail = "Lcom/android/thememanager/detail/theme/model/OnlineResourceDetail;"
        val themeRoot = "Lcom/android/thememanager/"

        ctx.patchApkDex(
            "product/product/app/MIUIThemeManager/MIUIThemeManager.apk",
            "MIUIThemeManager",
            compilerFilter = "speed",
        ) { cls ->
            // Mod3 + Mod6: rename DRM_ERROR_UNKNOWN → DRM_SUCCESS inside two specific subtrees.
            val mod36 = when {
                DexPatcher.isDeepChildOf(cls.type, themeRoot, depth = 2) ||
                    cls.type.startsWith(mamlEdit) ->
                    DexPatcher.renameIdentifier(cls, "DRM_ERROR_UNKNOWN", "DRM_SUCCESS")
                else -> cls
            }
            when {
                cls.type == "${ad}AdInfo;" -> DexPatcher.replaceMethodBody(
                    mod36,
                    match = { it.name == "isVideoAd" && it.parameters.isEmpty() && it.returnType == "Z" },
                    build = { DexPatcher.returnIntConst(it, 0) },
                )
                cls.type == "${ad}AdInfoResponse;" -> DexPatcher.replaceMethodBody(
                    mod36,
                    match = { it.name == "isAdValid" },
                    build = { DexPatcher.returnIntConst(it, 0) },
                )
                cls.type == "${resModel}Resource;" -> DexPatcher.replaceMethodBody(
                    mod36,
                    match = { it.name == "isAuthorizedResource" && it.parameters.isEmpty() && it.returnType == "Z" },
                    build = { DexPatcher.returnIntConst(it, 0) },
                )
                cls.type == "${presenter}qrj;" -> DexPatcher.replaceMethodBody(
                    mod36,
                    match = { it.name == "p" && it.parameters.isEmpty() && it.returnType == "Z" },
                    build = { DexPatcher.returnIntConst(it, 1) },
                )
                cls.type.startsWith(detailView) -> DexPatcher.mapMethods(mod36) { m ->
                    DexPatcher.forceIfEqzAfterIgetBoolean(m, onlineDetail, "bought")
                }
                cls.type == "${mamlEdit}MamlutilKt;" -> DexPatcher.replaceMethodBody(
                    mod36,
                    match = { it.name == "themeManagerSupportPaidWidget" },
                    build = { DexPatcher.returnIntConst(it, 0) },
                )
                else -> mod36
            }
        }
    }
}
