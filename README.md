# PFA (MVP)

一个用于管理代理核心（sing-box / mihomo / clash / hysteria / ant）与配置文件的 Android APK。
运行前指定**代理核心、配置文件、运行参数**，点击「启动」后自动配置防火墙与策略路由；点击「停止」清理。

> 包名：`com.pfa`；本 MVP 为 **纯 Kotlin 重写**实现（通过 libsu 逐条下发特权命令），参考了 box4 透明代理模块（Magisk/KernelSU/APatch）的脚本逻辑，但不依赖外部 shell 脚本。

## 工作目录体系（root）

| 目录 | 用途 |
|---|---|
| `/data/adb/proxy` | APK 工作根目录 |
| `/data/adb/proxy/bin` | 代理核心二进制（上传后自动 `chmod 755`） |
| `/data/adb/proxy/profiles` | 配置文件上传目录 |
| `/data/adb/proxy/run` | 运行目录（启动前把选中的配置复制进来，pid 文件也在此） |

## 三个页面

| 页面 | 功能 |
|---|---|
| **首页** | 启动/停止开关、运行状态（PID/内存）、选择代理核心、选择配置文件、运行参数、运行日志 |
| **管理** | 上传核心二进制、上传配置文件（系统文件选择器）、编辑配置文件（内置文本编辑）、删除文件 |
| **设置** | 对应 box.config 的全局选项：防火墙后端、TCP/UDP 代理方式、代理模式与包名列表、端口（tproxy/redir/dns）、IPv6、DNS 劫持（v4/v6 独立开关）、Fake-IP（range v4/v6、dummy 网卡、ping 劫持）、intranet/intranet6 排除网段（可编辑）、fwmark/路由表号/TUN 设备、启动前检查、一键还原 |

## 功能（MVP）

- **root 检测**：启动时通过 `Shell.getShell()` 建立 su 会话并触发 Magisk/KernelSU/APatch 授权弹窗，之后再查询 `isAppGrantedRoot()`（修复了 shell 未建立时永远检测不到 root 的问题）
- 首页选择核心 + 配置文件 + 运行参数 → 启动时：校验二进制 → 复制配置到 run/ →（sing-box `check`）→ `setsid` 拉起核心 → 配置 fwmark 策略路由 → 加载 nftables
- 停止：杀进程 → 清空路由表/规则 → 删除 nft 表；「一键还原」防配错断网
- 实时状态轮询（PID / 内存）与操作日志
- 文件导入经「app 缓存 + root cp」落地，无需存储权限

## 架构

```
UI (Jetpack Compose, 底部导航三页)
  ├─ HomeScreen / ManageScreen / SettingsScreen
  └─ MainViewModel（状态、启停、文件管理、轮询）
        ├─ ProfileStore（启动档案持久化）/ SettingsStore（全局设置持久化）
        ├─ FileManager（列出/导入/读写/删除 root 目录文件）
        └─ ProxyManager（核心启停、策略路由、nftables 生成、还原）
              └─ RootShell（libsu 封装，root 检测）
```

| 层 | 文件 | 职责 |
|---|---|---|
| domain | `domain/Profile.kt` | 枚举、`ProxyPaths`（目录体系）、`Profile`（启动档案）、`ProxySettings`（全局设置） |
| data | `data/ProfileStore.kt` / `data/SettingsStore.kt` | JSON 持久化 |
| root | `root/RootShell.kt` | libsu 执行封装 + root 检测 |
| root | `root/FileManager.kt` | root 目录文件管理 |
| root | `root/ProxyManager.kt` | 核心启停、策略路由、nftables 生成、还原 |
| ui | `ui/HomeScreen.kt` / `ui/ManageScreen.kt` / `ui/SettingsScreen.kt` / `ui/Widgets.kt` | 三个页面与共享组件 |
| viewmodel | `viewmodel/MainViewModel.kt` | 串联 |

