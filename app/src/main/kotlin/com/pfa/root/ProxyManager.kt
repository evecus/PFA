package com.pfa.root

import android.content.Context
import com.pfa.domain.CoreType
import com.pfa.domain.FirewallType
import com.pfa.domain.Profile
import com.pfa.domain.ProxyMethod
import com.pfa.domain.ProxyMode
import com.pfa.domain.ProxyPaths
import com.pfa.domain.ProxySettings
import kotlinx.coroutines.delay
import java.io.File

/**
 * 代理核心管理：启动 / 停止 / 状态 / 还原。
 *
 * 工作目录体系：
 *  - 核心：/data/adb/proxy/bin/<binaryName>
 *  - 配置：/data/adb/proxy/profiles/<configFile>（上传后存放）
 *  - 运行：/data/adb/proxy/run/<configFile>（启动前复制进来），pid 文件也在 run/
 *
 * 防火墙/路由逻辑严格对照参考模块 box4（box.tproxy + box.nft）：
 *  - mangle_pre  (prerouting, -150)：socket DIVERT、iif tun/lo 防回环 return、DNS skip、intranet return、TPROXY/打标
 *  - mangle_out  (output, route, -150)：核心 owner bypass（防回环）、DNS skip、intranet return、黑白名单、打标
 *  - filter_out  (output, 0)：tproxy 时 reject 核心连 127.0.0.1/::1:tproxy_port（防回环）
 *  - filter_in/fwd (input/forward, -1)：仅 tun 方式时放行 tun 网卡
 *  - nat_pre     (prerouting, -100)：fake-ip ping 劫持、DNS 劫持、redir（仅 iif lo + intranet return）
 *  - nat_out     (output, -100)：ping 劫持、DNS 劫持（核心 owner return 防回环）、redir（owner return + intranet + 名单）
 *  - 全部规则位于独立 table ip/ip6 box，清理 = delete table；先 add+delete 原子替换旧表
 *  - 能力探测（tproxy/socket/nat），缺 tproxy 时 tcp 降级 redir、udp 降级 null（对齐 box4）
 */
class ProxyManager(private val context: Context) {

    private val nftFilePath = File(context.filesDir, "box.nft").absolutePath

    fun isRootAvailable(): Boolean = RootShell.isRootAvailable()

    // ===================== 启动 =====================
    suspend fun start(profile: Profile, settings: ProxySettings): StartResult {
        // 1. 校验核心二进制
        val binPath = "${ProxyPaths.BIN_DIR}/${profile.bin}"
        val binCheck = RootShell.runScript(
            "[ -x '$binPath' ] && echo OK || { [ -f '$binPath' ] && echo NOEXEC || echo MISSING; }"
        )
        when {
            "MISSING" in binCheck.stdout ->
                return StartResult(false, "核心未找到：$binPath\n请先在「管理」页上传对应核心二进制")
            "NOEXEC" in binCheck.stdout -> {
                RootShell.runScript("chmod 755 '$binPath'")
                val re = RootShell.runScript("[ -x '$binPath' ] && echo OK || echo FAIL")
                if ("OK" !in re.stdout)
                    return StartResult(false, "核心无执行权限且自动 chmod 失败：$binPath")
            }
            "OK" !in binCheck.stdout ->
                return StartResult(false, "核心检测失败：$binPath\n${binCheck.stderr}")
        }

        // 2. 校验并复制配置文件到运行目录
        if (profile.configFile.isBlank())
            return StartResult(false, "未选择配置文件，请先在首页选择或到「管理」页上传")

        val srcConfig = "${ProxyPaths.PROFILES_DIR}/${profile.configFile}"
        val runConfig = "${ProxyPaths.RUN_DIR}/${profile.configFile}"
        val cp = RootShell.runScript(
            "mkdir -p '${ProxyPaths.RUN_DIR}' && cp -f '$srcConfig' '$runConfig' && echo CP_OK"
        )
        if (!cp.ok || "CP_OK" !in cp.stdout)
            return StartResult(
                false,
                "配置文件复制到运行目录失败:\n$srcConfig -> $runConfig\n${cp.stderr}"
            )

        // 3. 启动前配置检查（仅 sing-box 且使用默认参数时；自定义参数场景不做预检）
        if (settings.startCheck && profile.core == CoreType.SING_BOX && profile.extraArgs.isBlank()) {
            val chk = RootShell.runScript("'$binPath' check -c '$runConfig' 2>&1")
            val out = (chk.stdout + "\n" + chk.stderr).trim()
            if (!chk.ok || out.contains("error", ignoreCase = true))
                return StartResult(false, "sing-box 配置检查未通过:\n$out")
        }

        // 4. 启动核心
        RootShell.runScript(buildLaunchCommand(profile))

        // 5. 等待进程并取 PID
        val pid = waitForPid(profile.bin)
        if (pid == null) {
            return StartResult(false, "核心启动失败，请检查配置文件与运行参数（可尝试关闭启动前检查）")
        }

        // 6. 配置策略路由
        val route = applyRouting(settings)
        if (!route.ok) {
            return StartResult(false, "策略路由配置失败:\n${route.stderr}", pid)
        }

        // 7. 防火墙（nftables；规则生成需要核心 PID 的 uid/gid，故在核心启动之后）
        if (settings.firewall == FirewallType.NFTABLES) {
            val fwErr = applyNftables(settings, pid)
            if (fwErr != null) return StartResult(false, fwErr, pid)
        } else {
            // TODO(MVP): iptables + ipset 回退路径，后续实现
        }

        return StartResult(
            true,
            "已启动 ${profile.bin} (PID $pid)\n配置: $runConfig\n防火墙与策略路由已配置",
            pid
        )
    }

