#!/system/bin/sh
trap '' HUP
# 开机后给 Ubuntu 单独的局域网地址，并在该地址的 22 端口启动 sshd。
# 两套 tailscaled 都在这个挂载命名空间里拉起。没有 systemd，重启后靠这个脚本。
# 官方网必须 userspace：再开一张 tun 会删掉 5210 那组规则，并和 Headscale 抢 table 52。
# 官方 22 端口交给 Tailscale SSH，不要再 serve 转到 OpenSSH。
IP=192.168.50.161
PREFIX=24
UB=/data/ubuntu/rootfs
BB=/data/adb/magisk/busybox
if [ -f /data/adb/y700-ubuntu/disabled ]; then
  exit 0
fi
# 从平板应用或 ssh 拉起时，切到 init 的挂载命名空间，避免再起一套 sshd。
if [ -z "${Y700_UBUNTU_NS:-}" ]; then
  init_mnt=$(readlink /proc/1/ns/mnt)
  self_mnt=$(readlink /proc/self/ns/mnt)
  if [ -n "$init_mnt" ] && [ "$init_mnt" != "$self_mnt" ]; then
    export Y700_UBUNTU_NS=1
    exec "$BB" nsenter -t 1 -m -- /system/bin/sh "$0" "$@"
  fi
fi
HS_SOCK=/run/tailscale/tailscaled.sock
SAAS_SOCK=/run/tailscale/tailscaled-saas.sock

i=0
while [ "$(getprop sys.boot_completed)" != "1" ]; do
  i=$((i + 1))
  [ "$i" -gt 60 ] && exit 0
  sleep 2
done

fix_dns() {
  if [ -L "$UB/etc/resolv.conf" ] || [ ! -s "$UB/etc/resolv.conf" ]; then
    rm -f "$UB/etc/resolv.conf"
    printf "nameserver 192.168.50.1\nnameserver 223.5.5.5\n" > "$UB/etc/resolv.conf"
  fi
}

mounts() {
  mounted() { grep -q " $1 " /proc/mounts; }
  mounted "$UB/proc" || $BB mount -t proc proc "$UB/proc"
  mounted "$UB/sys" || $BB mount -t sysfs sysfs "$UB/sys"
  mounted "$UB/dev" || $BB mount --bind /dev "$UB/dev"
  mounted "$UB/dev/pts" || $BB mount -t devpts -o gid=5,mode=620 devpts "$UB/dev/pts"
  [ -d "$UB/dev/shm" ] || mkdir -p "$UB/dev/shm"
  mounted "$UB/dev/shm" || $BB mount -t tmpfs tmpfs "$UB/dev/shm"
  mkdir -p "$UB/run/sshd" "$UB/run/tailscale" "$UB/var/lib/tailscale" "$UB/var/lib/tailscale-saas" "$UB/var/log"
}

# Android 把 fwmark 0x80000 送进没有默认路由的 main，再 unreachable。
# tailscaled 用这个 mark 发控制流量，所以先改走 wlan 的 table 1015。
mark_route() {
  $BB ip rule del pref 5200 2>/dev/null
  $BB ip rule add pref 5200 fwmark 0x80000/0xff0000 lookup 1015
}

# 只认 comm 是 tailscaled 的进程，避免把脚本自己的命令行算进去。
daemon_up() {
  needle=$1
  for d in /proc/[0-9]*; do
    [ -r "$d/comm" ] || continue
    read comm < "$d/comm" || continue
    [ "$comm" = "tailscaled" ] || continue
    if "$BB" tr '\000' ' ' < "$d/cmdline" 2>/dev/null | "$BB" grep -q "$needle"; then
      return 0
    fi
  done
  return 1
}

start_headscale() {
  if daemon_up "$HS_SOCK"; then
    return 0
  fi
  $BB setsid /system/bin/chroot "$UB" /usr/sbin/tailscaled \
    --state=/var/lib/tailscale/tailscaled.state \
    --socket="$HS_SOCK" \
    >>"$UB/var/log/tailscaled.log" 2>&1 < /dev/null &
}

start_saas() {
  if daemon_up "$SAAS_SOCK"; then
    return 0
  fi
  $BB setsid /system/bin/chroot "$UB" /usr/sbin/tailscaled \
    --tun=userspace-networking \
    --state=/var/lib/tailscale-saas/tailscaled.state \
    --socket="$SAAS_SOCK" \
    --port=41642 \
    >>"$UB/var/log/tailscaled-saas.log" 2>&1 < /dev/null &
}

ensure_ssh() {
  daemon_up "$SAAS_SOCK" || return 0
  st=$(/system/bin/chroot "$UB" /usr/bin/tailscale --socket="$SAAS_SOCK" serve status 2>/dev/null)
  if echo "$st" | $BB grep -q "tcp://$IP:22"; then
    /system/bin/chroot "$UB" /usr/bin/tailscale --socket="$SAAS_SOCK" serve --tcp=22 off >/dev/null 2>&1
  fi
  prefs=$(/system/bin/chroot "$UB" /usr/bin/tailscale --socket="$SAAS_SOCK" debug prefs 2>/dev/null)
  echo "$prefs" | $BB grep -q '"RunSSH": true' && return 0
  /system/bin/chroot "$UB" /usr/bin/tailscale --socket="$SAAS_SOCK" set --ssh >/dev/null 2>&1
}

write_listen() {
  tsip=$1
  conf="$UB/etc/ssh/sshd_config.d/lan.conf"
  if [ -n "$tsip" ]; then
    printf 'ListenAddress %s\nListenAddress %s\n' "$IP" "$tsip" > "$conf"
  else
    printf 'ListenAddress %s\n' "$IP" > "$conf"
  fi
}

start_sshd() {
  /system/bin/chroot "$UB" /usr/sbin/sshd
}

while true; do
  if ip -4 addr show dev wlan0 2>/dev/null | grep -q "inet "; then
    ip -4 addr show dev wlan0 | grep -q "inet $IP/" || $BB ip addr add "$IP/$PREFIX" dev wlan0 2>/dev/null
  fi
  if ip -4 addr show dev wlan0 2>/dev/null | grep -q "inet $IP/"; then
    mounts
    fix_dns
    mark_route
    start_headscale
    start_saas
    ensure_ssh
    tsip=$(/system/bin/chroot "$UB" /usr/bin/tailscale --socket="$HS_SOCK" ip -4 2>/dev/null | $BB head -n 1)
    if ! ss -lnt 2>/dev/null | grep -q "$IP:22"; then
      write_listen "$tsip"
      start_sshd
    elif [ -n "$tsip" ] && ! ss -lnt 2>/dev/null | grep -q "$tsip:22"; then
      write_listen "$tsip"
      $BB pkill -x sshd
      sleep 1
      start_sshd
    fi
  fi
  sleep 20
done
