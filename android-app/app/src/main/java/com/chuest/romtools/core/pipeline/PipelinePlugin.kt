package com.chuest.romtools.core.pipeline

/**
 * A toggleable step in the ROM build pipeline. Each plugin owns one self-contained patch
 * (one APK, one jar, one prop file, one boot image, …) and accesses shared state via
 * [PipelineContext]. The mandatory pre/post phases (payload dump, erofs (un)pack, super.img,
 * vbmeta replacement, cust replacement, final cleanup) are not plugins — they live directly in
 * `RomPipeline.run()` because they have no meaningful off state.
 *
 * Implementations are stateless `object`s so they can be registered statically and shown in the UI.
 */
interface PipelinePlugin {
    /** Stable identifier persisted in SharedPreferences. Must match a constant in `Plugins`. */
    val id: String

    /** Short log-line identifier (the legacy bash function name). */
    val label: String

    /** Human-readable name shown in the plugin list. */
    val title: String

    /** Multi-line description shown under the title. */
    val description: String

    /** Whether this plugin is ticked by default when the user has no saved preference. */
    val defaultOn: Boolean get() = true

    fun run(ctx: PipelineContext)
}