    /**
     * 拼接启动命令行。
     * 运行参数（profile.extraArgs）语义：完整的核心启动参数。
     *  - 非空：原样使用，最终命令 = /data/adb/proxy/bin/<核心名> <运行参数>
     *    例：填 "-d /data/adb/proxy/run" → /data/adb/proxy/bin/mihomo -d /data/adb/proxy/run
     *  - 留空：按核心类型生成默认参数（配置文件取首页选中的，已复制到 run/）
     */
    private fun buildLaunchCommand(profile: Profile): String {
        val bin = "${ProxyPaths.BIN_DIR}/${profile.bin}"
        val conf = "${ProxyPaths.RUN_DIR}/${profile.configFile}"
        val args = profile.extraArgs.trim()
        val coreArgs = args.ifBlank {
            when (profile.core) {
                CoreType.SING_BOX -> "run -c '$conf'"
                CoreType.MIHOMO, CoreType.CLASH -> "-d '${ProxyPaths.RUN_DIR}' -f '$conf'"
                CoreType.HYSTERIA, CoreType.ANT -> "-c '$conf'"
            }
        }
        val pidFile = "${ProxyPaths.RUN_DIR}/${profile.bin}.pid"
        // setsid 让核心脱离 shell 会话常驻；输出丢弃（MVP 不采集核心日志）
        return "setsid '$bin' $coreArgs >/dev/null 2>&1 </dev/null & echo \$! > '$pidFile'"
    }

