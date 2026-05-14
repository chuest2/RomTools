package com.chuest.romtools.core.pipeline

import android.content.Context
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.chuest.romtools.core.AssetCopy
import com.chuest.romtools.core.DexPatcher
import com.chuest.romtools.core.Logger
import com.chuest.romtools.core.NativeExec
import java.io.File

/**
 * Shared state and helpers passed to every [PipelinePlugin] during a build.
 *
 * Plugins read inputs from this context (work dir, asset roots, user feature flags) and call back
 * into [zipalignAndInstall], [runDex2Oat] and [patchApkDex] for the boilerplate that used to live
 * privately inside `RomPipeline`.
 */
class PipelineContext(
    val androidCtx: Context,
    val workRoot: File,
    val features: Features,
) {
    /**
     * @property enabled set of plugin IDs the user opted in (see `Plugins.ALL`).
     * @property apatchSuperKey consumed only by the APatch plugin.
     */
    data class Features(
        val enabled: Set<String>,
        val apatchSuperKey: String? = null,
    )

    val filesRoot: File get() = AssetCopy.filesRoot(androidCtx)
    val blobsRoot: File get() = AssetCopy.blobsRoot(androidCtx)
    val work: File = File(workRoot, "work")
    val images: File = File(work, "images")

    fun isEnabled(id: String): Boolean = id in features.enabled

    // ============================================================================================
    // helpers shared by every dex / apk plugin
    // ============================================================================================
    fun zipalignAndInstall(built: File, dst: File) {
        dst.parentFile?.mkdirs()
        NativeExec.runOrThrow(
            androidCtx, NativeExec.ZIPALIGN,
            listOf("4", built.absolutePath, dst.absolutePath), work
        )
    }

    fun runDex2Oat(
        dexFile: String,
        oatRel: String,
        artRel: String? = null,
        profile: String? = null,
        compilerFilter: String,
    ) {
        File(work, oatRel).parentFile?.mkdirs()
        val args = mutableListOf(
            "--dex-file=$dexFile",
            "--instruction-set=arm64",
            "--compiler-filter=$compilerFilter",
            "--oat-file=$oatRel",
        )
        if (profile != null) args += "--profile-file=$profile"
        if (artRel != null) args += "--app-image-file=$artRel"
        NativeExec.runOrThrow(androidCtx, NativeExec.DEX2OAT, args, work)
    }

    /**
     * Pure-dexlib2 APK patch: drops the original apk + odex, runs [transform] over every class,
     * then zipalign + dex2oat.
     */
    fun patchApkDex(
        apkRel: String,
        label: String,
        compilerFilter: String,
        transform: (ClassDef) -> ClassDef,
    ) {
        Logger.n("Patching $label.apk via dexlib2")
        val tmp = File(work, "tmp"); tmp.deleteRecursively(); tmp.mkdirs()
        val srcApk = File(work, apkRel)
        val patched = File(tmp, "$label.apk")
        DexPatcher.patchJar(srcApk, patched, transformClass = transform)
        srcApk.delete()
        val baseDir = apkRel.substringBeforeLast('/')
        File(work, "$baseDir/oat/arm64").listFiles()?.forEach { it.delete() }
        zipalignAndInstall(patched, File(work, apkRel))
        runDex2Oat(
            dexFile = apkRel,
            oatRel = "$baseDir/oat/arm64/$label.odex",
            compilerFilter = compilerFilter,
        )
        tmp.deleteRecursively()
    }
}
