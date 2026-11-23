#!/system/bin/sh
# Route the stock HOME intent to the dashboard after Android resolves it.
# Runs as ordinary ADB shell, not root. Leave explicit Paladin launches alone.
# No injection, package changes, polling, or system-partition writes.
set -u
/system/bin/logcat -b system -v brief -T 1 -s ActivityManager:I | while IFS= read -r line; do
    case "$line" in
        *"START u0 {"*"act=android.intent.action.MAIN "*"cat=["*"android.intent.category.HOME"*"]"*"cmp=com.amazon.paladin/.app.MMLauncherActivity}"*"from uid "*)
            printf '%s: HOME redirected to dashboard\n' "$(date)"
            /system/bin/am start --user 0 -n com.bookcatkid.launcher/.MainActivity -f 0x14000000
            ;;
    esac
done
