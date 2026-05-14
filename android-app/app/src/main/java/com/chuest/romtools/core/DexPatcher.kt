package com.chuest.romtools.core

import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.builder.MethodImplementationBuilder
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction10x
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction11n
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction11x
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction21c
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction21s
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction21t
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction22c
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction31i
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction35c
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Field
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.MethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableDexFile
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import com.android.tools.smali.dexlib2.util.MethodUtil
import com.android.tools.smali.dexlib2.writer.pool.DexPool
import java.io.File
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Direct DEX byte-code patcher built on top of dexlib2. Replaces the previous "apktool d → sed →
 * apktool b" round-trip for every patch that only touches code (no AXML/arsc edits).
 *
 * Why dexlib2 here:
 *   - No JVM (apktool / APKEditor) needed for the simple patches; everything runs as a normal
 *     Kotlin library on ART.
 *   - Lossless: annotations, parameter names, hidden-API restrictions are all preserved.
 *   - Instruction offsets and try/catch ranges are recomputed by [MutableMethodImplementation], so
 *     we don't have to count code units by hand the way the bash + sed approach did.
 */
object DexPatcher {

    /**
     * Re-package [srcJar] into [dstJar], running [transformClass] on every class in every
     * `classes*.dex` entry. If the transform returns the same instance for every class in a dex,
     * the original dex bytes are copied verbatim (cheap). Non-dex entries (META-INF, manifest …)
     * are preserved.
     *
     * Uses STORED entries to keep the layout dex2oat-friendly; zipalign will finalise 4-byte
     * alignment for uncompressed dex entries.
     */
    fun patchJar(
        srcJar: File,
        dstJar: File,
        opcodes: Opcodes = Opcodes.forApi(34),
        transformClass: (ClassDef) -> ClassDef,
    ) = patchJar(srcJar, dstJar, opcodes, emptyList(), transformClass)

    /**
     * Same as the simpler [patchJar], but also injects [extraClasses] into the first dex entry
     * found in the apk/jar. Used by `settingsPatch` to graft pre-assembled .smali classes
     * (BlurBackgroundImageView, PrefWallBlur…) into Settings.apk without bumping the dex count.
     */
    fun patchJar(
        srcJar: File,
        dstJar: File,
        opcodes: Opcodes = Opcodes.forApi(34),
        extraClasses: List<ClassDef>,
        transformClass: (ClassDef) -> ClassDef,
    ) {
        val container = DexFileFactory.loadDexContainer(srcJar, opcodes)
        val rebuilt = LinkedHashMap<String, ByteArray>()
        val firstDexEntry = container.dexEntryNames.firstOrNull()

        for (entryName in container.dexEntryNames) {
            val dex = container.getEntry(entryName)!!.dexFile
            val newClasses = ArrayList<ClassDef>(dex.classes.size + extraClasses.size)
            var changed = false
            for (cls in dex.classes) {
                val nc = transformClass(cls)
                if (nc !== cls) changed = true
                newClasses.add(nc)
            }
            if (entryName == firstDexEntry && extraClasses.isNotEmpty()) {
                newClasses.addAll(extraClasses)
                changed = true
            }
            if (changed) {
                val tmp = File.createTempFile("patch-", ".dex")
                try {
                    DexPool.writeTo(tmp.absolutePath, ImmutableDexFile(opcodes, newClasses))
                    rebuilt[entryName] = tmp.readBytes()
                } finally { tmp.delete() }
            }
        }

        ZipFile(srcJar).use { src ->
            dstJar.parentFile?.mkdirs()
            ZipOutputStream(dstJar.outputStream().buffered()).use { zos ->
                zos.setLevel(Deflater.NO_COMPRESSION)
                // 1. Emit dex entries first, in their original order.
                for (entryName in container.dexEntryNames) {
                    val data = rebuilt[entryName]
                        ?: src.getInputStream(src.getEntry(entryName)).use { it.readBytes() }
                    writeStored(zos, entryName, data)
                }
                // 2. Everything else verbatim.
                val dexSet = container.dexEntryNames.toHashSet()
                val it = src.entries()
                while (it.hasMoreElements()) {
                    val e = it.nextElement()
                    if (e.name in dexSet) continue
                    val bytes = src.getInputStream(e).use { it.readBytes() }
                    writeStored(zos, e.name, bytes)
                }
            }
        }
    }

