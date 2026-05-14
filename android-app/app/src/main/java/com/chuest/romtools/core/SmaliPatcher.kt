package com.chuest.romtools.core

import java.io.File

/**
 * Kotlin replacement for the dozens of `sed` calls in start.sh. All operations work on text files
 * in-place using regex/line manipulation. Methods are named after the bash pattern they replace.
 */
object SmaliPatcher {

    // ---------- generic primitives ----------

    /** sed -i "s/<from>/<to>/g" on a file (literal flags). */
    fun replaceLiteral(file: File, from: String, to: String) {
        if (!file.isFile) return
        val txt = file.readText()
        val out = txt.replace(from, to)
        if (out != txt) file.writeText(out)
    }

    /** sed -i 's/<regex>/<replacement>/g' */
    fun replaceRegex(file: File, regex: Regex, replacement: String) {
        if (!file.isFile) return
        val txt = file.readText()
        val out = regex.replace(txt, replacement)
        if (out != txt) file.writeText(out)
    }

    /** Drop every line in [start..end] inclusive (1-based, both ends), then insert [insertAt(start)] body. */
    fun replaceLineRange(file: File, startLine: Int, endLine: Int, replacement: String) {
        val lines = file.readLines().toMutableList()
        require(startLine in 1..lines.size && endLine in startLine..lines.size) { "bad range $startLine..$endLine for ${file.name}" }
        repeat(endLine - startLine + 1) { lines.removeAt(startLine - 1) }
        lines.add(startLine - 1, replacement)
        file.writeText(lines.joinToString("\n", postfix = "\n"))
    }

    /** sed -i '<lineNum>i\<text>' — insert before lineNum (1-based). */
    fun insertBefore(file: File, lineNum: Int, text: String) {
        val lines = file.readLines().toMutableList()
        require(lineNum in 1..lines.size + 1) { "bad insert line $lineNum for ${file.name}" }
        lines.add(lineNum - 1, text)
        file.writeText(lines.joinToString("\n", postfix = "\n"))
    }

    fun findLine(file: File, needle: String): Int {
        file.useLines { seq ->
            seq.forEachIndexed { idx, line -> if (line.contains(needle)) return idx + 1 }
        }
        return -1
    }

    /**
     * Mirror of bash:
     *   sed -i '/^.method <sig>/,/^.end method/{//!d}'
     *   sed -i -e '/^.method <sig>/a\<body>' $f
     * Replaces a method body in a smali file, keeping the .method / .end method markers and inserting [body] right after.
     */
    fun replaceMethodBody(file: File, methodStartRegex: Regex, body: String) {
        val lines = file.readLines()
        val out = StringBuilder()
        var i = 0
        var changed = false
        while (i < lines.size) {
            val line = lines[i]
            out.appendLine(line)
            if (methodStartRegex.containsMatchIn(line)) {
                // skip until ".end method"
                i++
                while (i < lines.size && !lines[i].startsWith(".end method")) i++
                // emit body
                out.appendLine(body)
                if (i < lines.size) out.appendLine(lines[i]) // .end method
                changed = true
            }
            i++
        }
        if (changed) file.writeText(out.toString())
    }

    /** Find all `.smali` files under [root] that match [pathFilter] and whose contents match [grep]. */
    fun findSmali(root: File, pathFilter: (File) -> Boolean, grep: Regex): List<File> {
        if (!root.isDirectory) return emptyList()
        val acc = ArrayList<File>()
        root.walkTopDown().forEach { f ->
            if (f.isFile && f.name.endsWith(".smali") && pathFilter(f)) {
                runCatching { if (grep.containsMatchIn(f.readText())) acc += f }
            }
        }
        return acc
    }
}
