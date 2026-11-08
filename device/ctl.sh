#!/system/bin/sh
# Ubuntu chroot 的状态、开启、关闭。只动 /data/ubuntu 里的进程和这个守护脚本。
# 不碰 Android 的 dropbear、adbd，也不碰别的 tun。
BB=/data/adb/magisk/busybox
UB=/data/ubuntu/rootfs
DIR=/data/adb/y700-ubuntu
KEEPER=/data/adb/service.d/y700-ubuntu-lan.sh
DISABLED=$DIR/disabled
HS_SOCK=/run/tailscale/tailscaled.sock
SAAS_SOCK=/run/tailscale/tailscaled-saas.sock
mkdir -p "$DIR"

# toybox tr 的 '\0' 会变成空参数，然后空转占满一核。只走 busybox。
read_cmdline() {
  "$BB" tr '\000' ' ' < "$1" 2>/dev/null || true
}

action=${1:-status}

keeper_pid() {
  for d in /proc/[0-9]*; do
    pid=${d#/proc/}
    [ "$pid" = "$$" ] && continue
    [ -r "$d/cmdline" ] || continue
    cmd=$(read_cmdline "$d/cmdline") || continue
    case "$cmd" in
      "/system/bin/sh /data/adb/service.d/y700-ubuntu-lan.sh"*)
        echo "$pid"
        return 0
        ;;
    esac
  done
  return 1
}

kill_keepers() {
  for d in /proc/[0-9]*; do
    pid=${d#/proc/}
    [ "$pid" = "$$" ] && continue
    [ -r "$d/cmdline" ] || continue
    cmd=$(read_cmdline "$d/cmdline") || continue
    case "$cmd" in
      "/system/bin/sh /data/adb/service.d/y700-ubuntu-lan.sh"*)
        kill -9 "$pid" 2>/dev/null
        ;;
    esac
  done
}