    private fun writeStored(zos: ZipOutputStream, name: String, bytes: ByteArray) {
        val ze = ZipEntry(name).apply {
            method = ZipEntry.STORED
            size = bytes.size.toLong()
            compressedSize = bytes.size.toLong()
            crc = CRC32().apply { update(bytes) }.value
        }
        zos.putNextEntry(ze)
        zos.write(bytes)
        zos.closeEntry()
    }

    // ============================================================================================
    // class / method transforms
    // ============================================================================================

    /**
     * Return a new ClassDef with every method satisfying [match] replaced by an
     * [ImmutableMethod] whose implementation is built by [build]. If nothing matches, returns
     * [cls] unchanged.
     */
    fun replaceMethodBody(
        cls: ClassDef,
        match: (Method) -> Boolean,
        build: (Method) -> MethodImplementation,
    ): ClassDef {
        val newMethods = ArrayList<Method>()
        var changed = false
        for (m in cls.methods) {
            if (match(m)) {
                newMethods.add(rebuild(m, build(m)))
                changed = true
            } else newMethods.add(m)
        }
        if (!changed) return cls
        return rebuildClass(cls, newMethods)
    }

    /** Generic per-method rewriter; `transform` returns null when nothing changed. */
    fun mapMethods(cls: ClassDef, transform: (Method) -> Method?): ClassDef {
        val newMethods = ArrayList<Method>(cls.methods.toList().size)
        var changed = false
        for (m in cls.methods) {
            val n = transform(m)
            if (n != null) { newMethods.add(n); changed = true } else newMethods.add(m)
        }
        if (!changed) return cls
        return rebuildClass(cls, newMethods)
    }

    private fun rebuild(method: Method, impl: MethodImplementation): Method = ImmutableMethod(
        method.definingClass,
        method.name,
        method.parameters,
        method.returnType,
        method.accessFlags,
        method.annotations,
        method.hiddenApiRestrictions,
        impl,
    )

    private fun rebuildClass(cls: ClassDef, methods: List<Method>): ClassDef = ImmutableClassDef(
        cls.type,
        cls.accessFlags,
        cls.superclass,
        cls.interfaces,
        cls.sourceFile,
        cls.annotations,
        cls.staticFields,
        cls.instanceFields,
        methods,
    )

    /** Public form of [rebuild] for callers that need to swap a single method's body inline. */
    fun withBody(method: Method, impl: MethodImplementation): Method = rebuild(method, impl)

    /**
     * Apply a list of `(predicate, builder)` patches in one pass over the class. The first
     * predicate that matches each method wins; methods unmatched by any predicate are kept
     * unchanged. Faster + tidier than chaining multiple [replaceMethodBody] calls.
     */
    fun replaceMethodBodies(
        cls: ClassDef,
        patches: List<Pair<(Method) -> Boolean, (Method) -> MethodImplementation>>,
    ): ClassDef {
        val newMethods = ArrayList<Method>()
        var changed = false
        for (m in cls.methods) {
            val patch = patches.firstOrNull { it.first(m) }
            if (patch != null) {
                newMethods.add(rebuild(m, patch.second(m)))
                changed = true
            } else newMethods.add(m)
        }
        if (!changed) return cls
        return rebuildClass(cls, newMethods)
    }

    // ============================================================================================
    // body builders
    // ============================================================================================

    /** `return-void` only. */
    fun returnVoid(method: Method): MethodImplementation {
        val regs = MethodUtil.getParameterRegisterCount(method).coerceAtLeast(1)
        val b = MethodImplementationBuilder(regs)
        b.addInstruction(BuilderInstruction10x(Opcode.RETURN_VOID))
        return b.methodImplementation
    }

    /** `const-wide/16 v0, 0` ; `return-wide v0`. */
    fun returnWideZero(method: Method): MethodImplementation {
        val regs = MethodUtil.getParameterRegisterCount(method) + 2
        val b = MethodImplementationBuilder(regs)
        b.addInstruction(BuilderInstruction21s(Opcode.CONST_WIDE_16, 0, 0))
        b.addInstruction(BuilderInstruction11x(Opcode.RETURN_WIDE, 0))
        return b.methodImplementation
    }

