package com.chuest.romtools.core.pipeline.plugins

import com.chuest.romtools.core.DexPatcher
import com.chuest.romtools.core.Plugins
import com.chuest.romtools.core.pipeline.PipelineContext
import com.chuest.romtools.core.pipeline.PipelinePlugin

/** MIUIPersonalAssistantPhoneMIUI15.apk — unlock paid widgets in the picker. */
object PersonalAssistantPlugin : PipelinePlugin {
    override val id = Plugins.PERSONAL_ASSISTANT
    override val label = "personalAssistantPatch"
    override val title = "个性化助手破解"
    override val description = "MIUIPersonalAssistantPhoneMIUI15.apk：付费小组件直接添加"

    override fun run(ctx: PipelineContext) {
        val mamlEdit = "Lcom/miui/maml/widget/edit/"
        val pickerBean = "Lcom/miui/personalassistant/picker/business/detail/bean/"
        val utils = "Lcom/miui/personalassistant/picker/business/detail/utils/"
        val viewModel = "Lcom/miui/personalassistant/picker/business/detail/PickerDetailViewModel;"

        ctx.patchApkDex(
            "product/product/priv-app/MIUIPersonalAssistantPhoneMIUI15/MIUIPersonalAssistantPhoneMIUI15.apk",
            "MIUIPersonalAssistantPhoneMIUI15",
            compilerFilter = "speed",
        ) { cls ->
            when (cls.type) {
                "${mamlEdit}MamlutilKt;" -> DexPatcher.replaceMethodBody(
                    cls,
                    match = { it.name == "themeManagerSupportPaidWidget" },
                    build = { DexPatcher.returnIntConst(it, 0) },
                )
                "${pickerBean}PickerDetailResponse;",
                "${pickerBean}PickerDetailResponseWrapper;" -> DexPatcher.replaceMethodBodies(
                    cls,
                    listOf<Pair<(com.android.tools.smali.dexlib2.iface.Method) -> Boolean,
                            (com.android.tools.smali.dexlib2.iface.Method) -> com.android.tools.smali.dexlib2.iface.MethodImplementation>>(
                        { it.name == "isBought" && it.returnType == "Z" } to { DexPatcher.returnIntConst(it, 1) },
                        { it.name == "isPay" && it.returnType == "Z" } to { DexPatcher.returnIntConst(it, 0) },
                    ),
                )
                "${utils}PickerDetailDownloadManager\$Companion;" -> DexPatcher.replaceMethodBody(
                    cls,
                    match = { it.name == "isCanDownload" },
                    build = { DexPatcher.returnIntConst(it, 1) },
                )
                "${utils}PickerDetailUtil;" -> DexPatcher.replaceMethodBody(
                    cls,
                    match = { it.name == "isCanAutoDownloadMaMl" && it.returnType == "Z" },
                    build = { DexPatcher.returnIntConst(it, 1) },
                )
                viewModel -> DexPatcher.replaceMethodBodies(
                    cls,
                    listOf<Pair<(com.android.tools.smali.dexlib2.iface.Method) -> Boolean,
                            (com.android.tools.smali.dexlib2.iface.Method) -> com.android.tools.smali.dexlib2.iface.MethodImplementation>>(
                        { it.name == "isTargetPositionMamlPayAndDownloading" } to { DexPatcher.returnIntConst(it, 0) },
                        { it.name == "checkIsIndependentProcessWidgetForPosition" } to { DexPatcher.returnIntConst(it, 1) },
                        { it.name == "isCanDirectAddMaMl" } to { DexPatcher.returnIntConst(it, 1) },
                        { it.name == "shouldCheckMamlBoughtState" } to { DexPatcher.returnIntConst(it, 0) },
                    ),
                )
                else -> cls
            }
        }
    }
}
