package com.pfa.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pfa.domain.FirewallType
import com.pfa.domain.ProxyMethod
import com.pfa.domain.ProxyMode
import com.pfa.viewmodel.MainViewModel

/**
 * 设置页：对应 box.config 的防火墙/路由/DNS/Fake-IP 等全局选项。
 */
@Composable
fun SettingsScreen(vm: MainViewModel) {
    val settings by vm.settings.collectAsState()
    val log by vm.log.collectAsState()
    val loaded by vm.loaded.collectAsState()

    if (!loaded) {
        Text("加载中...", modifier = Modifier.padding(16.dp))
        return
    }

    // 多行文本用独立本地状态，避免每次按键重置光标
    var pkgText by rememberSaveable { mutableStateOf(settings.packageList.joinToString("\n")) }
    var intranetText by rememberSaveable { mutableStateOf(settings.intranet.joinToString("\n")) }
    var intranet6Text by rememberSaveable { mutableStateOf(settings.intranet6.joinToString("\n")) }

    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("设置", style = MaterialTheme.typography.headlineSmall)

        // ---------- 防火墙 / 代理方式 ----------
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("防火墙与代理方式", style = MaterialTheme.typography.titleMedium)
                EnumDropdown("防火墙后端", settings.firewall, FirewallType.entries.toList(), { it.value }) {
                    vm.updateSettings(settings.copy(firewall = it))
                }
                EnumDropdown("TCP 代理方式", settings.tcpMethod, ProxyMethod.entries.toList(), { it.value }) {
                    vm.updateSettings(settings.copy(tcpMethod = it))
                }
                EnumDropdown("UDP 代理方式", settings.udpMethod, ProxyMethod.entries.toList(), { it.value }) {
                    vm.updateSettings(settings.copy(udpMethod = it))
                }
                SwitchRow("启用 IPv6", settings.ipv6Enabled) {
                    vm.updateSettings(settings.copy(ipv6Enabled = it))
                }
            }
        }

        // ---------- 端口 ----------
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("端口", style = MaterialTheme.typography.titleMedium)
                LabeledNumberField("TPROXY 端口", settings.tproxyPort) {
                    vm.updateSettings(settings.copy(tproxyPort = it))
                }
                LabeledNumberField("REDIR 端口", settings.redirPort) {
                    vm.updateSettings(settings.copy(redirPort = it))
                }
                LabeledNumberField("DNS 劫持端口", settings.dnsPort) {
                    vm.updateSettings(settings.copy(dnsPort = it))
                }
            }
        }

        // ---------- DNS 与 Fake-IP ----------
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("DNS 与 Fake-IP", style = MaterialTheme.typography.titleMedium)
                SwitchRow("DNS 劫持 (53 → DNS 端口)", settings.dnsHijackEnabled) {
                    vm.updateSettings(settings.copy(dnsHijackEnabled = it))
                }
                SwitchRow("IPv6 DNS 劫持 (dns_hijack_v6)", settings.dnsHijackV6Enabled) {
                    vm.updateSettings(settings.copy(dnsHijackV6Enabled = it))
                }
                SwitchRow("Fake-IP Ping 劫持 (本机应答)", settings.fakeIpPingHijack) {
                    vm.updateSettings(settings.copy(fakeIpPingHijack = it))
                }
                LabeledTextField("Fake-IP IPv4 段", settings.fakeIpRangeV4) {
                    vm.updateSettings(settings.copy(fakeIpRangeV4 = it))
                }
                LabeledTextField("Fake-IP IPv6 段", settings.fakeIpRangeV6) {
                    vm.updateSettings(settings.copy(fakeIpRangeV6 = it))
                }
                LabeledTextField("Fake-IP6 Dummy 网卡 (≤15 字符)", settings.dummyDevice) {
                    vm.updateSettings(settings.copy(dummyDevice = it))
                }
            }
        }

        // ---------- 内网网段排除 ----------
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("内网网段排除 (intranet)", style = MaterialTheme.typography.titleMedium)
                Text(
                    "每行一个 CIDR；fake-ip 段运行时自动剔除，不写入排除集合。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                LabeledTextField(
                    label = "IPv4 网段 (intranet)",
                    value = intranetText,
                    singleLine = false
                ) {
                    intranetText = it
                    vm.updateIntranetText(it)
                }
                LabeledTextField(
                    label = "IPv6 网段 (intranet6)",
                    value = intranet6Text,
                    singleLine = false
                ) {
                    intranet6Text = it
                    vm.updateIntranet6Text(it)
                }
            }
        }

        // ---------- 模式 ----------
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("代理模式", style = MaterialTheme.typography.titleMedium)
                EnumDropdown("模式", settings.proxyMode, ProxyMode.entries.toList(), { it.value }) {
                    vm.updateSettings(settings.copy(proxyMode = it))
                }
                Text(
                    "包名列表（每行一个，黑白名单模式生效，MVP 暂未接入 uid 匹配）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                LabeledTextField(
                    label = "包名列表",
                    value = pkgText,
                    singleLine = false
                ) {
                    pkgText = it
                    vm.updatePackageListText(it)
                }
            }
        }

        // ---------- 内核标记 / 路由表 ----------
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("标记与路由表", style = MaterialTheme.typography.titleMedium)
                LabeledTextField("TPROXY fwmark", settings.markId) {
                    vm.updateSettings(settings.copy(markId = it))
                }
                LabeledTextField("TPROXY 路由表号", settings.tableId) {
                    vm.updateSettings(settings.copy(tableId = it))
                }
                LabeledTextField("TUN fwmark", settings.tunMarkId) {
                    vm.updateSettings(settings.copy(tunMarkId = it))
                }
                LabeledTextField("TUN 路由表号", settings.tunTableId) {
                    vm.updateSettings(settings.copy(tunTableId = it))
                }
                LabeledTextField("TUN 设备名", settings.tunDevice) {
                    vm.updateSettings(settings.copy(tunDevice = it))
                }
                SwitchRow("启动前 sing-box 配置检查", settings.startCheck) {
                    vm.updateSettings(settings.copy(startCheck = it))
                }
            }
        }

        // ---------- 一键还原 ----------
        Button(onClick = { vm.reset() }, modifier = Modifier.fillMaxWidth()) {
            Text("一键还原（清空路由/防火墙/核心进程）")
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Text(
                log.ifBlank { "（暂无日志）" },
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp)
            )
        }
    }
}