    /**
     * `.locals 0` style: `const(/4|/16|) v0, <value>` ; `return v0`. Picks the narrowest opcode
     * for the literal. Used for Boolean / int returning methods (`return false`, `return true`).
     */
    fun returnIntConst(method: Method, value: Int): MethodImplementation {
        val regs = MethodUtil.getParameterRegisterCount(method).coerceAtLeast(1)
        val b = MethodImplementationBuilder(regs)
        when {
            value in -8..7 -> b.addInstruction(BuilderInstruction11n(Opcode.CONST_4, 0, value))
            value in -32768..32767 -> b.addInstruction(BuilderInstruction21s(Opcode.CONST_16, 0, value))
            else -> b.addInstruction(BuilderInstruction31i(Opcode.CONST, 0, value))
        }
        b.addInstruction(BuilderInstruction11x(Opcode.RETURN, 0))
        return b.methodImplementation
    }

    /**
     * Rewrites every call site inside [method] that invokes a method named [calleeName] followed
     * by `move-result*`: drops the invoke+move-result pair and replaces it with a single
     * `const(-4/32) vDST, 0` where vDST was the move-result's destination register.
     *
     * Returns a new Method, or null if nothing matched.
     *
     * This mirrors the bash sed snippet that disabled
     * `getMinimumSignatureSchemeVersionForTargetSdk` per call site.
     */
    fun neutralizeCallSites(method: Method, calleeName: String): Method? {
        val impl = method.implementation ?: return null
        val mut = MutableMethodImplementation(impl)
        val instrs = mut.instructions
        var i = 0
        var changed = false
        while (i < instrs.size) {
            val ins = instrs[i]
            val ref = (ins as? ReferenceInstruction)?.reference
            val isInvoke = ins.opcode in invokeOpcodes
            if (isInvoke && ref is MethodReference && ref.name == calleeName) {
                if (i + 1 < instrs.size) {
                    val nxt = instrs[i + 1]
                    if (nxt.opcode in moveResultOpcodes) {
                        val reg = (nxt as OneRegisterInstruction).registerA
                        val zero = if (reg < 16)
                            BuilderInstruction11n(Opcode.CONST_4, reg, 0)
                        else
                            BuilderInstruction31i(Opcode.CONST, reg, 0)
                        mut.replaceInstruction(i, zero)
                        mut.removeInstruction(i + 1)
                        changed = true
                        i++
                        continue
                    }
                }
            }
            i++
        }
        if (!changed) return null
        return rebuild(method, mut)
    }

    private val invokeOpcodes = setOf(
        Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_STATIC, Opcode.INVOKE_DIRECT,
        Opcode.INVOKE_INTERFACE, Opcode.INVOKE_SUPER,
        Opcode.INVOKE_VIRTUAL_RANGE, Opcode.INVOKE_STATIC_RANGE, Opcode.INVOKE_DIRECT_RANGE,
        Opcode.INVOKE_INTERFACE_RANGE, Opcode.INVOKE_SUPER_RANGE,
    )
    private val moveResultOpcodes = setOf(
        Opcode.MOVE_RESULT, Opcode.MOVE_RESULT_OBJECT, Opcode.MOVE_RESULT_WIDE,
    )

    /**
     * Removes every `invoke-*` instruction in [method] whose target's defining class is exactly
     * [classDescriptor], plus the immediately following `move-result*` (if any). This faithfully
     * implements bash `sed -i "${ln},${ln+5}d"` patterns that nuke an entire singleton call
     * sequence such as `Lmiui/drm/DrmBroadcast;->getInstance + register`.
     */
    fun removeCallSitesToClass(method: Method, classDescriptor: String): Method? {
        val impl = method.implementation ?: return null
        val mut = MutableMethodImplementation(impl)
        var i = 0
        var changed = false
        while (i < mut.instructions.size) {
            val ins = mut.instructions[i]
            val ref = (ins as? ReferenceInstruction)?.reference
            if (ins.opcode in invokeOpcodes && ref is MethodReference &&
                ref.definingClass == classDescriptor
            ) {
                if (i + 1 < mut.instructions.size &&
                    mut.instructions[i + 1].opcode in moveResultOpcodes
                ) mut.removeInstruction(i + 1)
                mut.removeInstruction(i)
                changed = true
                continue
            }
            i++
        }
        if (!changed) return null
        return rebuild(method, mut)
    }

