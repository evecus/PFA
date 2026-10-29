package com.pfa.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.pfa.domain.ProxyPaths
import com.pfa.viewmodel.FileType
import com.pfa.viewmodel.MainViewModel

/**
 * 管理页：上传代理核心 / 上传配置文件 / 编辑配置文件 / 删除文件。
 */
@Composable
fun ManageScreen(vm: MainViewModel) {
    val bins by vm.bins.collectAsState()
    val configFiles by vm.configFiles.collectAsState()

    var editTarget by remember { mutableStateOf<String?>(null) }
    var editContent by remember { mutableStateOf("") }

    val corePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { vm.importFile(it, FileType.CORE) }
    }
    val configPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { vm.importFile(it, FileType.CONFIG) }
    }

    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("文件管理", style = MaterialTheme.typography.headlineSmall)

        // ---------- 上传 ----------
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { corePicker.launch("*/*") }) { Text("上传核心") }
            Button(onClick = { configPicker.launch("*/*") }) { Text("上传配置") }
        }

        // ---------- 核心 ----------
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("代理核心 (${ProxyPaths.BIN_DIR})", style = MaterialTheme.typography.titleMedium)
                Text(
                    "上传后自动赋予执行权限，供首页选择。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (bins.isEmpty()) {
                    Text("（空）", style = MaterialTheme.typography.bodySmall)
                } else {
                    bins.forEach { name ->
                        FileRow(
                            name = name,
                            onEdit = null,
                            onDelete = { vm.deleteFile(ProxyPaths.BIN_DIR, name) }
                        )
                    }
                }
            }
        }

        // ---------- 配置文件 ----------
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("配置文件 (${ProxyPaths.PROFILES_DIR})", style = MaterialTheme.typography.titleMedium)
                Text(
                    "启动时会把选中的配置复制到 ${ProxyPaths.RUN_DIR}。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (configFiles.isEmpty()) {
                    Text("（空）", style = MaterialTheme.typography.bodySmall)
                } else {
                    configFiles.forEach { name ->
                        FileRow(
                            name = name,
                            onEdit = {
                                vm.loadConfig(name) { content ->
                                    editContent = content
                                    editTarget = name
                                }
                            },
                            onDelete = { vm.deleteFile(ProxyPaths.PROFILES_DIR, name) }
                        )
                    }
                }
            }
        }
    }

    // ---------- 配置编辑对话框 ----------
    editTarget?.let { name ->
        AlertDialog(
            onDismissRequest = { editTarget = null },
            title = { Text("编辑 $name") },
            text = {
                OutlinedTextField(
                    value = editContent,
                    onValueChange = { editContent = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 360.dp),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                )
            },
            confirmButton = {
                Button(onClick = {
                    vm.saveConfig(name, editContent) { editTarget = null }
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { editTarget = null }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun FileRow(
    name: String,
    onEdit: (() -> Unit)?,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            name,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium
        )
        if (onEdit != null) {
            TextButton(onClick = onEdit) { Text("编辑") }
        }
        TextButton(onClick = onDelete) { Text("删除") }
    }
}
