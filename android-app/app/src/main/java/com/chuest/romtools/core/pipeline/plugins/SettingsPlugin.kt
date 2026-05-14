package com.chuest.romtools.core.pipeline.plugins

import com.chuest.romtools.core.ArscPatcher
import com.chuest.romtools.core.DexPatcher
import com.chuest.romtools.core.Logger
import com.chuest.romtools.core.Plugins
import com.chuest.romtools.core.SmaliAssembler
import com.chuest.romtools.core.pipeline.PipelineContext
import com.chuest.romtools.core.pipeline.PipelinePlugin
import java.io.File

/**
 * Settings.apk — the only patch that needs *both* dex and resource changes. Implemented as
 *
 *   1. ARSCLib loads the apk + framework-res, injects 4 raw layout XMLs (encoded to AXML
 *      lazily at write time), registers a new `layout/my_device_info_item2` entry, and
 *      grows the `notification_icon_counts_values` string-array with two extra items.
 *   2. ARSCLib writes the apk back to disk.
 *   3. dexlib2 ([DexPatcher.patchJar]) runs over the just-written apk to:
 *        - inject 3 pre-built classes (BlurBackgroundImageView, PrefWallBlur, …)
 *          assembled at runtime by [SmaliAssembler];
 *        - add a `public static final int my_device_info_item2 = <newId>` field to every
 *          R$layout that already holds `my_device_info_item`;
 *        - rewrite the dex-level patches: getLineNum body, MiuiMemoryCard layout swap,
 *          NotificationStatusBarSettings 3→5 icon counts, MiuiAboutPhoneUtils, SettingsFeatures,
 *          MiuiSettings IS_GLOBAL_BUILD.
 */
object SettingsPlugin : PipelinePlugin {
    override val id = Plugins.SETTINGS
    override val label = "settingsPatch"
    override val title = "设置界面与功能定制"
    override val description =
        "Settings.apk：自定义关于本机卡片、通知图标 5/7、隐藏商店入口、全球版定位为 true"

