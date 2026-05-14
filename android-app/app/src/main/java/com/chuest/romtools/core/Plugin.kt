package com.chuest.romtools.core

import com.chuest.romtools.core.pipeline.PluginRegistry

/**
 * Stable plugin IDs persisted in SharedPreferences. Each plugin `object` under
 * `core/pipeline/plugins/` references one of these constants as its [com.chuest.romtools.core.pipeline.PipelinePlugin.id].
 *
 * UI metadata (title / description / defaultOn) lives on the plugin objects themselves —
 * iterate [PluginRegistry.all] to render the list.
 */
object Plugins {
    // ---- partition / boot layer ----
    const val REMOVE_AVB              = "remove_avb"
    const val REPLACE_APKS            = "replace_apks"
    const val REMOVE_FILES            = "remove_files"
    const val MODIFY_PROPS            = "modify_props"
    const val KERNELSU                = "kernelsu"
    const val APATCH                  = "apatch"

    // ---- framework / smali ----
    const val REMOVE_SIGN_VERIFY      = "remove_sign_verify"
    const val PREVENT_THEME_RECOVERY  = "prevent_theme_recovery"

    // ---- apk patches ----
    const val THEME_MANAGER           = "theme_manager"
    const val PERSONAL_ASSISTANT      = "personal_assistant"
    const val MMS_VCODE_AUTOCOPY      = "mms_vcode_autocopy"
    const val POWER_KEEPER            = "power_keeper"
    const val SETTINGS                = "settings"
    const val MIUI_SYSTEMUI           = "miui_systemui"

    /** Default-ticked plugin IDs (derived from each plugin's `defaultOn`). */
    val DEFAULT_ENABLED: Set<String>
        get() = PluginRegistry.all.filter { it.defaultOn }.map { it.id }.toSet()
}
