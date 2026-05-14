package com.chuest.romtools.core

import android.content.Context
import java.io.File
import java.io.InputStream

/**
 * Copies the `files/` tree (replacement APKs, smali patches, vbmeta images, …) and the `blobs/`
 * dir (kpimg, etc.) from assets to a stable cache location.
 *
 * App ships these under `assets/`:
 *   assets/files/<everything that used to live in repo files/>
 *   assets/blobs/kpimg
 *
 * Historical `assets/tools/*.dex` (apktool / APKEditor / d8) are gone: Settings.apk patches now go
 * through ARSCLib + dexlib2 + smali assembler, all linked as regular Gradle deps.
 */
object AssetCopy {

    fun root(ctx: Context): File =
        File(ctx.filesDir, "rt-assets").also { it.mkdirs() }

    fun filesRoot(ctx: Context): File = File(root(ctx), "files")
    fun blobsRoot(ctx: Context): File = File(root(ctx), "blobs")

    /** One-shot extract on first launch / version change. */
    fun ensure(ctx: Context) {
        val marker = File(root(ctx), ".v${ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionCode}")
        if (marker.exists()) return
        root(ctx).deleteRecursively(); root(ctx).mkdirs()
        copyAssetDir(ctx, "files", filesRoot(ctx))
        copyAssetDir(ctx, "blobs", blobsRoot(ctx))
        marker.writeText("ok")
        Logger.n("Assets extracted to ${root(ctx)}")
    }

    private fun copyAssetDir(ctx: Context, assetPath: String, dst: File) {
        val list = try { ctx.assets.list(assetPath) ?: emptyArray() } catch (_: Throwable) { emptyArray() }
        if (list.isEmpty()) {
            // try as file
            runCatching { ctx.assets.open(assetPath).use { copyTo(it, dst) } }
            return
        }
        dst.mkdirs()
        for (name in list) {
            val sub = "$assetPath/$name"
            val children = try { ctx.assets.list(sub) ?: emptyArray() } catch (_: Throwable) { emptyArray() }
            if (children.isEmpty()) {
                ctx.assets.open(sub).use { copyTo(it, File(dst, name)) }
            } else {
                copyAssetDir(ctx, sub, File(dst, name))
            }
        }
    }

    private fun copyTo(input: InputStream, dst: File) {
        dst.parentFile?.mkdirs()
        dst.outputStream().use { input.copyTo(it) }
    }
}
