package com.chuest.romtools.core.pipeline.plugins

import com.chuest.romtools.core.Logger
import com.chuest.romtools.core.Plugins
import com.chuest.romtools.core.SmaliPatcher
import com.chuest.romtools.core.pipeline.PipelineContext
import com.chuest.romtools.core.pipeline.PipelinePlugin
import java.io.File

/** vendor/etc/fstab.qcom — strip AVB and file/metadata encryption flags. */
object RemoveAvbPlugin : PipelinePlugin {
    override val id = Plugins.REMOVE_AVB
    override val label = "removeAVB"
    override val title = "去除 AVB 校验"
    override val description =
        "改 vendor/etc/fstab.qcom，移除 AVB 与 file/metadata 加密，配合 vbmeta 替换"

    override fun run(ctx: PipelineContext) {
        Logger.n("Removing AVB in vendor image")
        val fstab = File(ctx.work, "vendor/vendor/etc/fstab.qcom")
        SmaliPatcher.replaceRegex(fstab, Regex("""avb,"""), "")
        SmaliPatcher.replaceRegex(fstab, Regex("""avb=vbmeta,"""), "")
        SmaliPatcher.replaceRegex(fstab, Regex("""avb=vbmeta_system,"""), "")
        SmaliPatcher.replaceLiteral(
            fstab,
            ",avb_keys=/avb/q-gsi.avbpubkey:/avb/r-gsi.avbpubkey:/avb/s-gsi.avbpubkey:/avb/t-gsi.avbpubkey:/avb/u-gsi.avbpubkey",
            ""
        )
        SmaliPatcher.replaceLiteral(fstab, ",fileencryption=aes-256-xts:aes-256-cts:v2+inlinecrypt_optimized+wrappedkey_v0", "")
        SmaliPatcher.replaceLiteral(fstab, ",metadata_encryption=aes-256-xts:wrappedkey_v0", "")
    }
}