kill_ubuntu() {
  sig=$1
  for d in /proc/[0-9]*; do
    pid=${d#/proc/}
    [ "$pid" = "$$" ] && continue
    exe=$(readlink "$d/exe" 2>/dev/null) || continue
    case "$exe" in
      "$UB"/*) kill "-$sig" "$pid" 2>/dev/null ;;
    esac
  done
}

do_start() {
  rm -f "$DISABLED"
  : > "$DIR/keeper.log"
  if ! keeper_pid >/dev/null; then
    $BB setsid $BB nsenter -t 1 -m -- /system/bin/sh "$KEEPER" \
      >>"$DIR/keeper.log" 2>&1 </dev/null &
  fi
}

do_stop() {
  : > "$DISABLED"
  kill_keepers
  kill_ubuntu TERM
  sleep 1
  kill_keepers
  kill_ubuntu KILL
  sleep 1
  $BB ip addr del 192.168.50.161/24 dev wlan0 2>/dev/null
  $BB ip rule del pref 5200 2>/dev/null
  if ! pidof tailscaled >/dev/null 2>&1; then
    $BB ip link del tailscale0 2>/dev/null
  fi
}

role_of() {
  exe=$1
  cmdl=$2
  base=${exe##*/}
  case "$base" in
    tailscaled)
      case "$cmdl" in
        *saas*) echo tailscale ;;
        *) echo headscale ;;
      esac
      ;;
    sshd) echo sshd ;;
    sshd-session) echo session ;;
    bash|sh) echo shell ;;
    *)
      case "$exe" in
        */.grok/*|*grok*) echo grok ;;
        *) echo other ;;
      esac
      ;;
  esac
}

print_status() {
  procs="["
  pfirst=1
  pss_sum=0
  ubuntu_n=0
  saas_up=0
  hs_up=0
  for d in /proc/[0-9]*; do
    [ -r "$d/exe" ] || continue
    exe=$(readlink "$d/exe") || continue
    case "$exe" in
      "$UB"/*) ;;
      *) continue ;;
    esac
    pid=${d#/proc/}
    cmdl=$(read_cmdline "$d/cmdline")
    role=$(role_of "$exe" "$cmdl")
    case "$role" in
      tailscale) saas_up=1 ;;
      headscale) hs_up=1 ;;
    esac
    rss=$($BB awk '/^VmRSS:/{print $2; exit}' "$d/status" 2>/dev/null)
    pss=$($BB awk '/^Pss:/{print $2; exit}' "$d/smaps_rollup" 2>/dev/null)
    [ -n "$rss" ] || rss=0
    [ -n "$pss" ] || pss=$rss
    pss_sum=$((pss_sum + pss))
    ubuntu_n=$((ubuntu_n + 1))
    [ "$pfirst" -eq 0 ] && procs="$procs,"
    pfirst=0
    procs="$procs{\"role\":\"$role\",\"pid\":$pid,\"rss_kb\":$rss,\"pss_kb\":$pss}"
  done

  kpid=$(keeper_pid || true)
  if [ -n "$kpid" ]; then
    rss=$($BB awk '/^VmRSS:/{print $2; exit}' /proc/$kpid/status 2>/dev/null)
    pss=$($BB awk '/^Pss:/{print $2; exit}' /proc/$kpid/smaps_rollup 2>/dev/null)
    [ -n "$rss" ] || rss=0
    [ -n "$pss" ] || pss=$rss
    pss_sum=$((pss_sum + pss))
    [ "$pfirst" -eq 0 ] && procs="$procs,"
    pfirst=0
    procs="$procs{\"role\":\"keeper\",\"pid\":$kpid,\"rss_kb\":$rss,\"pss_kb\":$pss}"
  fi
  procs="$procs]"

  if [ "$ubuntu_n" -gt 0 ]; then
    state=on
  elif [ -n "$kpid" ]; then
    state=starting
  else
    state=off
  fi

  if [ -f "$DISABLED" ]; then
    disabled=true
  else
    disabled=false
  fi

  pretty=$($BB sed -n 's/^PRETTY_NAME="\(.*\)"/\1/p' "$UB/etc/os-release" 2>/dev/null)
  [ -n "$pretty" ] || pretty="Ubuntu"
  mem_total=$($BB awk '/^MemTotal:/{print $2; exit}' /proc/meminfo)
  mem_avail=$($BB awk '/^MemAvailable:/{print $2; exit}' /proc/meminfo)
  [ -n "$mem_total" ] || mem_total=0
  [ -n "$mem_avail" ] || mem_avail=0

  lan=$($BB ip -4 addr show dev wlan0 2>/dev/null | $BB awk '/192\.168\.50\.161\//{print $2; exit}')
  if [ -n "$lan" ]; then
    lan_up=true
  else
    lan_up=false
    lan=""
  fi

  hs_ip=""
  rx=0
  tx=0
  hs_link=false
  if $BB ip link show tailscale0 >/dev/null 2>&1; then
    hs_link=true
    hs_ip=$($BB ip -4 addr show dev tailscale0 2>/dev/null | $BB awk '/inet /{print $2; exit}')
    hs_ip=${hs_ip%/*}
    set -- $($BB awk '$1 ~ /tailscale0/ {print $2, $10; exit}' /proc/net/dev)
    [ -n "$1" ] && rx=$1
    [ -n "$2" ] && tx=$2
  fi
  if [ -z "$hs_ip" ] && [ "$hs_up" -eq 1 ]; then
    hs_ip=$($BB timeout 2 /system/bin/chroot "$UB" /usr/bin/tailscale --socket="$HS_SOCK" ip -4 2>/dev/null | $BB head -n 1)
  fi

  saas_ip=""
  if [ "$saas_up" -eq 1 ]; then
    saas_ip=$($BB timeout 2 /system/bin/chroot "$UB" /usr/bin/tailscale --socket="$SAAS_SOCK" ip -4 2>/dev/null | $BB head -n 1)
  fi

  listeners="["
  lfirst=1
  ss -lnt 2>/dev/null | $BB awk '$1=="LISTEN" && $4 ~ /:22$/ {print $4}' > "$DIR/listeners.tmp"
  while read -r addr; do
    [ -n "$addr" ] || continue
    [ "$lfirst" -eq 0 ] && listeners="$listeners,"
    lfirst=0
    listeners="$listeners\"$addr\""
  done < "$DIR/listeners.tmp"
  listeners="$listeners]"

  if [ -n "$hs_ip" ]; then hs_has=true; else hs_has=false; fi
  if [ -n "$saas_ip" ]; then saas_has=true; else saas_has=false; fi

  printf '{"ok":true,"action":"%s","state":"%s","disabled":%s,"pretty":"%s","pss_kb":%s,"mem_total_kb":%s,"mem_available_kb":%s,"procs":%s,"lan":{"up":%s,"addr":"%s"},"headscale":{"up":%s,"ip":"%s","rx":%s,"tx":%s},"tailscale":{"up":%s,"ip":"%s"},"listeners":%s}\n' \
    "$action" "$state" "$disabled" "$pretty" "$pss_sum" "$mem_total" "$mem_avail" "$procs" \
    "$lan_up" "$lan" "$hs_has" "$hs_ip" "$rx" "$tx" "$saas_has" "$saas_ip" "$listeners"
}

case "$action" in
  start) do_start ;;
  stop) do_stop ;;
  status) ;;
  *)
    printf '{"ok":false,"error":"unknown"}\n'
    exit 1
    ;;
esac
print_status
