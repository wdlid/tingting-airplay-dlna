# 腾讯听听 (Android 4.4) 加入 AirPlay + DLNA

在一台老旧的 Allwinner A3003 / Android 4.4.2 / armeabi-v7a / 约 495 MB 内存的"腾讯听听"音箱上,
加入 **AirPlay 1 接收端**(shairport-sync) 和 **DLNA MediaRenderer**(纯 Java,运行在一个 APK 里),并开机自启。

## 架构

```
iPhone ──AirPlay──▶ shairport-sync (静态 musl 二进制, 由 APK 拉起) ─▶ ALSA hw:1,0 (SUNXI-I2S1)
网易云 ──DLNA────▶ DlnaRenderer.java (SSDP + HTTP/SOAP + GENA) ─▶ android.media.MediaPlayer
                                     └ https 链接 ─▶ 本机 127.0.0.1 代理(TLS1.2) ─▶ MediaPlayer
```

一个 APK(`com.tingting.shairport`)包含:开机自启前台服务、DLNA 渲染器、内置的 shairport-sync 二进制和配置。

## 目录

| 文件 | 作用 |
|---|---|
| `build.sh` | WSL 中交叉编译静态 shairport-sync(musl, ARMv7 hard-float, ALSA, OpenSSL, ALAC, soxr) |
| `relink_nopie.sh` | 重新链接为非 PIE 静态可执行文件(设备上更稳),输出 `dist/shairport-sync-nopie` |
| `patch_alsa.sh` / `alsa_embed.inc` | 对 shairport-sync 的补丁:内嵌 ALSA 配置、start_threshold、自守护、崩溃处理 |
| `shairport-sync.conf` | 设备上使用的配置 |
| `verify.sh` / `addr.sh` / `halcfg.sh` | 检查 ELF、qemu 冒烟测试、崩溃地址解析、导出系统 HAL 参数 |
| `autostart-app/` | APK 源码(Java)和 `build-apk.sh`(无需 Android SDK/Gradle) |
| `test.sh` | 设备端冒烟测试 |
| `dlna_test.py` / `dlna_play.py` | PC 端 DLNA 控制点测试:SSDP 发现、SOAP 控制、播放指定 URL |

## 构建

在 Windows 上用 WSL(Ubuntu 20.04):

```bash
# 1. 编译 shairport-sync (首次会下载依赖; musl.cc 工具链很慢, 可手动下载放到 ~/sp/dl/toolchain.tgz)
bash build.sh
bash relink_nopie.sh          # 得到 dist/shairport-sync-nopie
bash verify.sh                # 可选: 检查架构/静态链接并用 qemu 试跑

# 2. 打包 APK (需要: default-jdk-headless aapt apksigner zipalign dalvik-exchange libandroid-23-java)
bash autostart-app/build-apk.sh   # 得到 dist/AirPlayReceiver.apk
```

脚本需要 LF 换行。若 clone 后在 WSL 里报 `\r` 错误: `sed -i 's/\r$//' *.sh autostart-app/*.sh`。

## 安装与测试

```bash
adb connect 192.168.2.224:5555
adb install -r dist/AirPlayReceiver.apk
adb shell am start -n com.tingting.shairport/.MainActivity   # 必须手动启动一次, 否则收不到 BOOT_COMPLETED
DEVICE=192.168.2.224:5555 ./test.sh                          # 冒烟测试
python dlna_play.py "http://example.com/a.mp3" 8             # 指定 URL 播放并轮询状态
```

手动验证:iPhone 控制中心选 `TingTing` 播放;网易云/QQ 音乐投屏里搜 `TingTing`。
重启测试: `adb shell setprop sys.powerctl reboot`(需要 root,见下)。

## 经验总结

