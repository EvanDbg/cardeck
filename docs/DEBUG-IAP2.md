# iAP2 独立诊断包

本分支的 `0.1.32.1` 调试包包含 iAP2 生命周期修复、阶段日志，以及已有的「入网后连接媒体蓝牙」功能。
首次 Detect-only 握手停滞仍需实车确认；此包的发布不代表已通过目标车机连接或音频稳定性验证。

## 选择安装包

| 构建方式 | 包名 | 应用标签 | 当前版本名 |
|---|---|---|---|
| 默认 `standardDebug` | `io.github.shaonialife.cardeck` | CarDeck 车机投屏 | `0.1.32.1-debug` |
| `standardDebug` 加 `-Pcardeck.parallelDebug=true` | `io.github.shaonialife.cardeck.iap2debug` | CarDeck iAP2调试 | `0.1.32.1-iap2debug` |

前一版已发布 Debug APK 与本轮 APK 使用不同证书，**原包名 APK 无法覆盖前一版安装**。
使用独立诊断包可以保留旧版及其数据，无需卸载旧版。两包的应用数据、设置和权限彼此独立。
签名指纹和 APK SHA-256 以对应 Release 记录为准；没有把签名私钥或密码放入仓库。

## 安装与连接

1. 在当前运行的 CarDeck 中先断开会话，释放虚拟屏。关闭它的「上车自动连接」，再到 Android 应用信息中强行停止该实例。
2. 安装并打开「CarDeck iAP2调试」。保持手机屏幕亮起，完成系统安装与权限弹窗。
3. 为独立包单独授予 Shizuku 权限；按应用提示完成蓝牙、附近设备、悬浮窗及需要的通知访问权限，并重新选择目标车机。
4. 测试期间只运行一个 CarDeck 实例。诊断时也关闭「上车自动连接」，使用手动启动，避免两个安装同时响应车机蓝牙事件。
5. 切回另一安装前，先在当前实例断开并强行停止，再打开另一实例。仅从最近任务划掉应用不能代替断开与停止。

若使用「入网后连接媒体蓝牙」，在诊断包内重新启用该杂项开关，并关闭首页「车机侧声音」。
媒体蓝牙连接成功后，仍需在车机上选择蓝牙音源并确认实际播放。

## 手动导出日志

复现后，在**本次使用的诊断包**主页点击导出日志按钮。文件位于 `下载/CarDeck/CarDeck-log-<时间>.txt`；
弹窗会显示实际路径和大小，并可打开或分享文件。默认只导出本次进程启动后的日志，长按按钮可导出历史日志。
请同时说明使用的是哪个包、连接/取消的顺序及大致时间。日志可能含配对和网络信息，提供给排查者时请用私下渠道。

本包使用 standard 源集，没有远程调试桥，不会通过该桥自动上传日志。

## 从源码重建

保持 `appVersionName = "0.1.32.1"`，依次构建并保存两个产物；两种构建使用相同的 Gradle 输出路径，后一次会覆盖前一次。

```bash
mkdir -p build/outputs/iap2-debug
./gradlew :app:assembleStandardDebug
cp app/build/outputs/apk/standard/debug/app-standard-debug.apk build/outputs/iap2-debug/CarDeck-0.1.32.1-debug.apk
./gradlew :app:assembleStandardDebug -Pcardeck.parallelDebug=true
cp app/build/outputs/apk/standard/debug/app-standard-debug.apk build/outputs/iap2-debug/CarDeck-0.1.32.1-iap2debug.apk
```

`cardeck.parallelDebug` 只支持 `standardDebug` 诊断构建，不与 `assembleRemoteDebug` 或 remote flavor 混用。
不要把此属性固化到 `gradle.properties`；省略属性或设为 `false` 即恢复默认 Debug 包名、版本后缀和标签。
该属性不改变 release buildType、版本号计算或现有签名配置，也不需要 `cardeck.buildNumber`。

有 ADB 时，先按上文在应用内断开，再执行强行停止和安装。这里使用独立诊断包路径：

```bash
adb -s <serial> shell am force-stop io.github.shaonialife.cardeck
adb -s <serial> shell am force-stop io.github.shaonialife.cardeck.iap2debug
adb -s <serial> install -r build/outputs/iap2-debug/CarDeck-0.1.32.1-iap2debug.apk
```

独立包的 `run-as` 目标是 `io.github.shaonialife.cardeck.iap2debug`。发布前应分别核对两个 APK 的包名、版本、应用标签、
Shizuku authority、签名和 SHA-256，并确认独立包中没有 remote 桥组件。