## 与 box4 模块的对应关系

| box4 | 本 APK |
|---|---|
| `box.config` | `ProxySettings`（设置页）+ `Profile`（首页启动档案） |
| `box.service start_bin` | `ProxyManager.buildLaunchCommand` + `setsid` |
| `box.tproxy create_tproxy_route` | `ProxyManager.applyRouting`（fwmark + `local default dev lo`） |
| `box.tproxy create_tun_route` | 同上（tun 分支：default dev tun0 + pref 101/102） |
| `box.nft`（table ip/ip6 box） | `ProxyManager.buildNftScript` 生成脚本，`nft -f` 加载 |
| `box.service stop_service` | `ProxyManager.stop` |
| 开机自启 `box4_service.sh` | （MVP 未实现，纯手动按需） |

## 本地构建

需要：Android Studio（Hedgehog 及以上）+ Android SDK 34 + 一台已 root 的设备（Magisk / KernelSU / APatch）。

```bash
# 克隆或拷贝本目录后，用 Android Studio 打开本目录
# 或用命令行（需已配置 ANDROID_HOME / sdkmanager）：
./gradlew assembleDebug
```

依赖（见 `app/build.gradle.kts`）：
- AGP 8.3.2 / Kotlin 1.9.24 / Compose Compiler 1.5.14 / Compose BOM 2024.04.01
- 已启用 Gradle 插件：`org.jetbrains.kotlin.plugin.serialization`（数据类使用 `@Serializable`）
- `com.github.topjohnwu.libsu:core:6.0.0`（root 命令执行；**仅发布在 JitPack**，已在 `settings.gradle.kts` 加入 `maven { url "https://jitpack.io" }`）
- `org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3`
- `org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1`

## 部署与测试

1. 安装 APK 并打开，在授权弹窗中授予 root（首页会显示「已获得 root 权限」）。
2. 「管理」页：上传核心二进制（自动放入 `/data/adb/proxy/bin/` 并 `chmod 755`）、上传配置文件（放入 `/data/adb/proxy/profiles/`）。
3. 「首页」：选择核心与配置文件、按需填写运行参数，打开开关启动。
4. 「设置」页：按需调整防火墙/路由选项（默认对齐 box4 的 tproxy + nftables），异常时点「一键还原」。
5. 验证：`ip rule ls`、`nft list table ip box`、`ls /data/adb/proxy/run`、`pidof sing-box`。

核心配置文件注意：**核心需关闭 tun 自动路由**（auto-route / auto_route = false），流量导入由 APK 负责。

## 已知限制（MVP）

- 仅实现 **nftables** 主线路径；iptables + ipset 回退仅留 TODO。
- 包名黑白名单字段已留存（`ProxySettings.packageList`），但 UID 匹配规则尚未接入 nft 生成。
- 多 Profile 数据层已支持，UI 暂只暴露单个启动档案。
- 未实现开机自启（`service.d` 注册）。
- 核心日志未采集（仅记录 APK 侧操作日志）。
- nft TPROXY 需要内核 `nf_tables` + `nft_tproxy`；nat DNS 劫持需要 `nf_nat`。不支持时启动会返回明确错误。

## GitHub Actions 自动构建

仓库已包含 Gradle Wrapper（`gradlew` / `gradlew.bat` / `gradle/wrapper/`）与
`.github/workflows/build.yml`。推送到 `main`/`master` 或手动触发 `workflow_dispatch`
即可在 ubuntu runner 上自动编译并产出 `app-debug.apk`（作为 Actions Artifact 下载）。

workflow 步骤：`checkout` → `setup-java 17` → `setup-android`（SDK）→ 安装
`platforms;android-34` + `build-tools;34.0.0` → `gradle/actions/setup-gradle`（缓存）
→ `./gradlew assembleDebug` → 上传 APK。

本地等价构建：

```bash
chmod +x gradlew
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```