    /**
     * Walks [method] looking for `iget-boolean vR, ..., <field>:Z`. For each hit, the very next
     * conditional instruction (`if-*`) is rewritten to `if-eqz vR, <originalTarget>` — keeping the
     * original branch label so the verifier remains happy. Mirrors the bash sed snippets that
     * neutralise per-field gating logic (e.g. `OnlineResourceDetail.bought:Z`).
     *
     * [fieldDefiningClass] / [fieldName] identify the field; pass `null` to match any class.
     */
    fun forceIfEqzAfterIgetBoolean(
        method: Method,
        fieldDefiningClass: String?,
        fieldName: String,
    ): Method? {
        val impl = method.implementation ?: return null
        val mut = MutableMethodImplementation(impl)
        var changed = false
        var i = 0
        while (i < mut.instructions.size - 1) {
            val ins = mut.instructions[i]
            if (ins.opcode == Opcode.IGET_BOOLEAN) {
                val ref = (ins as ReferenceInstruction).reference as FieldReference
                val match = ref.name == fieldName &&
                    (fieldDefiningClass == null || ref.definingClass == fieldDefiningClass)
                if (match) {
                    val reg = (ins as OneRegisterInstruction).registerA
                    // Search ahead a few instructions for the first conditional branch.
                    var j = i + 1
                    val cap = (i + 4).coerceAtMost(mut.instructions.size)
                    while (j < cap) {
                        val nxt = mut.instructions[j]
                        if (nxt is BuilderOffsetInstruction && nxt.opcode.name.startsWith("IF_")) {
                            val newCond = BuilderInstruction21t(Opcode.IF_EQZ, reg, nxt.target)
                            mut.replaceInstruction(j, newCond)
                            changed = true
                            break
                        }
                        j++
                    }
                }
            }
            i++
        }
        if (!changed) return null
        return rebuild(method, mut)
    }

    /**
     * Inserts `invoke-static {[argReg]}, [calleeOwner]->[calleeName]([calleeParam])V` immediately
     * before every `const-string vR, "[stringValue]"` in [method]. Translation of bash
     * `sed -i '/const-string v4, "is_verification_code"/i\    invoke-static …'`.
     */
    fun insertInvokeStaticBeforeStringConst(
        method: Method,
        stringValue: String,
        calleeOwner: String,
        calleeName: String,
        calleeParam: String,
        argReg: Int,
    ): Method? {
        val impl = method.implementation ?: return null
        val mut = MutableMethodImplementation(impl)
        val callee = ImmutableMethodReference(calleeOwner, calleeName, listOf(calleeParam), "V")
        var changed = false
        var i = 0
        while (i < mut.instructions.size) {
            val ins = mut.instructions[i]
            if ((ins.opcode == Opcode.CONST_STRING || ins.opcode == Opcode.CONST_STRING_JUMBO) &&
                ((ins as ReferenceInstruction).reference as StringReference).string == stringValue
            ) {
                val invoke = BuilderInstruction35c(
                    Opcode.INVOKE_STATIC, 1, argReg, 0, 0, 0, 0, callee,
                )
                mut.addInstruction(i, invoke)
                changed = true
                i += 2 // skip past invoke + const-string
                continue
            }
            i++
        }
        if (!changed) return null
        return rebuild(method, mut)
    }