    override fun run(ctx: PipelineContext) {
        val work = ctx.work
        val apkRel = "system_ext/system_ext/priv-app/Settings/Settings.apk"
        val baseDir = apkRel.substringBeforeLast('/')
        Logger.n("Patching Settings.apk via ARSCLib + dexlib2")

        val tmp = File(work, "tmp"); tmp.deleteRecursively(); tmp.mkdirs()
        val settingsFiles = File(ctx.filesRoot, "app/Settings")
        val srcApk = File(work, apkRel)
        val arscOut = File(tmp, "Settings-arsc.apk")
        val dexOut  = File(tmp, "Settings-final.apk")

        // ----- 1. ARSCLib pass: resource changes -------------------------------------------------
        val frameworkRes = File("/system/framework/framework-res.apk").takeIf { it.isFile }
        val apk = ArscPatcher.load(srcApk, frameworkRes)

        for (xml in listOf(
            "device_layout.xml", "miui_version_card.xml",
            "my_device_info_item.xml", "my_device_info_item2.xml",
        )) {
            ArscPatcher.replaceXml(apk, "res/layout/$xml", File(settingsFiles, xml))
        }

        val newLayoutId = ArscPatcher.addFileEntry(
            apk, typeName = "layout",
            entryName = "my_device_info_item2",
            path = "res/layout/my_device_info_item2.xml",
        )
        Logger.n("Registered my_device_info_item2 → 0x%08x".format(newLayoutId))

        runCatching {
            ArscPatcher.appendStringArrayItems(
                apk, arrayName = "notification_icon_counts_values",
                items = listOf("5", "7"),
            )
        }.onFailure { Logger.n("WARN: notification_icon_counts_values append skipped: ${it.message}") }

        ArscPatcher.writeApk(apk, arscOut)
        apk.close()

        // ----- 2. Assemble pre-built smali into ClassDefs ---------------------------------------
        val asmDir = File(tmp, "asm").apply { mkdirs() }
        val extraClasses = SmaliAssembler.assembleToClassDefs(
            roots = listOf(File(settingsFiles, "com")),
            apiLevel = 34,
            workDir = asmDir,
        )

        val getLineNumImpl = SmaliAssembler.assembleMethodImpl(
            body = File(settingsFiles, "basicInfoReplace.smali").readText(),
            stubClassDescriptor = "Lcom/android/settings/device/DeviceBasicInfoPresenter;",
            methodHeader = "private getLineNum()I",
            apiLevel = 34,
            workDir = File(tmp, "asm-fragment").apply { mkdirs() },
        )

        // ----- 3. dexlib2 pass: dex changes -----------------------------------------------------
        val rLayoutSuffix = "/R\$layout;"
        val deviceBasicInfo = "Lcom/android/settings/device/DeviceBasicInfoPresenter;"
        val miuiMemoryCard  = "Lcom/android/settings/device/MiuiMemoryCard;"
        val notifSettings   = "Lcom/android/settings/NotificationStatusBarSettings;"
        val miuiAboutPhone  = "Lcom/android/settings/device/MiuiAboutPhoneUtils;"
        val settingsFeats   = "Lcom/android/settings/utils/SettingsFeatures;"
        val miuiSettingsCls = "Lcom/android/settings/MiuiSettings;"
        val miuiBuild       = "Lmiui/os/Build;"

        DexPatcher.patchJar(arscOut, dexOut, extraClasses = extraClasses) { cls ->
            when {
                cls.type.startsWith("Lcom/android/settings") && cls.type.endsWith(rLayoutSuffix) &&
                    cls.staticFields.any { it.name == "my_device_info_item" } ->
                    DexPatcher.addStaticIntField(cls, "my_device_info_item2", newLayoutId)

                cls.type == deviceBasicInfo -> DexPatcher.replaceMethodBody(
                    cls,
                    match = { it.name == "getLineNum" && it.parameters.isEmpty() && it.returnType == "I" },
                    build = { getLineNumImpl },
                )

                cls.type == miuiMemoryCard ->
                    DexPatcher.renameIdentifier(cls, "my_device_info_item", "my_device_info_item2")

                cls.type == notifSettings -> DexPatcher.mapMethods(cls) { m ->
                    DexPatcher.growFilledNewArray(
                        m,
                        oldRegs = intArrayOf(1, 2, 0),
                        newRegs = intArrayOf(1, 2, 0, 3, 4),
                        extraInitValues = intArrayOf(5, 7),
                    )
                }

                cls.type == miuiAboutPhone -> DexPatcher.replaceMethodBody(
                    cls,
                    match = { it.name == "isLocalCnAndChinese" && it.returnType == "Z" },
                    build = { DexPatcher.returnIntConst(it, 0) },
                )

                cls.type == settingsFeats -> DexPatcher.replaceMethodBody(
                    cls,
                    match = { it.name == "isNeedHideShopEntrance" && it.returnType == "Z" },
                    build = { DexPatcher.returnIntConst(it, 1) },
                )

                cls.type == miuiSettingsCls -> DexPatcher.mapMethods(cls) { m ->
                    DexPatcher.replaceSgetBooleanWithConst(m, miuiBuild, "IS_GLOBAL_BUILD", 1)
                }

                else -> cls
            }
        }

        // ----- 4. zipalign + dex2oat ------------------------------------------------------------
        srcApk.delete()
        File(work, "$baseDir/oat/arm64").listFiles()?.forEach { it.delete() }
        ctx.zipalignAndInstall(dexOut, File(work, apkRel))
        ctx.runDex2Oat(
            dexFile = apkRel,
            oatRel = "$baseDir/oat/arm64/Settings.odex",
            compilerFilter = "speed",
        )
        tmp.deleteRecursively()
    }
}
