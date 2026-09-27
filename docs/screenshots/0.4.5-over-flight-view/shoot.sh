#!/usr/bin/env bash
# shoot.sh <grep pattern in logcat> <sleep s after match> <out.png> [timeout s]
S=$(dirname "$0"); A="$HOME/Android/Sdk/platform-tools/adb -s emulator-5556"
PAT=$1; D=$2; OUT=$3; TO=${4:-120}
start=$(grep -ac "" "$S/logcat.txt")
for i in $(seq 1 $((TO*5))); do
  if tail -n +$((start+1)) "$S/logcat.txt" | grep -aq -- "$PAT"; then break; fi; sleep 0.2
done
tail -n +$((start+1)) "$S/logcat.txt" | grep -a -- "$PAT" | head -1
sleep "$D"
$A exec-out screencap -p > "$OUT"
$A shell dumpsys activity service com.android.systemui/.SystemUIService > "$OUT.sysui.txt" 2>/dev/null
echo "shot $OUT at $(date +%T.%N | cut -c1-12)"
