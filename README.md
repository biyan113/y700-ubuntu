# Ubuntu

联想拯救者平板 Legion Y700 上的 Ubuntu 开关。桌面名称是「Ubuntu」，包名 `app.y700.ubuntu`，版本 1.0。

这个应用不安装系统。它通过 Magisk 的 `su` 调用平板上的 `/data/adb/y700-ubuntu/ctl.sh`，打开或关掉已经放在 `/data/ubuntu/rootfs` 里的 chroot。关掉只停服务，不删系统文件。

## 使用

安装后，在 Magisk 里允许「Ubuntu」使用 root。`su` 会依次试 `/product/bin/su`、`/debug_ramdisk/su`、`su`。

打开桌面图标「Ubuntu」。

- 状态是「已关闭」「正在启动」或「运行中」。旁边写着开机后会不会自动打开。
- 「开启 Ubuntu」去掉停用标记，并拉起守护脚本。
- 「关闭 Ubuntu」会先确认。确认后写入停用标记，停掉守护脚本和 chroot 里的进程。正在用的终端会断开。系统文件还在。
- 运行中可以「重启」。先关再开，终端会断一下。
- 「内存」显示这套服务占了多少，平板还剩多少、一共多少，以及各进程。角色有 Headscale、Tailscale、SSH、终端、Grok、守护。
- 「网络」在开启后显示局域网地址、Headscale、Tailscale，以及谁在听 22 端口。局域网地址是 `192.168.50.161/24`，加在平板 Wi-Fi 上。Headscale 用内核网卡，Tailscale 用用户态网络。
- 「刷新」再读一次状态。点了开启或关闭之后，页面大约每两秒再读，最多十五次，直到状态跟上。

开机要不要自动打开，跟这个开关走。开启会删掉 `/data/adb/y700-ubuntu/disabled`。关闭会把这个文件写回去，守护脚本一看到就退出。

## 平板上要有

- 已安装 Magisk，并且允许「Ubuntu」使用 root。
- Magisk 自带的 busybox：`/data/adb/magisk/busybox`。读进程命令行必须用它。系统自带的 `tr` 遇到空字节会空转，占满一核。
- Ubuntu chroot 在 `/data/ubuntu/rootfs`。
- 控制脚本 `/data/adb/y700-ubuntu/ctl.sh`，内容是仓库里的 `device/ctl.sh`。
- 守护脚本 `/data/adb/service.d/y700-ubuntu-lan.sh`，内容是仓库里的 `device/y700-ubuntu-lan.sh`。没有 systemd，开机后靠它补挂载、地址和 sshd。
- Android 8.0 及以上。

## 自己编译

不需要 Gradle。Mac 上 Android SDK 在 `~/Library/Android/sdk` 时直接：

```sh
./build.sh
```

也可以设置 `ANDROID_HOME`。SDK 里要有 `platforms;android-34` 和 `build-tools;36.0.0`，JDK 用 17（源码按 Java 11 编译）。

编完得到 `y700-ubuntu.apk`。签名文件是仓库根目录的 `debug.keystore`，没有就自动生成。库密码和密钥密码都是 `android`。keystore、apk 和 `build/` 不进 git。

```sh
adb install -r y700-ubuntu.apk
```

本机这把 `debug.keystore` 只给自己编译用。GitHub Release 用的是另一把钥匙，两边不能互相覆盖安装。

## 发布

推送 `v*` 标签，或在 Actions 里手动跑 Release。工作流在 Ubuntu 上编译，把 `y700-ubuntu.apk` 传到 GitHub Release。

签名用仓库 secret `KEYSTORE_BASE64`（keystore 的 base64）。这样每次发布的签名一致，平板上可以覆盖安装。