    /** 轮询 pidof 取 PID（对齐 box.service wait_bin_listen 思路） */
    private suspend fun waitForPid(binaryName: String, timeoutMs: Long = 5000): Int? {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            val r = RootShell.runScript("pidof '$binaryName' | head -n1")
            val pid = r.stdout.trim().toIntOrNull()
            if (pid != null && pid > 0) return pid
            delay(300)
        }
        // 退路：读 pid 文件
        val pf = RootShell.runScript("cat '${ProxyPaths.RUN_DIR}/$binaryName.pid' 2>/dev/null").stdout.trim()
        return pf.toIntOrNull()
    }

    // ===================== 策略路由 =====================
    private suspend fun applyRouting(s: ProxySettings): ShellResult {
        val sb = StringBuilder()
        val mark = s.markId
        val table = s.tableId

        // tproxy 路由：标记流量经 local default dev lo 再被 TPROXY 截获
        if (s.tcpMethod == ProxyMethod.TPROXY || s.udpMethod == ProxyMethod.TPROXY) {
            sb.appendLine("ip rule add fwmark $mark table $table pref 100 2>/dev/null")
            sb.appendLine("ip route add local default dev lo table $table 2>/dev/null")
            if (s.ipv6Enabled) {
                sb.appendLine("ip -6 rule add fwmark $mark table $table pref 100 2>/dev/null")
                sb.appendLine("ip -6 route add local default dev lo table $table 2>/dev/null")
            }
        }

        // tun 路由（tcp/udp 任一为 tun）
        if (s.tcpMethod == ProxyMethod.TUN || s.udpMethod == ProxyMethod.TUN) {
            val tmark = s.tunMarkId
            val ttable = s.tunTableId
            val tun = s.tunDevice
            sb.appendLine("ip route replace default dev $tun table $ttable 2>/dev/null")
            sb.appendLine("ip rule add fwmark $tmark table $ttable pref 101 2>/dev/null")
            sb.appendLine("ip rule add iif $tun lookup main suppress_prefixlength 0 pref 102 2>/dev/null")
            if (s.ipv6Enabled) {
                sb.appendLine("ip -6 route replace default dev $tun table $ttable 2>/dev/null")
                sb.appendLine("ip -6 rule add fwmark $tmark table $ttable pref 101 2>/dev/null")
                sb.appendLine("ip -6 rule add iif $tun lookup main suppress_prefixlength 0 pref 102 2>/dev/null")
            }
            // 对齐 box4 create_tun_route：热点转发 + 关闭 rp_filter
            sb.appendLine("echo 1 > /proc/sys/net/ipv4/ip_forward 2>/dev/null")
            sb.appendLine("echo 2 > /proc/sys/net/ipv4/conf/default/rp_filter 2>/dev/null")
            sb.appendLine("echo 2 > /proc/sys/net/ipv4/conf/all/rp_filter 2>/dev/null")
            sb.appendLine("echo 2 > /proc/sys/net/ipv4/conf/$tun/rp_filter 2>/dev/null")
        }

        // fake-ip6 dummy 网卡与本地路由（对齐 box4 setup_fakeip6_dummy：ip link add dummy + ip -6 route replace）
        if (s.ipv6Enabled && s.fakeIpRangeV6.isNotBlank()) {
            sb.appendLine("ip link add '${s.dummyDevice}' type dummy 2>/dev/null")
            sb.appendLine("ip link set '${s.dummyDevice}' up 2>/dev/null")
            sb.appendLine("ip -6 route replace '${s.fakeIpRangeV6}' dev '${s.dummyDevice}' 2>/dev/null")
        }
        return RootShell.runScript(sb.toString())
    }

    // ===================== 停止 =====================
    suspend fun stop(profile: Profile, settings: ProxySettings): String {
        val bin = profile.bin
        val sb = StringBuilder()
        // 杀进程
        sb.appendLine("pkill -x '$bin' 2>/dev/null; sleep 1; pkill -9 -x '$bin' 2>/dev/null")
        // 清 tproxy 路由（对齐 destroy_tproxy_route）
        sb.appendLine("while ip rule del fwmark ${settings.markId} table ${settings.tableId} 2>/dev/null; do :; done")
        sb.appendLine("ip route flush table ${settings.tableId} 2>/dev/null")
        sb.appendLine("while ip -6 rule del fwmark ${settings.markId} table ${settings.tableId} 2>/dev/null; do :; done")
        sb.appendLine("ip -6 route flush table ${settings.tableId} 2>/dev/null")
        // 清 tun 路由（对齐 destroy_tun_route：循环删 pref 101/102 + flush 表）
        if (settings.tcpMethod == ProxyMethod.TUN || settings.udpMethod == ProxyMethod.TUN) {
            sb.appendLine("while ip rule del pref 101 2>/dev/null; do :; done")
            sb.appendLine("while ip rule del pref 102 2>/dev/null; do :; done")
            sb.appendLine("ip route flush table ${settings.tunTableId} 2>/dev/null")
            sb.appendLine("while ip -6 rule del pref 101 2>/dev/null; do :; done")
            sb.appendLine("while ip -6 rule del pref 102 2>/dev/null; do :; done")
            sb.appendLine("ip -6 route flush table ${settings.tunTableId} 2>/dev/null")
        }
        // 清 fake-ip6 dummy 路由与网卡（对齐 cleanup_fakeip6_dummy）
        if (settings.ipv6Enabled && settings.fakeIpRangeV6.isNotBlank()) {
            sb.appendLine("ip -6 route del '${settings.fakeIpRangeV6}' dev '${settings.dummyDevice}' 2>/dev/null")
            sb.appendLine("ip link del '${settings.dummyDevice}' 2>/dev/null")
        }
        // 清 nft（全部规则在独立 table box，delete 即干净清除；两种表都删，避免残留）
        sb.appendLine("nft delete table ip box 2>/dev/null")
        sb.appendLine("nft delete table ip6 box 2>/dev/null")
        val r = RootShell.runScript(sb.toString())
        return if (r.ok) "已停止并清理防火墙与路由" else "停止异常:\n${r.stderr}"
    }

    // ===================== 状态 =====================
    suspend fun status(binaryName: String): CoreStatus {
        val pidR = RootShell.runScript("pidof '$binaryName' | head -n1")
        val pid = pidR.stdout.trim().toIntOrNull()
        if (pid == null) return CoreStatus(false)
        val memR = RootShell.runScript(
            "awk '/VmRSS/{print \$2}' /proc/$pid/status 2>/dev/null"
        )
        val mem = memR.stdout.trim().toLongOrNull()
        return CoreStatus(true, pid, mem)
    }

    // ===================== 一键还原 =====================
    suspend fun reset(): String {
        val r = RootShell.runScript(
            """
            for t in 2024 2025; do
                ip route flush table ${'$'}t 2>/dev/null
                ip -6 route flush table ${'$'}t 2>/dev/null
            done
            while ip rule del pref 100 2>/dev/null; do :; done
            while ip rule del pref 101 2>/dev/null; do :; done
            while ip rule del pref 102 2>/dev/null; do :; done
            nft delete table ip box 2>/dev/null
            nft delete table ip6 box 2>/dev/null
            nft delete table ip box_probe 2>/dev/null
            nft delete table ip6 box_probe 2>/dev/null
            ip link del box-dummy 2>/dev/null
            pkill -x sing-box 2>/dev/null; pkill -x mihomo 2>/dev/null
            pkill -x clash 2>/dev/null; pkill -x hysteria 2>/dev/null; pkill -x ant 2>/dev/null
            echo RESET_DONE
            """.trimIndent()
        )
        return if ("RESET_DONE" in r.stdout) "已还原：清空路由/规则/进程" else "还原异常:\n${r.stderr}"
    }

    // ===================== nftables：执行入口 =====================
    /** 返回 null 表示成功，否则返回错误信息 */
    private suspend fun applyNftables(s: ProxySettings, corePid: Int): String? {
        // 核心 owner（防回环用，对齐 box4 nft_prepare：读运行中核心的 uid/gid）
        val (uid, gid) = readProcOwner(corePid)
        // 包名 -> uid（黑白名单用，对齐 find_packages_uid）
        val pkgUids = resolvePackageUids(s)
        // 本机网卡地址加入 intranet 集合（对齐 nft_elems）
        val (local4, local6) = queryLocalAddrs(s.tunDevice)
        val ctx = NftContext(uid, gid, pkgUids, local4, local6)

        val nftFile = File(context.filesDir, "box.nft")
        val fams = buildList {
            add("ip")
            if (s.ipv6Enabled) add("ip6")
        }

        // 能力探测 + 方式降级（对齐 nft_apply_family 的 has_tproxy/has_socket/has_nat）
        val activePlans = fams.map { planFamily(it, s) }.filter { familyNeeded(it, s) }
        val needMangle = activePlans.any {
            it.tcp == ProxyMethod.TPROXY || it.tcp == ProxyMethod.TUN ||
                it.udp == ProxyMethod.TPROXY || it.udp == ProxyMethod.TUN
        }

        // ===== 事务 1：mangle + filter（含 intranet 集合）=====
        if (needMangle) {
            nftFile.writeText(activePlans.joinToString("\n") { buildNftMainScript(it, s, ctx, includeLocal = true) })
            var load = RootShell.runScript("nft -f '$nftFilePath'")
            if (!load.ok) {
                // 对齐 box4：本机地址导致集合冲突时，去掉后重试一次
                nftFile.writeText(activePlans.joinToString("\n") { buildNftMainScript(it, s, ctx, includeLocal = false) })
                load = RootShell.runScript("nft -f '$nftFilePath'")
                if (!load.ok)
                    return "nftables 加载失败（内核可能缺 nf_tables / nft_tproxy）:\n${load.stderr}"
            }
        }

        // ===== 事务 2：nat（DNS 劫持 / ping 劫持 / redir；失败不影响 mangle）=====
        for (p in activePlans) {
            val nat = buildNftNatScript(p, s, ctx) ?: continue
            nftFile.writeText(nat)
            val r = RootShell.runScript("nft -f '$nftFilePath'")
            if (!r.ok && p.fam == "ip") {
                return "nftables nat 加载失败（DNS 劫持 / REDIRECT 不可用）:\n${r.stderr}"
            }
            // ip6 nat 失败仅降级（内核可能缺 nf_nat_ipv6），与 box4 一致
        }
        return null
    }

    /** 该地址族是否需要生成任何规则 */
    private fun familyNeeded(p: FamilyPlan, s: ProxySettings): Boolean {
        val hasMethod = p.tcp != ProxyMethod.NULL || p.udp != ProxyMethod.NULL
        val dns = if (p.fam == "ip6") s.dnsHijackEnabled && s.dnsHijackV6Enabled else s.dnsHijackEnabled
        val fake = if (p.fam == "ip6") s.fakeIpRangeV6 else s.fakeIpRangeV4
        val ping = s.fakeIpPingHijack && fake.isNotBlank()
        return hasMethod || dns || ping
    }

    // ===================== nftables：能力探测与方式规划 =====================
    data class FamilyPlan(
        val fam: String,
        val tcp: ProxyMethod,
        val udp: ProxyMethod,
        val socketOk: Boolean
    )

    /** 探测内核 nft 能力并按 box4 规则降级：tproxy 缺失 → tcp 降 redir / udp 降 null；nat 缺失 → redir 不可用 */
    private suspend fun planFamily(fam: String, s: ProxySettings): FamilyPlan {
        var tcp = s.tcpMethod
        var udp = s.udpMethod
        var socketOk = true
        val preroutingDef = "type filter hook prerouting priority -150; policy accept;"

        if (tcp == ProxyMethod.TPROXY || udp == ProxyMethod.TPROXY) {
            val tproxyOk = nftProbe(
                fam, preroutingDef, "meta l4proto udp tproxy to :1 meta mark set 1 accept"
            )
            if (!tproxyOk) {
                if (tcp == ProxyMethod.TPROXY) tcp = ProxyMethod.REDIR
                if (udp == ProxyMethod.TPROXY) udp = ProxyMethod.NULL
            } else if (tcp == ProxyMethod.TPROXY) {
                // socket 表达式探测：不支持则跳过已建立连接 DIVERT（对齐 NFT_SOCKET_OK）
                socketOk = nftProbe(
                    fam, preroutingDef, "meta l4proto tcp socket transparent 1 meta mark set 1 accept"
                )
            }
        }
        if (tcp == ProxyMethod.REDIR && !nftProbe(
                fam, "type nat hook prerouting priority -100; policy accept;", "meta l4proto tcp redirect to :1"
            )
        ) {
            tcp = ProxyMethod.NULL
        }
        return FamilyPlan(fam, tcp, udp, socketOk)
    }

    /** 临时 probe 表验证 nft 表达式支持（对齐 box4 nft_probe），用后即删 */
    private suspend fun nftProbe(fam: String, chainDef: String, rule: String): Boolean {
        val probe = File(context.filesDir, "box_probe.nft")
        probe.writeText(
            buildString {
                appendLine("add table $fam box_probe")
                appendLine("add chain $fam box_probe c { $chainDef }")
                appendLine("add rule $fam box_probe c $rule")
            }
        )
        val r = RootShell.runScript(
            "if nft -f '${probe.absolutePath}' >/dev/null 2>&1; then echo PROBE_OK; fi; " +
                "nft delete table $fam box_probe 2>/dev/null"
        )
        return "PROBE_OK" in r.stdout
    }

    // ===================== nftables：规则生成 =====================
    data class NftContext(
        val ownerUid: String,
        val ownerGid: String,
        val pkgUids: List<String>,
        val localAddrs4: List<String>,
        val localAddrs6: List<String>
    )

    /**
     * 事务 1 脚本：mangle + filter（对齐 box4 nft_emit_mangle + filter_out/filter_in/filter_fwd）。
     * 先 add+delete 原子替换旧表，避免上次残留导致 "table already exists" 加载失败。
     */
    private fun buildNftMainScript(p: FamilyPlan, s: ProxySettings, ctx: NftContext, includeLocal: Boolean): String {
        val fam = p.fam
        val kw = if (fam == "ip6") "ip6" else "ip"
        val setType = if (fam == "ip6") "ipv6_addr" else "ipv4_addr"
        val tproxyUsed = p.tcp == ProxyMethod.TPROXY || p.udp == ProxyMethod.TPROXY
        val tunUsed = p.tcp == ProxyMethod.TUN || p.udp == ProxyMethod.TUN

        // intranet 集合元素：设置页列表剔除 fake-ip 段（fake-ip 必须进核心），加本机网卡地址
        val base = (if (fam == "ip6") s.intranet6 else s.intranet).map { it.trim() }
        val fake = (if (fam == "ip6") s.fakeIpRangeV6 else s.fakeIpRangeV4).trim()
        val locals = if (fam == "ip6") ctx.localAddrs6 else ctx.localAddrs4
        val elems = buildList {
            addAll(base.filter { it.isNotEmpty() && it != fake })
            if (includeLocal) addAll(locals)
        }

        val sb = StringBuilder()
        sb.appendLine("add table $fam box")
        sb.appendLine("delete table $fam box")
        sb.appendLine("table $fam box {")
        sb.appendLine("    set intranet {")
        sb.appendLine("        type $setType; flags interval; auto-merge;")
        if (elems.isNotEmpty()) {
            sb.appendLine("        elements = { ${elems.joinToString(", ")} }")
        }
        sb.appendLine("    }")

        // ----- mangle_pre（PREROUTING，仅 tproxy/tun 需要）-----
        if (tproxyUsed || tunUsed) {
            sb.appendLine("    chain mangle_pre {")
            sb.appendLine("        type filter hook prerouting priority -150; policy accept;")
            // tproxy 已建立连接 DIVERT（仅 TCP，socket 表达式可用时）
            if (p.tcp == ProxyMethod.TPROXY && p.socketOk) {
                sb.appendLine("        meta l4proto tcp socket transparent 1 ${markSetExpr(s.markId)} accept")
            }
            // 防回环：从 tun 回来的包不能再被打标/TPROXY
            if (tunUsed) sb.appendLine("        iifname \"${s.tunDevice}\" return")
            // 防回环：本机经 lo 回环进来的包只处理带 tproxy mark 的
            if (tproxyUsed) sb.appendLine("        iifname \"lo\" ${markMatchExpr(s.markId, ne = true)} return")
            // DNS 由 nat 处理，mangle 跳过
            sb.appendLine("        tcp dport 53 return")
            sb.appendLine("        udp dport 53 return")
            sb.appendLine("        $kw daddr @intranet return")
            for ((proto, m) in listOf("tcp" to p.tcp, "udp" to p.udp)) {
                when (m) {
                    ProxyMethod.TPROXY ->
                        sb.appendLine("        meta l4proto $proto tproxy to :${s.tproxyPort} ${markSetExpr(s.markId)} accept")
                    ProxyMethod.TUN ->
                        sb.appendLine("        meta l4proto $proto ${markSetExpr(s.tunMarkId)}")
                    else -> {}
                }
            }
            sb.appendLine("    }")

            // ----- mangle_out（本机流量，type route：打标后触发重新路由）-----
            sb.appendLine("    chain mangle_out {")
            sb.appendLine("        type route hook output priority -150; policy accept;")
            // 防回环：核心自身流量放行
            sb.appendLine("        meta skuid ${ctx.ownerUid} meta skgid ${ctx.ownerGid} return")
            sb.appendLine("        tcp dport 53 return")
            sb.appendLine("        udp dport 53 return")
            sb.appendLine("        $kw daddr @intranet return")
            emitUidPolicy(sb, listOf("tcp", "udp"), s, ctx) { proto ->
                when (if (proto == "tcp") p.tcp else p.udp) {
                    ProxyMethod.TPROXY -> markSetExpr(s.markId)
                    ProxyMethod.TUN -> markSetExpr(s.tunMarkId)
                    else -> null
                }
            }
            sb.appendLine("    }")
        }

        // ----- filter_out：tproxy 防回环，核心不能连自己的 tproxy 端口 -----
        if (tproxyUsed) {
            val lo = if (fam == "ip6") "::1" else "127.0.0.1"
            sb.appendLine("    chain filter_out {")
            sb.appendLine("        type filter hook output priority 0; policy accept;")
            sb.appendLine(
                "        $kw daddr $lo meta l4proto tcp tcp dport ${s.tproxyPort} " +
                    "meta skuid ${ctx.ownerUid} meta skgid ${ctx.ownerGid} reject"
            )
            sb.appendLine("    }")
        }

        // ----- filter_in / filter_fwd：仅 tun 方式时放行 tun 网卡 -----
        if (tunUsed) {
            sb.appendLine("    chain filter_in {")
            sb.appendLine("        type filter hook input priority -1; policy accept;")
            sb.appendLine("        iifname \"${s.tunDevice}\" accept")
            sb.appendLine("    }")
            sb.appendLine("    chain filter_fwd {")
            sb.appendLine("        type filter hook forward priority -1; policy accept;")
            sb.appendLine("        iifname \"${s.tunDevice}\" accept")
            sb.appendLine("        oifname \"${s.tunDevice}\" accept")
            sb.appendLine("    }")
        }
        sb.appendLine("}")
        return sb.toString()
    }

    /**
     * 事务 2 脚本：nat（对齐 box4 nft_emit_nat）。无需任何 nat 规则时返回 null。
     *  - nat_pre：fake-ip ping 劫持、DNS 劫持、redir（仅 iif lo，外部流量按需 ap_list——MVP 未提供）
     *  - nat_out：本机方向，DNS/redir 前先核心 owner return 防回环，redir 走黑白名单
     */
    private fun buildNftNatScript(p: FamilyPlan, s: ProxySettings, ctx: NftContext): String? {
        val fam = p.fam
        val kw = if (fam == "ip6") "ip6" else "ip"
        val icmp = if (fam == "ip6") "icmpv6" else "icmp"
        val fake = (if (fam == "ip6") s.fakeIpRangeV6 else s.fakeIpRangeV4).trim()
        val dns = if (fam == "ip6") s.dnsHijackEnabled && s.dnsHijackV6Enabled else s.dnsHijackEnabled
        val redir = p.tcp == ProxyMethod.REDIR
        val ping = s.fakeIpPingHijack && fake.isNotBlank()
        if (!dns && !redir && !ping) return null

        val sb = StringBuilder()
        sb.appendLine("table $fam box {")

        sb.appendLine("    chain nat_pre {")
        sb.appendLine("        type nat hook prerouting priority -100; policy accept;")
        if (ping) sb.appendLine("        $kw daddr $fake $icmp type echo-request redirect")
        if (dns) {
            sb.appendLine("        udp dport 53 redirect to :${s.dnsPort}")
            sb.appendLine("        tcp dport 53 redirect to :${s.dnsPort}")
        }
        if (redir) {
            sb.appendLine("        $kw daddr @intranet return")
            sb.appendLine("        iifname \"lo\" meta l4proto tcp redirect to :${s.redirPort}")
        }
        sb.appendLine("    }")

        sb.appendLine("    chain nat_out {")
        sb.appendLine("        type nat hook output priority -100; policy accept;")
        if (ping) sb.appendLine("        $kw daddr $fake $icmp type echo-request redirect")
        if (dns) {
            // 核心自身的 DNS 不劫持，避免回环
            sb.appendLine("        meta skuid ${ctx.ownerUid} meta skgid ${ctx.ownerGid} udp dport 53 return")
            sb.appendLine("        meta skuid ${ctx.ownerUid} meta skgid ${ctx.ownerGid} tcp dport 53 return")
            sb.appendLine("        udp dport 53 redirect to :${s.dnsPort}")
            sb.appendLine("        tcp dport 53 redirect to :${s.dnsPort}")
        }
        if (redir) {
            sb.appendLine("        meta skuid ${ctx.ownerUid} meta skgid ${ctx.ownerGid} return")
            sb.appendLine("        $kw daddr @intranet return")
            emitUidPolicy(sb, listOf("tcp"), s, ctx) { "redirect to :${s.redirPort}" }
        }
        sb.appendLine("    }")
        sb.appendLine("}")
        return sb.toString()
    }

    /**
     * 本机流量黑/白名单（对齐 box4 apply_uid_policy / nft_apply_uid_policy）：
     *  - BLACKLIST：名单内 uid return，其余全代理
     *  - WHITELIST：仅名单内 uid 代理（名单非空时 root/dnsmasq 始终代理）
     *  - CORE：全代理（核心自行处理）
     */
    private fun emitUidPolicy(
        sb: StringBuilder,
        protos: List<String>,
        s: ProxySettings,
        ctx: NftContext,
        target: (String) -> String?
    ) {
        when (s.proxyMode) {
            ProxyMode.BLACKLIST -> {
                ctx.pkgUids.forEach { sb.appendLine("        meta skuid $it return") }
                protos.forEach { proto ->
                    target(proto)?.let { sb.appendLine("        meta l4proto $proto $it") }
                }
            }
            ProxyMode.WHITELIST -> {
                protos.forEach { proto ->
                    val t = target(proto) ?: return@forEach
                    ctx.pkgUids.forEach { sb.appendLine("        meta l4proto $proto meta skuid $it $t") }
                    if (ctx.pkgUids.isNotEmpty()) {
                        // 名单非空时始终放行 root 与 dnsmasq（对齐 box4）
                        sb.appendLine("        meta l4proto $proto meta skuid 0 $t")
                        sb.appendLine("        meta l4proto $proto meta skuid 1052 $t")
                    }
                }
            }
            ProxyMode.CORE -> {
                protos.forEach { proto ->
                    target(proto)?.let { sb.appendLine("        meta l4proto $proto $it") }
                }
            }
        }
    }

    // ===================== nftables：辅助 =====================

    /** markId "v/m" → nft 设置表达式，等价 iptables `MARK --set-xmark v/m`（(mark & ~m) | v） */
    private fun markSetExpr(markId: String): String {
        val (v, m) = splitMark(markId)
        return if (m == "4294967295") "meta mark set $v"
        else "meta mark set meta mark & ${(4294967295L - m.toLong())} | $v"
    }

    /** markId "v/m" → nft 匹配表达式，等价 iptables `-m mark --mark v/m` */
    private fun markMatchExpr(markId: String, ne: Boolean): String {
        val (v, m) = splitMark(markId)
        return "meta mark & $m ${if (ne) "!=" else "=="} $v"
    }

    private fun splitMark(markId: String): Pair<String, String> =
        if ("/" in markId) {
            val parts = markId.split("/", limit = 2)
            parts[0].trim() to parts[1].trim()
        } else {
            markId.trim() to "4294967295"
        }

    /** 读取运行中核心的 uid/gid（防回环 owner 匹配用；读不到按 root 0/0） */
    private suspend fun readProcOwner(pid: Int): Pair<String, String> {
        val r = RootShell.runScript("stat -c '%u %g' /proc/$pid 2>/dev/null")
        val parts = r.stdout.trim().split(Regex("\\s+"))
        val uid = parts.getOrNull(0)?.toIntOrNull()?.toString() ?: "0"
        val gid = parts.getOrNull(1)?.toIntOrNull()?.toString() ?: "0"
        return uid to gid
    }

    /** 包名 → uid（读 /data/system/packages.list，对齐 box4 find_packages_uid） */
    private suspend fun resolvePackageUids(s: ProxySettings): List<String> {
        if (s.proxyMode == ProxyMode.CORE || s.packageList.isEmpty()) return emptyList()
        val pkgs = s.packageList.map { it.trim() }.filter { it.isNotEmpty() }
        if (pkgs.isEmpty()) return emptyList()
        val script = buildString {
            append("for p in ")
            append(pkgs.joinToString(" ") { "'$it'" })
            append("; do awk -F: -v P=\"\$p\" '\$1==P{print \$2; exit}' /data/system/packages.list 2>/dev/null; done")
        }
        val r = RootShell.runScript(script)
        return r.stdout.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.toIntOrNull() != null }
    }

    /** 收集本机网卡地址（排除 tun 与回环），用于 intranet 集合（对齐 box4 nft_elems） */
    private suspend fun queryLocalAddrs(tun: String): Pair<List<String>, List<String>> {
        val r = RootShell.runScript(
            "ip -4 -o addr show 2>/dev/null | awk -v t='$tun' '\$2 != t {print \$4}' | cut -d/ -f1 | grep -vE '^127\\.'; " +
                "echo ---SPLIT---; " +
                "ip -6 -o addr show 2>/dev/null | awk -v t='$tun' '\$2 != t {print \$4}' | cut -d/ -f1 | grep -viE '^fe80|^::1$'"
        )
        val parts = r.stdout.split("---SPLIT---")
        val v4 = parts.getOrNull(0)?.lines()
            ?.map { it.trim() }?.filter { it.isNotEmpty() && !it.contains(":") } ?: emptyList()
        val v6 = parts.getOrNull(1)?.lines()
            ?.map { it.trim() }?.filter { it.isNotEmpty() && it.contains(":") } ?: emptyList()
        return v4 to v6
    }
}

data class StartResult(
    val ok: Boolean,
    val message: String,
    val pid: Int? = null
)

data class CoreStatus(
    val running: Boolean,
    val pid: Int? = null,
    val memKb: Long? = null
)
