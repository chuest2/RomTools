package com.chuest.romtools

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chuest.romtools.core.AssetCopy
import com.chuest.romtools.core.Logger
import com.chuest.romtools.core.Plugins
import com.chuest.romtools.core.pipeline.PipelinePlugin
import com.chuest.romtools.core.pipeline.PluginRegistry
import com.chuest.romtools.service.RomBuildService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.runningFold
import java.io.File

private const val PREFS = "rom-tools"
private const val K_PLUGINS = "enabled_plugins"
private const val K_SUPERKEY = "apatch_superkey"

class MainActivity : ComponentActivity() {
    private val pickedRom = MutableStateFlow<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Thread { AssetCopy.ensure(applicationContext) }.start()

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        setContent { MaterialTheme { Surface { MainScreen(pickedRom) } } }
    }
}

private fun loadEnabled(ctx: Context): Set<String> {
    val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    return sp.getStringSet(K_PLUGINS, null) ?: Plugins.DEFAULT_ENABLED
}

private fun saveEnabled(ctx: Context, set: Set<String>) {
    ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit().putStringSet(K_PLUGINS, set).apply()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(pickedRom: MutableStateFlow<Uri?>) {
    val ctx = LocalContext.current
    val romUri by pickedRom.collectAsStateWithLifecycle()
    val workRoot = remember {
        File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "rt-work").also { it.mkdirs() }
    }

    val enabled = remember {
        mutableStateMapOf<String, Boolean>().also { m ->
            val s = loadEnabled(ctx); PluginRegistry.all.forEach { m[it.id] = it.id in s }
        }
    }
    var superKey by rememberSaveable {
        mutableStateOf(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(K_SUPERKEY, "") ?: "")
    }

    fun persist() {
        saveEnabled(ctx, enabled.filter { it.value }.keys)
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(K_SUPERKEY, superKey).apply()
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            pickedRom.value = uri
            Logger.n("Selected ROM: $uri")
        }
    }

    val logs by Logger.flow.runningFold(emptyList<Logger.Line>()) { acc, line ->
        (acc + line).takeLast(2000)
    }.collectAsStateWithLifecycle(initialValue = emptyList())

    val listState = rememberLazyListState()
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) listState.animateScrollToItem(logs.lastIndex)
    }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text("HyperOS Rom Modify", style = MaterialTheme.typography.headlineSmall)

        Spacer(Modifier.height(8.dp))
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("ROM zip: ${romUri ?: "(未选择)"}", maxLines = 2, fontSize = 12.sp)
                Spacer(Modifier.height(4.dp))
                Text("Work dir: ${workRoot.absolutePath}", fontSize = 11.sp)
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { picker.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }) {
                Text("选择 ROM zip")
            }
            Button(
                enabled = romUri != null && !RomBuildService.running,
                onClick = {
                    val u = romUri ?: return@Button
                    persist()
                    val enabledIds = enabled.filter { it.value }.keys
                    val sk = superKey.takeIf { it.isNotBlank() && Plugins.APATCH in enabledIds }
                    if (Plugins.APATCH in enabledIds && sk == null) {
                        Logger.e("APatch 已勾选但 SUPERKEY 为空")
                        return@Button
                    }
                    if (Plugins.KERNELSU in enabledIds && Plugins.APATCH in enabledIds) {
                        Logger.e("KernelSU 与 APatch 互斥，请只勾选其中一个")
                        return@Button
                    }
                    RomBuildService.start(ctx, u, workRoot, enabledIds, sk)
                }
            ) { Text(if (RomBuildService.running) "运行中…" else "开始打包") }
            OutlinedButton(onClick = {
                PluginRegistry.all.forEach { enabled[it.id] = it.defaultOn }
                superKey = ""; persist()
            }) { Text("重置") }
        }

        Spacer(Modifier.height(12.dp))
        Text("功能插件（按勾选执行）", style = MaterialTheme.typography.titleMedium)
        Divider()
        Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
            for (p in PluginRegistry.all) {
                PluginRow(p, enabled[p.id] == true) { v ->
                    enabled[p.id] = v
                    if (p.id == Plugins.KERNELSU && v) enabled[Plugins.APATCH] = false
                    if (p.id == Plugins.APATCH   && v) enabled[Plugins.KERNELSU] = false
                    persist()
                }
            }
            if (enabled[Plugins.APATCH] == true) {
                OutlinedTextField(
                    value = superKey,
                    onValueChange = { superKey = it },
                    label = { Text("APatch SUPERKEY") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth().padding(start = 36.dp, end = 4.dp, top = 4.dp)
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Text("日志", style = MaterialTheme.typography.titleMedium)
        Divider()
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            items(logs) { line ->
                val color = when (line.level) {
                    Logger.Level.ERROR  -> Color(0xFFB00020)
                    Logger.Level.WARN   -> Color(0xFFCC6600)
                    Logger.Level.NOTICE -> Color(0xFF1565C0)
                    Logger.Level.INFO   -> Color.Unspecified
                }
                Text(line.render(), color = color, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
private fun PluginRow(p: PipelinePlugin, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
    ) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Column(Modifier.padding(start = 4.dp, top = 10.dp)) {
            Text(p.title, style = MaterialTheme.typography.bodyLarge)
            Text(p.description, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        }
    }
}
