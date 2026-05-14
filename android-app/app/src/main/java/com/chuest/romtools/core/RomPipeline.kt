package com.chuest.romtools.core

import android.content.Context
import android.net.Uri
import com.chuest.romtools.core.pipeline.PipelineContext
import com.chuest.romtools.core.pipeline.PipelinePlugin
import com.chuest.romtools.core.pipeline.PluginRegistry
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

/**
 * Port of start.sh `main()` flow. The pipeline now has only three responsibilities:
 *
 *   1. Mandatory pre-phase  — payload extraction, erofs unpack.
 *   2. Run every enabled plugin (see [PluginRegistry]).
 *   3. Mandatory post-phase — erofs repack, super.img, vbmeta replacement, cust replacement,
 *      boot-patches (KernelSU / APatch are themselves plugins), final cleanup, flash.bat.
 *
 * All optional patches live under `core/pipeline/plugins/`, one per file.
 *
 * @param romZipUri SAF Uri to a local *.zip (no aria2 / download).
 * @param workRoot  regular filesystem dir (volume must have ≈40 GiB free).
 */
class RomPipeline(
    ctx: Context,
    private val romZipUri: Uri,
    val workRoot: File,
    features: Features = Features()
) {
    /**
     * @property enabled set of plugin IDs the user ticked in the UI (see [Plugins]).
     * @property apatchSuperKey consumed only if [Plugins.APATCH] is in [enabled].
     */
    data class Features(
        val enabled: Set<String> = Plugins.DEFAULT_ENABLED,
        val apatchSuperKey: String? = null,
    )

    private val pipelineCtx = PipelineContext(
        androidCtx = ctx,
        workRoot = workRoot,
        features = PipelineContext.Features(features.enabled, features.apatchSuperKey),
    )

    private val androidCtx = ctx
    private val filesRoot get() = pipelineCtx.filesRoot
    private val work get() = pipelineCtx.work
    private val images get() = pipelineCtx.images

    // ============================================================================================
    // entrypoint
    // ============================================================================================
    fun run() {
        AssetCopy.ensure(androidCtx)
        workRoot.mkdirs(); work.mkdirs(); images.mkdirs()

        Logger.n("Unzipping ROM into $work")
        unzipRomFromUri()

        // strip OTA metadata
        listOf("META-INF", "apex_info.pb", "care_map.pb", "payload_properties.txt")
            .forEach { File(work, it).deleteRecursively() }

        Logger.n("Dumping images from payload.bin")
        NativeExec.runOrThrow(
            androidCtx, NativeExec.PAYLOAD_DUMPER,
            listOf("-o", images.absolutePath, "payload.bin"),
            work
        )
        File(work, "payload.bin").delete()

        listOf("system", "vendor", "product", "system_ext").forEach { unpackErofs(it) }

        // ---- partition-tree plugins (between erofs unpack and repack) --------------------------
        PluginRegistry.systemPatches.forEach(::runPlugin)

        listOf("system", "vendor", "product", "system_ext").forEach { repackErofs(it) }

        // move pre-built partitions next to the resized images for lpmake
        for (p in listOf("odm", "mi_ext", "system_dlkm", "vendor_dlkm")) {
            File(images, "$p.img").renameTo(File(work, "$p.img"))
        }

        makeSuperImg()
        removeVbmetaVerify()
        replaceCust()

        // ---- boot-image plugins (KernelSU / APatch) --------------------------------------------
        if (pipelineCtx.isEnabled(Plugins.KERNELSU) && pipelineCtx.isEnabled(Plugins.APATCH)) {
            error("KernelSU 与 APatch 不能同时启用（一个改 init_boot，一个改 boot，但用户只能选一个 root 方案）")
        }
        PluginRegistry.bootPatches.forEach(::runPlugin)

        // cleanup intermediates
        sequenceOf(
            "system", "vendor", "product", "system_ext",
            "system.img", "vendor.img", "product.img", "system_ext.img",
            "odm.img", "mi_ext.img", "system_dlkm.img", "vendor_dlkm.img",
            "init_boot.img", "boot.img"
        ).forEach { File(work, it).deleteRecursively() }

        File(filesRoot, "flash.bat").copyTo(File(work, "flash.bat"), overwrite = true)
        Logger.n("Done. Output at $images + $work/flash.bat")
    }

    private fun runPlugin(plugin: PipelinePlugin) {
        if (!pipelineCtx.isEnabled(plugin.id)) {
            Logger.i("[skip] ${plugin.label}  (plugin '${plugin.id}' disabled)")
            return
        }
        plugin.run(pipelineCtx)
    }

    // ============================================================================================
    // unzip from SAF Uri (replaces unzip + aria2 download)
    // ============================================================================================
    private fun unzipRomFromUri() {
        val cr = androidCtx.contentResolver
        cr.openInputStream(romZipUri).use { input ->
            requireNotNull(input) { "cannot open $romZipUri" }
            ZipInputStream(input.buffered()).use { zis ->
                while (true) {
                    val e = zis.nextEntry ?: break
                    val out = File(work, e.name)
                    if (e.isDirectory) { out.mkdirs(); zis.closeEntry(); continue }
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { zis.copyTo(it, bufferSize = 1 shl 20) }
                    zis.closeEntry()
                }
            }
        }
    }

    // ============================================================================================
    // erofs (un)pack — mandatory, not plugin-gated
    // ============================================================================================
    private fun unpackErofs(name: String) {
        val src = File(images, "$name.img")
        val dst = File(work, name)
        src.renameTo(File(work, "$name.img"))
        Logger.n("Unpacking $name image")
        NativeExec.runOrThrow(
            androidCtx, NativeExec.EXTRACT_EROFS,
            listOf("-i", "$name.img", "-o", name, "-x"), work
        )
        File(work, "$name.img").delete()
        require(dst.isDirectory) { "extract.erofs produced no $dst" }
    }

    private fun repackErofs(name: String) {
        val fc  = File(work, "$name/config/${name}_file_contexts").absolutePath
        val fs  = File(work, "$name/config/${name}_fs_config").absolutePath
        val out = File(work, "$name.img").absolutePath
        val inDir = File(work, "$name/$name").absolutePath
        Logger.n("Repacking $name image")
        NativeExec.runOrThrow(
            androidCtx, NativeExec.MKFS_EROFS,
            listOf(
                "-zlz4hc", "-T1640966400",
                "--mount-point=/$name",
                "--fs-config-file=$fs",
                "--file-contexts=$fc",
                out, inDir,
            ), work
        )
    }

    // ============================================================================================
    // super.img / vbmeta / cust — mandatory, no off switch
    // ============================================================================================
    private fun removeVbmetaVerify() {
        Logger.n("Removing verification of vbmeta")
        File(filesRoot, "images/vbmeta.img").copyTo(File(images, "vbmeta.img"), overwrite = true)
        File(filesRoot, "images/vbmeta_system.img").copyTo(File(images, "vbmeta_system.img"), overwrite = true)
    }

    private fun replaceCust() {
        Logger.n("Replacing cust image")
        File(filesRoot, "images/cust.img").copyTo(File(images, "cust.img"), overwrite = true)
    }

    private fun makeSuperImg() {
        Logger.n("Repacking Super image")
        fun size(name: String): String = File(work, name).length().toString()
        val args = mutableListOf(
            "--metadata-size", "65536",
            "--super-name", "super",
            "--device", "super:8321499136",
            "--group", "main_a:8321499136",
            "--group", "main_b:8321499136",
            "--metadata-slots", "3",
            "--virtual-ab",
        )
        for (p in listOf("system", "vendor", "product", "system_ext", "odm", "mi_ext", "system_dlkm", "vendor_dlkm")) {
            args += listOf("--partition", "${p}_a:readonly:${size("$p.img")}:main_a")
            args += listOf("--image", "${p}_a=$p.img")
            args += listOf("--partition", "${p}_b:readonly:0:main_b")
        }
        args += listOf("--sparse", "--output", "images/super.img")
        NativeExec.runOrThrow(androidCtx, NativeExec.LPMAKE, args, work)
    }
}
