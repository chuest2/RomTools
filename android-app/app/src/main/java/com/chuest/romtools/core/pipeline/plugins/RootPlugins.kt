package com.chuest.romtools.core.pipeline.plugins

import com.chuest.romtools.core.Logger
import com.chuest.romtools.core.NativeExec
import com.chuest.romtools.core.Plugins
import com.chuest.romtools.core.pipeline.PipelineContext
import com.chuest.romtools.core.pipeline.PipelinePlugin
import java.io.File

/** init_boot.img — inject KernelSU using ksud boot-patch. Mutually exclusive with APatch. */
object KernelSuPlugin : PipelinePlugin {
    override val id = Plugins.KERNELSU
    override val label = "kernelsuPatch"
    override val title = "刷入 KernelSU (init_boot)"
    override val description =
        "用 ksud boot-patch 给 init_boot.img 注入 KernelSU；与 APatch 二选一"

    override fun run(ctx: PipelineContext) {
        Logger.n("Patching init_boot image using KernelSU")
        val work = ctx.work
        val ib = File(work, "init_boot.img")
        File(ctx.images, "init_boot.img").renameTo(ib)
        val r = NativeExec.runOrThrow(
            ctx.androidCtx, NativeExec.KSUD,
            listOf("boot-patch", "-b", "init_boot.img",
                "--kmi", "android14-6.1",
                "--magiskboot", NativeExec.path(ctx.androidCtx, NativeExec.MAGISKBOOT)),
            work, captureStdout = true
        )
        val lines = r.stdout.lines()
        val outIdx = lines.indexOfFirst { it.contains("Output file is written to") }
        require(outIdx >= 0 && outIdx + 1 < lines.size) { "ksud output not understood:\n${r.stdout}" }
        val outPath = lines[outIdx + 1].trim()
        File(outPath).renameTo(File(ctx.images, "init_boot.img"))
    }
}

/**
 * boot.img — patch the kernel with APatch via kptools. Mutually exclusive with KernelSU.
 * Requires [PipelineContext.Features.apatchSuperKey].
 */
object APatchPlugin : PipelinePlugin {
    override val id = Plugins.APATCH
    override val label = "apatchPatch"
    override val title = "刷入 APatch (boot)"
    override val description =
        "用 kptools 给 boot.img 注入 APatch；需填写 SUPERKEY，与 KernelSU 二选一"
    override val defaultOn = false

    override fun run(ctx: PipelineContext) {
        val superKey = ctx.features.apatchSuperKey
            ?: error("SUPERKEY required for APatch")
        Logger.n("Patching boot image using APatch")
        val work = ctx.work
        val boot = File(work, "boot.img")
        File(ctx.images, "boot.img").renameTo(boot)

        NativeExec.runOrThrow(ctx.androidCtx, NativeExec.MAGISKBOOT, listOf("unpack", "boot.img"), work)
        val kOri = File(work, "kernel.ori")
        File(work, "kernel").renameTo(kOri)
        Logger.n("Patching kernel")
        NativeExec.runOrThrow(
            ctx.androidCtx, NativeExec.KPTOOLS,
            listOf("-p", "-i", "kernel.ori", "-S", superKey,
                "-k", File(ctx.blobsRoot, "kpimg").absolutePath,
                "-o", "kernel"),
            work
        )
        kOri.delete()
        NativeExec.runOrThrow(ctx.androidCtx, NativeExec.MAGISKBOOT, listOf("repack", "boot.img"), work)
        File(work, "kernel").delete()
        boot.delete()
        File(work, "new-boot.img").renameTo(File(ctx.images, "boot.img"))
    }
}