    /**
     * Equivalent to a global `sed s/<oldName>/<newName>/g` over a smali file, but at the dex level
     * and bounded to a single [ClassDef]:
     *   - field DECLARATIONS named [oldName] are renamed.
     *   - `const-string` literals "[oldName]" are rewritten to "[newName]".
     *   - any field REFERENCE inside the class' method bodies whose name is [oldName] is
     *     rewritten to point at [newName] (same defining class + type).
     *
     * The corresponding `themeManagerPatch` rename of `DRM_ERROR_UNKNOWN` → `DRM_SUCCESS` relies
     * on all references to the field living inside the same package scope; we keep that
     * assumption (caller chooses which classes to apply the rename to).
     */
    fun renameIdentifier(cls: ClassDef, oldName: String, newName: String): ClassDef {
        var changed = false

        fun renameField(f: Field): Field = if (f.name != oldName) f else {
            changed = true
            ImmutableField(
                f.definingClass, newName, f.type, f.accessFlags,
                f.initialValue, f.annotations, f.hiddenApiRestrictions,
            )
        }

        val newStatic = cls.staticFields.map(::renameField)
        val newInstance = cls.instanceFields.map(::renameField)

        val newMethods = cls.methods.map { m ->
            val impl = m.implementation ?: return@map m
            val mut = MutableMethodImplementation(impl)
            var mChanged = false
            for (i in mut.instructions.indices) {
                val ins = mut.instructions[i]
                when {
                    // 1. const-string "oldName"
                    (ins.opcode == Opcode.CONST_STRING || ins.opcode == Opcode.CONST_STRING_JUMBO) &&
                        ((ins as ReferenceInstruction).reference as StringReference).string == oldName -> {
                        val reg = (ins as OneRegisterInstruction).registerA
                        mut.replaceInstruction(
                            i,
                            BuilderInstruction21c(
                                ins.opcode, reg, ImmutableStringReference(newName),
                            ),
                        )
                        mChanged = true
                    }
                    // 2. Field references (sget-*, sput-*, iget-*, iput-*) whose name matches.
                    ins is ReferenceInstruction && ins.reference is FieldReference -> {
                        val ref = ins.reference as FieldReference
                        if (ref.name == oldName) {
                            val newRef = ImmutableFieldReference(
                                ref.definingClass, newName, ref.type,
                            )
                            val newIns = when (ins) {
                                is TwoRegisterInstruction -> BuilderInstruction22c(
                                    ins.opcode,
                                    (ins as OneRegisterInstruction).registerA,
                                    ins.registerB,
                                    newRef,
                                )
                                else -> BuilderInstruction21c(
                                    ins.opcode,
                                    (ins as OneRegisterInstruction).registerA,
                                    newRef,
                                )
                            }
                            mut.replaceInstruction(i, newIns)
                            mChanged = true
                        }
                    }
                }
            }
            if (mChanged) { changed = true; rebuild(m, mut) } else m
        }

        if (!changed) return cls
        return ImmutableClassDef(
            cls.type, cls.accessFlags, cls.superclass, cls.interfaces, cls.sourceFile,
            cls.annotations, newStatic, newInstance, newMethods,
        )
    }


    // ============================================================================================
    // smali-style type helpers
    // ============================================================================================

    /** True if [type] is a class directly inside [pkg], e.g. type=`Lcom/android/server/pm/Foo;`,
     *  pkg=`Lcom/android/server/pm/`. Nested packages (`Lcom/android/server/pm/sub/Bar;`) return false. */
    fun isImmediateChildOf(type: String, pkg: String): Boolean {
        if (!type.startsWith(pkg) || !type.endsWith(";")) return false
        val tail = type.substring(pkg.length, type.length - 1)
        return tail.isNotEmpty() && '/' !in tail
    }

