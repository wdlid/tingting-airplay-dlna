#!/system/bin/sh
# 9420 唤醒 -> Gemini 语音 APK (与 AirPlay 无关)
# 需 root: adb root 后后台运行
#
# ADAPTER_POLICY 控制如何打断腾讯云端会话:
#   full     - am force-stop com.tencent.adapter (默认, 最稳但 9420 需恢复)
#   subproc  - 只 kill :wns / :tts / :log 子进程 (实验, 见 adapter-probe.sh)
#   none     - 不杀 adapter, 仅 unmute + 广播 (通常 mic 仍被占)

ADAPTER_POLICY="${ADAPTER_POLICY:-full}"
STAMP=/data/local/tmp/tingting_wake
ACTION=com.tingting.gemini9420.action.WAKE_9420
PKG=com.tingting.gemini9420
PIDFILE=/data/local/tmp/voice-wake-bridge.pid
VOICE_BUSY=/data/local/tmp/tingting_voice_busy
ADAPTER_STOPPED=/data/local/tmp/tingting_adapter_stopped

echo $$ > "$PIDFILE"
touch "$STAMP" 2>/dev/null
chmod 666 "$STAMP" 2>/dev/null

should_fire() {
  now=$(date +%s)
  old=0
  [ -f "$STAMP" ] && old=$(cat "$STAMP" 2>/dev/null)
  [ -z "$old" ] && old=0
  if [ "$((now - old))" -lt 4 ]; then
    return 1
  fi
  echo "$now" > "$STAMP"
  return 0
}

unmute_speaker() {
  for f in /data/data/com.tencent.adapter/shared_prefs/*.xml; do
    [ -f "$f" ] || continue
    if busybox grep -q 'speaker_mute">1' "$f" 2>/dev/null; then
      busybox sed -i 's/speaker_mute">1/speaker_mute">0/g' "$f" 2>/dev/null
    fi
  done
  settings put system volume_music 15 2>/dev/null
  settings put system volume_alarm 15 2>/dev/null
}

stop_adapter_cloud() {
  ps | while read line; do
    case "$line" in
      *com.tencent.adapter:wns*|*com.tencent.adapter:tts*|*com.tencent.adapter:log*)
        set -- $line
        [ -n "$2" ] && kill "$2" 2>/dev/null
        ;;
    esac
  done
}

interrupt_adapter() {
  case "$ADAPTER_POLICY" in
    none)
      return 0
      ;;
    subproc)
      stop_adapter_cloud
      touch "$ADAPTER_STOPPED" 2>/dev/null
      ;;
    full|*)
      am force-stop com.tencent.adapter 2>/dev/null
      touch "$ADAPTER_STOPPED" 2>/dev/null
      ;;
  esac
}

restore_adapter_if_needed() {
  [ -f "$ADAPTER_STOPPED" ] || return 0
  [ -f "$VOICE_BUSY" ] && return 0
  rm -f "$ADAPTER_STOPPED" 2>/dev/null
  case "$ADAPTER_POLICY" in
    none)
      return 0
      ;;
    subproc|full|*)
      am start -n com.tencent.adapter/.ui.activity.MainActivity 2>/dev/null
      ;;
  esac
}

fire_gemini() {
  [ -f "$VOICE_BUSY" ] && return 0
  should_fire || return
  interrupt_adapter
  unmute_speaker
  sleep 1
  am broadcast -a "$ACTION" -n "$PKG/.WakeReceiver" 2>/dev/null
}

watch_voice_idle() {
  while true; do
    sleep 3
    restore_adapter_if_needed
  done
}

watch_voice_idle &

logcat -v time 2>/dev/null | while read line; do
  case "$line" in
    *"onWakeup confidence="*|*"onWakeup:audio"*|*"MESSAGE_MIA_ENGINE_WAKE"*)
      fire_gemini
      ;;
    *mia_wakeup*|*wakeup_16k16b*)
      fire_gemini
      ;;
  esac
done
