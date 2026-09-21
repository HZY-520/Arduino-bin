package com.arduinobin.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.arduinobin.data.BoardPreset
import com.arduinobin.data.BuildStatus
import com.arduinobin.data.BuildUiState
import com.arduinobin.data.DEFAULT_BOARDS
import com.arduinobin.vm.MainViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel = viewModel()) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    val state by viewModel.buildState.collectAsState()

    var zipUri by remember { mutableStateOf<Uri?>(null) }
    var zipName by remember { mutableStateOf<String?>(null) }
    var projectName by remember { mutableStateOf("") }
    var boardId by remember { mutableStateOf(DEFAULT_BOARDS.first().id) }
    var libsText by remember { mutableStateOf("") }

    // 通知权限（Android 13+）
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) {
            permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val zipPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            zipUri = uri
            zipName = queryDisplayName(context, uri)
            projectName = zipName?.removeSuffix(".zip")?.let { sanitizeProjectName(it) } ?: projectName
        }
    }

    val exportPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            val copied = viewModel.exportArtifacts(context, uri, state.artifacts)
            scope.launch {
                snackbar.showSnackbar("已导出 $copied 个产物文件。")
            }
        }
    }

    val libZipPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val result = viewModel.installLibraryFromZip(context, uri)
                snackbar.showSnackbar(result.message)
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            // 配置区
            Column(
                Modifier
                    .weight(0.55f)
                    .verticalScroll(rememberScrollState())
            ) {
                HeaderCard()
                Spacer(Modifier.height(12.dp))
                UploadCard(zipName, zipUri != null, onPick = { zipPicker.launch("application/zip") })
                Spacer(Modifier.height(12.dp))
                BoardSelector(boardId, onSelect = { boardId = it })
                Spacer(Modifier.height(12.dp))
                LibInput(libsText, onChange = { libsText = it }, onPickLibZip = { libZipPicker.launch("application/zip") })
                Spacer(Modifier.height(12.dp))
                BuildButton(
                    enabled = zipUri != null && state.status != BuildStatus.PREPARING &&
                        state.status != BuildStatus.DOWNLOADING && state.status != BuildStatus.COMPILING,
                    onClick = {
                        val libs = libsText.split(',', ' ', '\n')
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                        viewModel.startBuild(
                            context,
                            zipUri!!,
                            boardId,
                            projectName.ifBlank { "sketch" },
                            libs,
                        )
                    },
                )
                Spacer(Modifier.height(8.dp))
            }

            Spacer(Modifier.height(8.dp))
            LogPanel(state, Modifier.weight(0.45f), onExport = {
                exportPicker.launch(null)
            })
        }
    }
}

private fun sanitizeProjectName(name: String): String =
    name.replace(Regex("[^A-Za-z0-9._-]"), "_").trim('_').ifBlank { "sketch" }

private fun queryDisplayName(context: Context, uri: Uri): String? {
    return runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
        }
    }.getOrNull()
}

@Composable
private fun HeaderCard() {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(20.dp)) {
            Text("Arduino Builder", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(4.dp))
            Text(
                "在设备本地用 arduino-cli 将 Arduino 项目源码构建为二进制（bin/hex），支持自定义库与开发板。",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.Gray,
            )
        }
    }
}

@Composable
private fun UploadCard(name: String?, ready: Boolean, onPick: () -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text("项目源码", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onPick, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.UploadFile, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (ready) "已选择: $name" else "上传 Arduino 项目 zip")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BoardSelector(selected: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selectedBoard = DEFAULT_BOARDS.first { it.id == selected }
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text("目标开发板", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                OutlinedTextField(
                    value = selectedBoard.name,
                    onValueChange = {},
                    readOnly = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                    modifier = Modifier.menuAnchor().fillMaxWidth(),
                )
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    DEFAULT_BOARDS.forEach { board ->
                        DropdownMenuItem(
                            text = { Text("${board.name} — ${board.architecture}") },
                            onClick = {
                                onSelect(board.id)
                                expanded = false
                            },
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text("FQBN: ${selectedBoard.fqbn}", fontSize = 12.sp, color = Color.Gray)
        }
    }
}

@Composable
private fun LibInput(value: String, onChange: (String) -> Unit, onPickLibZip: () -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text("自定义依赖库（可选）", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = value,
                onValueChange = onChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("例如 WiFi, Adafruit_GFX（逗号或空格分隔）") },
                minLines = 1,
                maxLines = 3,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onPickLibZip, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.UploadFile, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("从本机 zip 安装库")
            }
        }
    }
}

@Composable
private fun BuildButton(enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
        shape = RoundedCornerShape(14.dp),
    ) {
        Icon(Icons.Default.Build, null)
        Spacer(Modifier.width(8.dp))
        Text("开始构建", fontSize = 16.sp)
    }
}

@Composable
private fun LogPanel(state: BuildUiState, modifier: Modifier = Modifier, onExport: () -> Unit) {
    val listState = rememberLazyListState()
    LaunchedEffect(state.log.size) {
        if (state.log.size > 1) listState.animateScrollToItem(state.log.size - 1)
    }
    Card(modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxSize().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("构建日志", style = MaterialTheme.typography.titleMedium)
                    if (state.progressLabel.isNotEmpty()) {
                        Text(state.progressLabel, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                    }
                }
                if (state.status == BuildStatus.PREPARING || state.status == BuildStatus.DOWNLOADING || state.status == BuildStatus.COMPILING) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                }
            }
            Spacer(Modifier.height(6.dp))
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
            ) {
                items(state.log) { line ->
                    Text(
                        line,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = lineColor(line),
                        modifier = Modifier.padding(vertical = 1.dp),
                    )
                }
            }
            if (state.status == BuildStatus.SUCCESS && state.artifacts.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = onExport, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Folder, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("导出产物到指定位置")
                }
            }
        }
    }
}

private fun lineColor(line: String): Color = when {
    line.contains("error", ignoreCase = true) || line.contains("错误", ignoreCase = true) ||
        line.contains("failed", ignoreCase = true) -> Color(0xFFC62828)
    line.contains("warning", ignoreCase = true) -> Color(0xFFF9A825)
    else -> Color(0xFF37474F)
}