    /**
     * True if [type] is a class exactly [depth] directories below [pkg]. Models bash glob
     * `find pkg/*/*/ -name '*.smali'` (depth = 2 means two intermediate dirs).
     */
    fun isDeepChildOf(type: String, pkg: String, depth: Int): Boolean {
        if (!type.startsWith(pkg) || !type.endsWith(";")) return false
        val tail = type.substring(pkg.length, type.length - 1)
        return tail.count { it == '/' } == depth
    }

    /**
     * Within [method], rewrite every `sget-boolean vR, <field>` that targets [fieldOwner]::[fieldName]
     * into a `const(/4|/16|) vR, [value]`. Models the bash patch that pins
     * `miui.os.Build.IS_GLOBAL_BUILD` to `true` inside MiuiSettings.smali.
     */
    fun replaceSgetBooleanWithConst(
        method: Method,
        fieldOwner: String,
        fieldName: String,
        value: Int,
    ): Method? {
        val impl = method.implementation ?: return null
        val mut = MutableMethodImplementation(impl)
        var changed = false
        for (i in mut.instructions.indices) {
            val ins = mut.instructions[i]
            if (ins.opcode == Opcode.SGET_BOOLEAN && ins is ReferenceInstruction) {
                val ref = ins.reference as? FieldReference ?: continue
                if (ref.definingClass != fieldOwner || ref.name != fieldName) continue
                val reg = (ins as OneRegisterInstruction).registerA
                val newIns = if (reg < 16 && value in -8..7)
                    BuilderInstruction11n(Opcode.CONST_4, reg, value)
                else
                    BuilderInstruction31i(Opcode.CONST, reg, value)
                mut.replaceInstruction(i, newIns)
                changed = true
            }
        }
        if (!changed) return null
        return rebuild(method, mut)
    }

    /**
     * Append a new `public static final int <name> = <value>` field to [cls]. Used by
     * `settingsPatch` to register a new R.layout.my_device_info_item2 entry alongside the
     * existing R.layout.my_device_info_item.
     */
    fun addStaticIntField(cls: ClassDef, name: String, value: Int): ClassDef {
        // Skip if the field already exists (idempotent on re-runs).
        if (cls.staticFields.any { it.name == name }) return cls
        val accessFlags =
            com.android.tools.smali.dexlib2.AccessFlags.PUBLIC.value or
            com.android.tools.smali.dexlib2.AccessFlags.STATIC.value or
            com.android.tools.smali.dexlib2.AccessFlags.FINAL.value
        val initial = com.android.tools.smali.dexlib2.immutable.value.ImmutableIntEncodedValue(value)
        val newField = ImmutableField(
            cls.type, name, "I", accessFlags,
            initial, emptySet(), emptySet(),
        )
        val newStatic = cls.staticFields.toMutableList().also { it.add(newField) }
        return ImmutableClassDef(
            cls.type, cls.accessFlags, cls.superclass, cls.interfaces, cls.sourceFile,
            cls.annotations, newStatic, cls.instanceFields, cls.methods,
        )
    }

    /**
     * Within [method], find every `filled-new-array {v1, v2, v0}` (or whatever the [oldRegs]
     * register list is) and grow it to [newRegs]. The caller must have already prepended
     * `const(-4)` instructions that initialise the new registers.
     *
     * Mirrors the bash:
     *   sed -i 's/filled-new-array {v1, v2, v0}/filled-new-array {v1, v2, v0, v3, v4}/g'
     * combined with the const/4 inserts immediately before that line.
     */
    fun growFilledNewArray(
        method: Method,
        oldRegs: IntArray,
        newRegs: IntArray,
        extraInitValues: IntArray,
    ): Method? {
        val impl = method.implementation ?: return null
        val mut = MutableMethodImplementation(impl)
        var changed = false
        var i = 0
        while (i < mut.instructions.size) {
            val ins = mut.instructions[i]
            if (ins.opcode == Opcode.FILLED_NEW_ARRAY && ins is com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c) {
                val regs = IntArray(ins.registerCount) { idx ->
                    when (idx) {
                        0 -> ins.registerC; 1 -> ins.registerD; 2 -> ins.registerE
                        3 -> ins.registerF; 4 -> ins.registerG; else -> -1
                    }
                }
                if (regs.contentEquals(oldRegs) && newRegs.size <= 5) {
                    val newIns = com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction35c(
                        Opcode.FILLED_NEW_ARRAY,
                        newRegs.size,
                        newRegs.getOrElse(0) { 0 },
                        newRegs.getOrElse(1) { 0 },
                        newRegs.getOrElse(2) { 0 },
                        newRegs.getOrElse(3) { 0 },
                        newRegs.getOrElse(4) { 0 },
                        (ins as com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction).reference,
                    )
                    mut.replaceInstruction(i, newIns)
                    // Insert const/4 for the extra registers immediately before this instruction.
                    val extras = newRegs.drop(oldRegs.size)
                    extras.forEachIndexed { idx, reg ->
                        val value = extraInitValues.getOrElse(idx) { 0 }
                        val cins = BuilderInstruction11n(Opcode.CONST_4, reg, value)
                        mut.addInstruction(i, cins)
                    }
                    i += extras.size + 1
                    changed = true
                    continue
                }
            }
            i++
        }
        if (!changed) return null
        return rebuild(method, mut)
    }

    /**
     * Within [cls], find every `const-wide/16 v[registerA], [oldValue]` and rewrite the literal
     * to [newValue] (also as const-wide/16 — assumes both fit in 16-bit signed). Mirrors the
     * `miuiSystemUIPatch` constant tweak.
     */
    fun replaceConstWide16(
        cls: ClassDef,
        registerA: Int,
        oldValue: Int,
        newValue: Int,
    ): ClassDef = mapMethods(cls) { m ->
        val impl = m.implementation ?: return@mapMethods null
        val mut = MutableMethodImplementation(impl)
        var ch = false
        for (i in mut.instructions.indices) {
            val ins = mut.instructions[i]
            if (ins.opcode == Opcode.CONST_WIDE_16 &&
                (ins as OneRegisterInstruction).registerA == registerA &&
                (ins as NarrowLiteralInstruction).narrowLiteral == oldValue
            ) {
                mut.replaceInstruction(
                    i,
                    BuilderInstruction21s(Opcode.CONST_WIDE_16, registerA, newValue),
                )
                ch = true
            }
        }
        if (ch) rebuild(m, mut) else null
    }
}