### 设备与环境
- **`adb reboot` 在这台设备上无效**,要用 root 执行 `setprop sys.powerctl reboot`。
- **root**:USB adb 下用 `echo -n rootmydevice > /proc/sunxi_debug/sunxi_debug` 只能得到权限受限的 uid 0(没有 SYS_ADMIN,不能 remount `/system`)。**网络 adb**(`adb connect <ip>:5555`)是完整 root,可以 remount。
- Android 4.4 的 adbd 在 shell 退出时会杀掉子进程,没有 nohup/setsid。做法是二进制自己 fork 两次守护化(`SP_DAEMON=1`)。
- 没有 Android SDK 也能出 APK:WSL 里 `javac`(`-source 8`,android-23.jar)+ `dx` + `aapt` + `zipalign` + `apksigner`。
- Android 3.1+ 的应用必须先被用户启动一次,才会收到 `BOOT_COMPLETED`;服务里要持有 WakeLock、WifiLock、MulticastLock。
- 开机时 Wi-Fi 可能还没拿到 IP,服务里要等到有 IPv4 再启动;`killStale()` 清理上次遗留的 shairport-sync,否则占着 5000 端口。

### AirPlay (shairport-sync 4.3.7)
- 使用 musl 全静态链接(`--with-alsa --with-ssl=openssl --with-tinysvcmdns --with-soxr`)。glibc 静态+mDNS 体验差,musl + tinysvcmdns 不需要 avahi。
- 默认 `-static-pie` 在设备上会出问题,改成 `-static -no-pie`。
- 声卡是 `SUNXI-I2S1`(`hw:1,0`),驱动接受 44100 但硬件实际跑 48000。**必须用 plug 设备做重采样**(`pcm.sp48` 内嵌在二进制里),否则每约 0.5 秒出现同步错误和爆音。
- 驱动只在每个 period 更新 hw_ptr,默认 period 很大。**必须调高 `start_threshold`**(补丁,默认 4096 帧),否则一开始就 XRUN,表现是爆裂声和一顿一顿。
- `use_mmap_if_available = "no"`、`use_precision_timing = "no"`:精确定时路径会崩溃。
- 不要关闭同步(drift)检测,关了会持续 underrun。
- ALSA 配置内嵌在二进制里,运行时写到可写目录再通过 `ALSA_CONFIG_PATH` 指向,避免依赖 `/usr/share/alsa`。
- AirPlay 1 协议自带约 2 秒缓冲,所以暂停/恢复比 DLNA 慢,这是协议行为。

### DLNA
- 纯 Java 实现:SSDP(M-SEARCH 应答 + NOTIFY alive/byebye)、HTTP 服务(设备/服务描述、SOAP、GENA 订阅)、`MediaPlayer` 播放。
- **SSDP 组播地址是 `239.255.255.250`**。曾经手滑写成 `239.255.250.250`,PC 测试脚本也犯了同样的错,所以自测通过但网易云死活搜不到。
- 需要 AVTransport、RenderingControl、ConnectionManager 三个服务,控制点才会认设备;AVTransport 用 `LastChange` 事件报告状态。
- **厂商播放器(Cedarx)只支持旧 TLS**,https 链接报 `MediaPlayer error -1004` / `tlsv1 alert protocol version`。App 内置了 127.0.0.1 的 https→http 代理(Java TLS1.2、设置 SNI、放宽证书校验,因为系统根证书过期),`SetAVTransportURI` 时自动改写 https 链接。
- 部分站点(例如 `upload.wikimedia.org`)与 Android 4.4 的 TLS 套件不兼容,握手仍会失败;`soundhelix.com` 正常。
- **音量**:系统音乐音量档位很粗,低档位仍然很响,"往下调不灵敏"。做法:系统音量固定最大,用 `MediaPlayer.setVolume` 做 0–100 的平方曲线,默认音量 12%。
- 不要同时运行其他 DLNA 服务,会抢 SSDP 1900 端口和声卡。

### 调试方法
- PowerShell 调 WSL 时,不要在 `wsl bash -c "..."` 里写复杂引号和 `$变量`,把逻辑放进脚本文件。
- 脚本用 LF;WSL 中的 `apksigner` 要用 `java -jar` 调用。
- 崩溃处理器打印 `CRASH sig=... pc=...`,用 `addr.sh` 配合未 strip 的二进制 `addr2line` 定位。
- 先用 `/sdcard` 本地文件验证播放,再排查网络和 TLS;Windows 防火墙会挡住设备回连 PC 的 HTTP 服务。

## 已知限制
- 只有 AirPlay 1(不是 AirPlay 2)。
- DLNA 只做了单曲播放,没有实现播放列表。
- 签名使用调试密钥库,`build-apk.sh` 在没有时自动生成(密钥库不入库)。
