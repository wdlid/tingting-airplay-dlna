#!/system/bin/sh
# 实验: 9420 唤醒后如何打断腾讯而不整包 force-stop
# 用法 (root): sh /data/local/tmp/adapter-probe.sh [full|subproc|none|status|pull-apk]
# 配合: logcat -s VoiceAgent GeminiLive; 说 9420 或手动 broadcast

MODE="${1:-status}"
APK_SYS=/system/app/miaAdapterGA1.3-3020028/miaAdapterGA1.3-3020028.apk

case "$MODE" in
  status)
    echo "=== adapter processes ==="
    ps | busybox grep -E 'tencent.adapter' || true
    echo "=== speaker_mute ==="
    busybox grep speaker_mute /data/data/com.tencent.adapter/shared_prefs/*.xml 2>/dev/null || true
    echo "=== voice busy ==="
    ls -l /data/local/tmp/tingting_voice_busy 2>/dev/null || echo "(idle)"
    ;;
  subproc)
    echo "kill :wns :tts :log only"
    ps | while read line; do
      case "$line" in
        *com.tencent.adapter:wns*|*com.tencent.adapter:tts*|*com.tencent.adapter:log*)
          set -- $line
          echo "kill $2 $line"
          kill "$2" 2>/dev/null
          ;;
      esac
    done
    sleep 1
    ps | busybox grep -E 'tencent.adapter' || true
    ;;
  full)
    am force-stop com.tencent.adapter
    sleep 1
    ps | busybox grep -E 'tencent.adapter' || echo "(all stopped)"
    ;;
  none)
    echo "no stop; only unmute"
    ;;
  restore)
    am start -n com.tencent.adapter/.ui.activity.MainActivity
    ;;
  pull-apk)
    echo "system apk: $APK_SYS"
    ls -l "$APK_SYS" 2>/dev/null || ls -l /system/app/*/miaAdapter*.apk 2>/dev/null
    ;;
  *)
    echo "usage: $0 full|subproc|none|status|restore|pull-apk"
    exit 1
    ;;
esac

# 修改 adapter 方向 (需 PC 上 apktool, 勿直接改 /system 除非 remount):
# 1. adb pull 系统 apk -> 反编译搜 OkWss / MiaOsStateMachine / onWakeup
# 2. 在 onWakeup 后 am broadcast com.tingting.gemini9420.action.WAKE_9420 (仍要处理 mic)
# 3. 或改 WSS URL 指向自建中继 (维护成本高, OTA 覆盖 /system)
# 4. 若 subproc 实验可释放 mic 且主进程仍监听 9420, 桥脚本设 ADAPTER_POLICY=subproc
