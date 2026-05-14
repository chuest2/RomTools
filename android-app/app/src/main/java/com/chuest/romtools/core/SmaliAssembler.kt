package com.chuest.romtools.core

import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.MethodImplementation
import com.android.tools.smali.smali.Smali
import com.android.tools.smali.smali.SmaliOptions
import java.io.File

/**
 * Thin wrapper over `com.android.tools.smali:smali` that takes a directory tree of .smali source
 * files and turns it into [ClassDef] objects we can merge into an existing dex container via
 * [DexPatcher].
 *
 * Two usage shapes:
 *  - [assembleToClassDefs] — for a whole pre-built class tree (Settings.apk plugin code lives
 *    under files/app/Settings/com/**).
 *  - [assembleMethodImpl] — for a body-only smali fragment (basicInfoReplace.smali) which only
 *    contains the body of `.method private getLineNum()I`; we wrap it in a synthetic class,
 *    assemble, then return just the [MethodImplementation] for transplantation.
 */
object SmaliAssembler {

    /** Build every .smali under [roots] into a dex, then return its class list. */
    fun assembleToClassDefs(
        roots: List<File>,
        apiLevel: Int,
        workDir: File,
        opcodes: Opcodes = Opcodes.forApi(apiLevel),
    ): List<ClassDef> {
        workDir.mkdirs()
        val outDex = File(workDir, "assembled.dex")
        val options = SmaliOptions().apply {
            apiLevel = apiLevel
            outputDexFile = outDex.absolutePath
            jobs = 1
        }
        val ok = Smali.assemble(options, roots.map { it.absolutePath })
        if (!ok) error("smali assembler reported errors for: $roots")
        val dexFile = DexFileFactory.loadDexFile(outDex, opcodes)
        return dexFile.classes.toList()
    }

    /**
     * Take a smali method-body fragment (everything *between* `.method ...` and `.end method`)
     * and return its compiled [MethodImplementation].
     *
     * [stubClassDescriptor] / [methodHeader] only exist to satisfy the assembler grammar; they
     * must be syntactically valid but otherwise have no effect on the returned implementation.
     * The caller wires the resulting impl onto whatever real method via [DexPatcher.withBody].
     */
    fun assembleMethodImpl(
        body: String,
        stubClassDescriptor: String,
        methodHeader: String,
        apiLevel: Int,
        workDir: File,
    ): MethodImplementation {
        workDir.mkdirs()
        val smali = buildString {
            append(".class $stubClassDescriptor\n")
            append(".super Ljava/lang/Object;\n\n")
            append(".method $methodHeader\n")
            append(body)
            if (!body.endsWith("\n")) append('\n')
            append(".end method\n")
        }
        val srcFile = File(workDir, "Stub.smali").apply { writeText(smali) }
        val outDex = File(workDir, "stub.dex")
        val options = SmaliOptions().apply {
            apiLevel = apiLevel
            outputDexFile = outDex.absolutePath
            jobs = 1
        }
        val ok = Smali.assemble(options, listOf(srcFile.absolutePath))
        if (!ok) error("smali assembler failed for fragment in $workDir")
        val dex = DexFileFactory.loadDexFile(outDex, Opcodes.forApi(apiLevel))
        val cls = dex.classes.firstOrNull { it.type == stubClassDescriptor }
            ?: error("stub class $stubClassDescriptor not produced")
        val method = cls.methods.firstOrNull()
            ?: error("stub class has no methods")
        return method.implementation
            ?: error("stub method has no implementation (abstract?)")
    }
}
