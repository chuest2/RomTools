package com.chuest.romtools.core

import com.reandroid.apk.ApkModule
import com.reandroid.apk.xmlencoder.XMLEncodeSource
import com.reandroid.archive.ByteInputSource
import com.reandroid.arsc.chunk.PackageBlock
import com.reandroid.arsc.chunk.TableBlock
import com.reandroid.arsc.value.Entry
import com.reandroid.arsc.value.ResConfig
import com.reandroid.arsc.value.ValueType
import com.reandroid.xml.source.XMLFileSource
import java.io.File

/**
 * Binary-resource patcher built on ARSCLib. Replaces the APKEditor d/b round-trip for
 * `settingsPatch`, the only patch that needs to mutate AXML / arsc on top of dex code.
 *
 * Lifecycle:
 *   val apk = ArscPatcher.load(srcApk, framework)  // optional framework-res.apk
 *   ArscPatcher.replaceXml(apk, "res/layout/foo.xml", rawXmlFile)
 *   val newId = ArscPatcher.addLayoutEntry(apk, "my_device_info_item2", "res/layout/foo.xml")
 *   ArscPatcher.appendStringArrayItems(apk, "notification_icon_counts_values", listOf("5","7"))
 *   apk.writeApk(dstApk)
 */
object ArscPatcher {

    /** Load an apk and (optionally) link a framework-res.apk so `@android:...` refs resolve. */
    fun load(apkFile: File, frameworkRes: File? = null): ApkModule {
        val module = ApkModule.loadApkFile(apkFile)
        if (frameworkRes != null && frameworkRes.isFile) {
            module.addExternalFramework(frameworkRes)
        }
        return module
    }

    /** First (and usually only) PackageBlock in the apk's resource table. */
    fun primaryPackage(apk: ApkModule): PackageBlock {
        val table: TableBlock = apk.tableBlock
        return table.pickOne() ?: error("apk has no resource package")
    }

    /**
     * Replace (or add) `path` with the AXML-encoded form of [rawXmlFile]. Uses ARSCLib's lazy
     * [XMLEncodeSource]: encoding only happens when [ApkModule.writeApk] runs, so this is cheap
     * to call repeatedly.
     */
    fun replaceXml(apk: ApkModule, path: String, rawXmlFile: File) {
        val pkg = primaryPackage(apk)
        val parser = XMLFileSource(path, rawXmlFile)
        val source = XMLEncodeSource(pkg, parser)
        apk.add(source)
    }

    /**
     * Register a new resource entry `<type>/<name>` whose value is a STRING pointing at [path]
     * (the layout file you just injected via [replaceXml]). Returns the freshly assigned full
     * resource id.
     *
     * Equivalent to bash:
     *   <public id="0xNN" type="layout" name="my_device_info_item2" />
     */
    fun addFileEntry(apk: ApkModule, typeName: String, entryName: String, path: String): Int {
        val pkg = primaryPackage(apk)
        // getOrCreate creates the spec/type entry slot if missing; for new layouts under the
        // existing `layout` type it just appends an entry and ARSCLib picks the next id.
        val entry: Entry = pkg.getOrCreate(ResConfig.getDefault(), typeName, entryName)
            ?: error("getOrCreate returned null for $typeName/$entryName")
        // STRING value pointing at the file path inside the apk. ARSCLib will lay this out into
        // the table's string pool when writing.
        entry.setValueAsString(path)
        return entry.resourceId
    }

    /**
     * Append [items] to an existing `<string-array name="...">`. Each item is added as a STRING
     * value (the bash uses `@string/...` references; you can pass `"@string/foo"` strings and
     * ARSCLib will keep them as plain strings — the runtime resolves them at lookup time the
     * same way aapt-encoded values do).
     *
     * NOTE: this addresses the simple "append" case. The bash also has a more invasive sed that
     * inserts two duplicate `<item>@string/display_notification_icon_3</item>` lines in the
     * middle of the SAME array — using the existing tail item's resource ref. We model that by
     * letting callers pass the literal reference strings.
     */
    fun appendStringArrayItems(apk: ApkModule, arrayName: String, items: List<String>) {
        val pkg = primaryPackage(apk)
        val entry = pkg.getEntry(ResConfig.getDefault(), "array", arrayName)
            ?: error("string-array '$arrayName' not found")
        val bag = entry.resValueMapArray ?: error("array '$arrayName' is not a bag entry")
        val nextKey = (bag.size() + 1) // simple sequential keying; matches array order
        items.forEachIndexed { i, value ->
            val mapItem = bag.createNext()
            mapItem.setName(0x01000000 or (nextKey + i)) // array item id form: high byte 0x01
            mapItem.setValueAsString(value)
        }
    }

    /**
     * Inject a raw file (no AXML encoding) at [path]. Useful for resource artifacts that are
     * already in their final binary form.
     */
    fun replaceRaw(apk: ApkModule, path: String, bytes: ByteArray) {
        apk.add(ByteInputSource(bytes, path))
    }

    fun writeApk(apk: ApkModule, dst: File) {
        apk.writeApk(dst)
    }
}
