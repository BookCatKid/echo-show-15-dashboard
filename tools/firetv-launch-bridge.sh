#!/system/bin/sh
# Fixed-action bridge for the owner-managed Echo. Never execute request contents.
umask 077
dir=/data/user/0/com.bookcatkid.launcher/files
request="$dir/firetv-launch-request"
while true; do
    if [ -f "$request" ] && [ ! -L "$request" ]; then
        # Consume the fixed request before making the launch.
        if /system/bin/rm -f "$request"; then
            /system/bin/runcon u:r:shell:s0 /system/bin/am start -a com.amazon.tv.launcher.SYSTEM_UI_INTENT -f 0x10000000
        fi
    fi
    sleep 1
done
