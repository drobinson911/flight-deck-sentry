#!/usr/bin/env bash
S=$(dirname "$0"); cd "$S"; A="$HOME/Android/Sdk/platform-tools/adb -s emulator-5556"
start=$(grep -ac "" logcat.txt)
$A shell am start-foreground-service -n com.uasflightdeck.sentry/.SentryService -a com.uasflightdeck.sentry.REPLAY --ez crossing false >/dev/null
for i in $(seq 1 1000); do tail -n +$((start+1)) logcat.txt | grep -aq "ALERT\[replay\] 11:52:57" && break; sleep 0.1; done
t0=$(date +%s.%N); tail -n +$((start+1)) logcat.txt | grep -a "ALERT\[replay\] 11:52:57" | head -1
at() { python3 -c "import time; d=$t0+$1-time.time(); time.sleep(max(0,d))"; }
at 1.0; $A exec-out screencap -p > pass-0-115258-headsup.png; echo "headsup $(date +%T.%N|cut -c1-12)"
$A shell dumpsys activity service com.android.systemui/.SystemUIService > pass-0.sysui.txt
at 6.2; $A shell cmd statusbar expand-notifications
for k in 7 8 9 10 11 12 13; do at $k.5; $A exec-out screencap -p > pass-shade-$k.png; echo "shade +$k.5 = replay 11:53:0$((k-3)) $(date +%T.%N|cut -c1-12)"; done
$A shell dumpsys activity service com.android.systemui/.SystemUIService > pass-shade.sysui.txt
$A shell cmd statusbar collapse
