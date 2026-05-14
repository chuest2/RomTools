package com.chuest.romtools.core

import android.content.Context
import java.io.File

/**
 * Runs native binaries that are shipped as fake .so files inside jniLibs/arm64-v8a/.
 * On Android 10+ only files under applicationInfo.nativeLibraryDir are executable,
 * and they MUST be named lib*.so to be extracted by the package installer.
 *
 * Mapping convention (see prepare-jnilibs.sh):
 *   bin/payload-dumper   -> jniLibs/arm64-v8a/libpayload_dumper.so
 *   bin/extract.erofs    -> jniLibs/arm64-v8a/libextract_erofs.so
 *   bin/mkfs.erofs       -> jniLibs/arm64-v8a/libmkfs_erofs.so
 *   bin/lpmake           -> jniLibs/arm64-v8a/liblpmake.so
 *   bin/magiskboot       -> jniLibs/arm64-v8a/libmagiskboot.so
 *   bin/dex2oat          -> jniLibs/arm64-v8a/libdex2oat.so
 *   bin/ksud             -> jniLibs/arm64-v8a/libksud.so
 *   bin/kptools          -> jniLibs/arm64-v8a/libkptools.so
 *   bin/busybox          -> jniLibs/arm64-v8a/libbusybox.so
 *   bin/zipalign         -> jniLibs/arm64-v8a/libzipalign.so
 *   lib/lib*.so          -> jniLibs/arm64-v8a/lib*.so (kept verbatim, dex2oat deps)
 *   bin/kpimg            -> assets/blobs/kpimg          (not executable, read by kptools)
 */
object NativeExec {

    data class Bin(val key: String, val libName: String)

    val PAYLOAD_DUMPER = Bin("payload-dumper", "libpayload_dumper.so")
    val EXTRACT_EROFS  = Bin("extract.erofs",  "libextract_erofs.so")
    val MKFS_EROFS     = Bin("mkfs.erofs",     "libmkfs_erofs.so")
    val LPMAKE         = Bin("lpmake",         "liblpmake.so")
    val MAGISKBOOT     = Bin("magiskboot",     "libmagiskboot.so")
    val DEX2OAT        = Bin("dex2oat",        "libdex2oat.so")
    val KSUD           = Bin("ksud",           "libksud.so")
    val KPTOOLS        = Bin("kptools",        "libkptools.so")
    val BUSYBOX        = Bin("busybox",        "libbusybox.so")
    val ZIPALIGN       = Bin("zipalign",       "libzipalign.so")

    fun nativeDir(ctx: Context): File = File(ctx.applicationInfo.nativeLibraryDir)

    fun path(ctx: Context, bin: Bin): String = File(nativeDir(ctx), bin.libName).absolutePath

    data class Result(val exit: Int, val stdout: String, val stderr: String)

    /**
     * Execute a packaged native binary. Working directory + env are configured to mimic the bash script
     * (`LD_LIBRARY_PATH=<rootPath>/lib`).
     */
    fun run(
        ctx: Context,
        bin: Bin,
        args: List<String>,
        workDir: File,
        env: Map<String, String> = emptyMap(),
        captureStdout: Boolean = false
    ): Result {
        val nlib = nativeDir(ctx).absolutePath
        val cmd = mutableListOf<String>().apply {
            add(File(nlib, bin.libName).absolutePath)
            addAll(args)
        }
        val pb = ProcessBuilder(cmd)
            .directory(workDir)
            .redirectErrorStream(false)
        val pbEnv = pb.environment()
        // ART libs needed by dex2oat live in the same nativeLibraryDir.
        pbEnv["LD_LIBRARY_PATH"] = nlib + (pbEnv["LD_LIBRARY_PATH"]?.let { ":$it" } ?: "")
        pbEnv["TMPDIR"] = workDir.absolutePath
        pbEnv["HOME"] = workDir.absolutePath
        env.forEach { (k, v) -> pbEnv[k] = v }

        Logger.i("\$ ${bin.key} ${args.joinToString(" ")}")
        val p = pb.start()
        val outBuf = StringBuilder()
        val errBuf = StringBuilder()

        val outT = Thread {
            p.inputStream.bufferedReader().useLines { seq ->
                seq.forEach {
                    if (captureStdout) outBuf.appendLine(it) else Logger.i(it)
                }
            }
        }.also { it.start() }
        val errT = Thread {
            p.errorStream.bufferedReader().useLines { seq ->
                seq.forEach {
                    errBuf.appendLine(it)
                    Logger.w(it)
                }
            }
        }.also { it.start() }

        val code = p.waitFor()
        outT.join(); errT.join()
        if (code != 0) Logger.w("${bin.key} exited with code $code")
        return Result(code, outBuf.toString(), errBuf.toString())
    }

    fun runOrThrow(
        ctx: Context, bin: Bin, args: List<String>, workDir: File,
        env: Map<String, String> = emptyMap(), captureStdout: Boolean = false
    ): Result {
        val r = run(ctx, bin, args, workDir, env, captureStdout)
        if (r.exit != 0) throw RuntimeException("${bin.key} failed (exit=${r.exit}): ${r.stderr.take(2000)}")
        return r
    }
}
