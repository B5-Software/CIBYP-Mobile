# Could I Be Your Partner · Android / Wear OS

[![Android and Wear OS](https://github.com/B5-Software/CIBYP-Mobile/actions/workflows/android.yml/badge.svg)](https://github.com/B5-Software/CIBYP-Mobile/actions/workflows/android.yml)

原生 Kotlin / Jetpack Compose 客户端，连接 [CIBYP 桌面共享后台](https://github.com/B5-Software/Could-I-Be-Your-Partner)。手机使用 Material Design 3、预测性返回和桌面小组件；手表使用 Wear Compose Material 3。连接后两端实时同步所选电脑的强调色、背景色和深浅色，未选择电脑时使用系统主题。任务、文件和模型调用在原电脑执行。

## 连接

1. 电脑设置 → WebUI：设置访问密码，可选二次认证，启动 Tor。
2. 手机添加设备，填写电脑的 v3 onion 地址、访问密码与验证码。访问令牌用 Android Keystore 加密保存，密码不保存。
3. 手机可设置可选的 obfs4 / snowflake / webtunnel / meek 网桥，或点击“使用内置 meek 网桥”后保存。内置参数来自 Tor Expert Bundle 15.0.24；`meek` 自动转换为 `meek_lite`，保留 `url` / `front` / `targets`。桌面和手机分别配置自己的网桥。网桥可用性依赖网络，不能保证在所有网络中可连接。
4. 系统配对 Wear OS 手表，安装手表 APK，在手机中允许该手表。手表共享多个设备，通过手机转发命令，无需扫码；手机须保持连接服务运行。

桌面后台推荐 1.9.0-alpha.24 或更新版本。支持新建/选择会话、查看完整手机消息、发送任务、停止任务、人工审批、共享待办和订阅用量。头像与头像框从电脑共享设置读取，显示在消息气泡外；未设置头像时使用默认图标。附件使用独立文件卡片，可从手机上传、预览图片、下载到系统选择的目录，聊天记录保留附件元数据。

手机 `/compact` 使用同一后台的上下文压缩，显示进行中、节省量和失败状态。完成提示只短期显示，渐显渐隐与滚动遵守电脑的全局动画设置。完整聊天记录与持久化的压缩上下文独立保存。手机聊天中的 `/update` 与手表 `/update` 按钮下载电脑新版，下载完提醒；重启安装始终手动二次确认，运行任务时后台拒绝安装。

允许 HTTPS 和私有局域网地址用于本地连接。onion 请求只经过内置 Tor SOCKS，不会因 Tor 失败而回退直连。HTTP 重定向关闭。后台令牌到期后须重新登录，可在电脑修改访问密码撤销已有会话。

手表不接收访问令牌或模型配置。手表仅展示最近消息片段，完整输出在手机/电脑查看；敏感审批先在手机阅读完整请求。语音输入使用系统识别服务；需要手表安装可用的语音识别器。

手机完整显示后端返回的可读推理，区分全文与摘要；不会逐 token 请求刷新，也不会显示加密签名。手表仅展示短摘要预览，最多 8 条消息、40 个会话，单次传输限制在 48 KB 内。前台聊天请求结束后等待 10 秒再刷新，其余页等待 30 秒；离开前台停止轮询，刷新串行执行，内容未变化时不更新 UI。

最低设备目标为升级至 Wear OS 3 的 TicWatch Pro 3 和 Galaxy Watch 5，推荐 Galaxy Watch 7。发布手表 APK 启用 R8、资源裁剪及 ProfileInstaller，当前约 2 MB。已在 1 GB / 双核 Wear OS 模拟器上验证原生 UI 和主题热切换；尚无上述实机帧率、耗电及真实配对数据，不能据此承诺实机流畅度。

## 构建

JDK 21、Android SDK 36 / 37.0、Gradle 9.3.1。SDK 37.0 用于 Tor Android 依赖，运行最低 Android 8 / Wear OS 3。

```sh
./gradlew :core:testDebugUnitTest :phone:assembleDebug :wear:assembleRelease :phone:lintDebug :wear:lintRelease
```

`phone` 与 `wear` 使用相同 application ID，必须以同一证书签名才能使用 Data Layer。CI 生成手机 debug APK 与经过裁剪的手表 release APK，使用固定公共开发签名以便 MVP 升级；release 优化不代表使用了生产签名。生产发布前须配置私有签名、权限审核与实机验证。

## 当前限制

这是远程控制 MVP，不包含手机本地 Agent 或手机 Code-OSS 工作台。桌面任务即使手机断开仍继续；手表需通过手机连接。桌面小组件提供当前连接设备与打开 App 的快捷入口。后台连接使用显式前台服务，可在通知中断开；系统省电策略仍可能影响长期连接。

Tor runtime：Guardian Project tor-android；可插拔传输：IPtProxy（obfs4、Snowflake、WebTunnel、meek_lite）。本地原生测试检查 meek 的 SOCKS 握手；Tor / 网桥及 Wear Data Layer 仍需要跨设备、受限网络实机测试，编译或本地监听成功不代表网络可达性验证。

## English

Native Android and Wear OS companions for CIBYP. Connect to your own desktop backend over bundled Tor or private LAN/HTTPS. Pair and explicitly approve watches on the phone; credentials stay on the phone. Chat, stop, approvals, persistent todos, usage and desktop `/update` share the existing backend. The desktop keeps executing tasks when a client disconnects. See CI artifacts for development APKs; phone and watch require the same signing certificate.

GPL-3.0-or-later. Third-party runtime notices are in [THIRD_PARTY.md](THIRD_PARTY.md).
