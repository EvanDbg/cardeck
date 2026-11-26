# 2026-11-26：iAP2 启动停滞与会话清理调查

## 范围与结论

- 现场版本为 **CarDeck 0.1.31.0**，源码/二进制基准为 [v0.1.31 的提交][commit] `38a3f2f438d7e48272d3cf42fcecda725f98c5bf`。
- 手机为 Pixel 7 Pro、Android 17（SDK 37）；依据附件 `CarDeck-log-20261126-134013.txt`，共 336 行，仅本次进程启动。
- 用户已确认同场操作包含 **Hypercar 成功连接 → 断开 → 切换 CarDeck 连接失败**。这项对照已经完成，无需重复要求。
- Hypercar 成功及操作顺序来自用户实测陈述；CarDeck 日志没有 Hypercar 的协议记录。HFP/A2DP 快照不能替代该记录。
- 本轮发现并修复可确定的宿主生命周期缺陷；**尚未证明现场第二轮触发了该写异常路径，也未完成实车验证**。
- 第一轮 Detect-only 握手停滞与后续清理卡死必须分别解释；本补丁不宣称已解决前者。

## 现场时间线

以下时间沿用日志主区的本地时间；L 为附件行号。末尾 native 日志使用 UTC，同事件相差八小时，不能重复计为新轮次。
第 1 轮启动于 13:12:45.137（L15）。共 17 次手动启动，17 次最终建立 RFCOMM；部分连接失败后重试成功。

| 轮次 | RFCOMM 建立（行号） | 用户取消（行号） | 建立至取消/秒 |
|---:|---|---|---:|
| 1 | 13:12:48.056（L26） | 13:13:37.722（L32） | 49.666 |
| 2 | 13:13:45.131（L54） | 13:14:06.896（L60） | 21.765 |
| 3 | 13:14:24.222（L76） | 13:16:36.862（L77） | 132.640 |
| 4 | 13:16:44.421（L93） | 13:18:47.969（L94） | 123.548 |
| 5 | 13:19:02.676（L110） | 13:20:32.280（L111） | 89.604 |
| 6 | 13:21:22.168（L127） | 13:21:39.500（L128） | 17.332 |
| 7 | 13:24:21.122（L145） | 13:24:36.639（L146） | 15.517 |
| 8 | 13:24:55.200（L163） | 13:25:25.415（L164） | 30.215 |
| 9 | 13:26:10.897（L182） | 13:26:25.489（L183） | 14.592 |
| 10 | 13:26:41.147（L198） | 13:26:46.880（L199） | 5.733 |
| 11 | 13:26:51.355（L216） | 13:27:12.849（L217） | 21.494 |
| 12 | 13:27:16.597（L231） | 13:27:37.255（L232） | 20.658 |
| 13 | 13:27:50.247（L246） | 13:28:12.617（L247） | 22.370 |
| 14 | 13:28:46.498（L264） | 13:29:10.483（L265） | 23.985 |
| 15 | 13:29:25.652（L281） | 13:29:51.466（L282） | 25.814 |
| 16 | 13:29:53.382（L296） | 13:30:02.318（L297） | 8.936 |
| 17 | 13:30:44.702（L315） | 13:30:54.839（L316） | 10.137 |

第 1 轮在 13:12:48.067 创建 native session（L28），13:12:48.095 出现 `phase -> 1`（L31）。
用户取消后才出现“链路未就绪即终止”（L34）；`LINK closed`、destroy、会话结束、客户端退出均有记录（L35–41）。
第 2 轮在 13:13:45.135 创建 session（L56），13:13:45.149 即记录“链路未就绪即终止”（L59）：距 RFCOMM **18 ms**，早于用户取消 **21.747 秒**。
此后没有该轮 destroy、`LINK closed` 或客户端真正退出记录。第 3–17 轮均无新增 native create；其中两轮等待超过两分钟。
全日志未记录 CarDeck 进入 iAP2 ready、识别/MFi、Wi-Fi 入网、AirPlay 或视频阶段。ctrl 监听/广播启动不等于 CarPlay 已建立。
Shizuku 已就绪；存在 HFP/A2DP 未连接仍失败的轮次。此次证据不支持优先归因于声音、投屏授权或旧版 60 秒问题。

## 代码与二进制证据

[基线发送端会话][sender] 调用 `awaitReady(0L)`；[通道实现][channel] 的 0 表示无限等待。
该等待提前返回 false 的路径是宿主终止标志或线程中断；[BtSession][bt-session] 没有另套完成时限。
客户端残留的 90 秒常量未传入当前调用链，不能把 18 ms 终止解释为该超时。
第一轮客户端已于 13:13:37.732 退出（L41），第二轮 13:13:39.201 才重新启动（L48）；新线程不继承旧线程的 interrupt 标志。

静态核验对象为该提交内的 [ARM64 `libcardeck_protocol.so`][native]，大小 1,608,568 字节。
SHA-256：`4d3f034c485f7b3125021dc7f03a24cb76552c83cf7806dfac5c46fe57f12806`。
使用 ELF 导出、动态重定位、函数边界和 AArch64 反汇编交叉核验；公开 checkout 未包含对应 Rust 源码。

1. Create、Destroy、BtAttached、Feed、Tick、LinkReady 等入口取得同一把进程级、非递归 futex 锁。
2. Attach/Feed/Tick/LinkReady 在释放该锁之前排空出站队列，经 JNI `CallVoidMethod` **同步**调用 Kotlin `TransportSender.send`。
3. 旧 Kotlin `send` 在 `stream.write` 抛 `IOException` 后同步进入 `finish`；先置 `terminated=true`，再调用 native Destroy。
4. 此时同一线程重入它尚未释放的原生锁，必然自锁。Destroy 无法返回，外层 native 调用也无法退栈解锁。
5. 另有跨线程锁反转：关闭线程持 Kotlin 锁等待 native 锁，native 回调持 native 锁等待 Kotlin 锁。
6. 阻塞写还可能持住发送锁，使关闭线程无法关闭传输来解除该写阻塞；仅把 Destroy 异步化不足以覆盖全部问题。

Create 的日志在取锁、插入和解锁之后；Destroy 的日志在取锁和清理之后。缺失日志不等于相关函数从未被调用。
宿主先置终止，能解释为何先出现“未就绪终止”；[旧客户端][client] 又在 channel 清理前清空 activeChannel，后续“已停止”不保证旧线程已退出。
后续 channel 构造位于 activeChannel 登记和 try/finally 之前；若 Create 等原生锁，新的 RFCOMM 仍可先建立，取消却拿不到未构造完成的 channel。

**证据边界：**以上确定了条件死锁的代码路径；现场日志没有写异常、写返回、线程栈或锁等待现场，不能断言第二轮一定走的是哪一种卡死路径。
“第二轮快速终止、无清理完成、后续无 create”与该缺陷高度一致，但不是完整的现场运行时证明。

## 第一轮 Detect-only 握手仍独立未解

该 native 库的新建 attach 路径只产生一次 Detect；在**没有入站字节或其它状态变化**时，初始计时器和 SYN 响应重传槽均未武装。
在这个有界条件下，重复 Tick 不会主动发 SYN，也不会重发 Detect；20 ms Tick 返回值只是轮询间隔。
`phase -> 1` 是原始状态 3/4 的折叠诊断值，不表示 ready 或 Phase-1 配置过程完成。
第一轮出现该日志，说明 Tick 曾取得同一把原生锁，首次 attach 的同步出站回调已经退栈；**仍不证明车机收到、接受或解析了 Detect**。
二进制存在接收合法对端 SYN、发送 SYN/ACK、再由对端 ACK 进入 ready 的路径，但不等于跳过 attach 已有完整兼容性保证。
三个实现的启动方式不同，不能混成一项“补发 SYN”修复：

| 所检查的实现 | 启动策略 |
|---|---|
| [CarDeck 0.1.13 Kotlin 引擎][legacy-engine] | 主动发起 SYN，并安排协商重试 |
| CarDeck 0.1.31 附带 native 库 | 先发一次 Detect，无输入时不自行发起 SYN 或重发 Detect |
| [Hypercar 提交 b271dc1 的 RFCOMM 通道][hypercar] | `passive = true`，先等对端探测/协商输入 |

Hypercar 源码对照版本不等同于用户实装版本；成功连接事实来自用户已完成的测试。
本轮不绕过 native FSM 手写协议包；首次握手策略的兼容性须独立验证。

## 本地补丁范围

- `transport/Iap2LinkChannel.kt`：终止只触发一次；先记录原因并唤醒等待，不持发送/native 调用锁关闭传输；native 销毁另线程执行，与本通道其它 JNI 调用串行。
- 同一通道增加创建、attach、首次读写和销毁进入/返回诊断；`awaitReady` 通过宿主状态/latch 响应取消，不在等待者线程阻塞查询 native。
- `transport/Iap2NativeSession.kt`：增加 JNI 薄适配用于注入生命周期测试，不承载新的协议状态；原公开 channel 构造签名保留。
- `bt/Iap2BluetoothClient.kt`：按世代和对象身份登记资源；channel 使用 AtomicReference/CAS；构造纳入清理范围，构造后复查取消。
- 客户端旧轮只关闭自己的 socket/channel；stop/detach 在短锁中摘取资源、锁外关闭；端点、Wi-Fi、关闭回调投递前核对所有权。
- 增加 `Iap2LinkChannelLifecycleTest.kt`，并将已确认约束补入 `docs/knowledge/connection-iap2.md`。
- **没有修改 `.so`、native 协议启动策略或协议报文字节；截至首次本地验证时，尚未进行实车验证、安装、提交或发布。** 后续 fork 移植状态见下文。

客户端所有权竞态是源码可证明的预防性修复；不能倒推用户日志已经触发。已进入主控执行的回调不在本文件内撤回。

## 故障注入与首次本地验证（0.1.31 基线）

原版通道验证编译真实 Kotlin 通道/流代码，以非递归锁和同步回调模拟已核实的 native 边界；没有执行 Android `.so`。
测试用 latch 固定线程交错，不依赖随机重复；正常阻塞读作为对照，避免把所有 close 都描述为死锁。

| 原版通道场景 | 关闭返回 | 传输关闭 | Destroy 返回 | 后续新通道创建 |
|---|---|---|---|---|
| 回调内 write 抛 IOException | 否 | 否 | 否 | 否 |
| native/Kotlin 锁反转 | 否 | 否 | 否 | 否 |
| 写阻塞时取消 | 否 | 否 | 否 | 否 |
| 正常读阻塞时取消（对照） | 是 | 是 | 是 | 是 |

客户端验证编译真实 `Iap2BluetoothClient.kt` 和 `DuplexStream.kt`，只替换 Android/蓝牙及会话外部边界。

| 客户端固定交错 | 原版 | 修补版 |
|---|---|---|
| 旧 connect 在 stop→start 后迟归 | 误关新 socket | 通过 |
| 取消期间旧 channel 构造迟归 | 旧会话仍被登记和运行 | 通过 |
| 旧端点/Wi-Fi/关闭回调及 finally 迟归 | 旧回调进入主控 | 通过 |
| channel 构造抛 IOException | socket 未释放 | 通过 |
| 旧 stop 关闭阻塞时并发 start | 误关新资源 | 通过 |
| 旧配置文件操作迟归 | 再次登记旧 socket | 通过 |

客户端合计：**原版 0/6，修补版 6/6**；Kotlin 2.0.21 编译与客户端 `git diff --check` 通过。
通道新增的 JUnit 生命周期回归 **13/13 通过**，包含同步回调异常、阻塞读写取消、就绪后取消、并发销毁与迟到清理。
完整 `testStandardDebugUnitTest` **145 项通过，0 失败、0 错误、0 跳过**（包含上述新增 13 项；没有启用 quick 开关）。
`spotlessApply`、`:app:lintStandardDebug`、`:app:assembleStandardDebug` 均成功；`git diff --check` 通过。
验证使用 Kotlin 2.0.21、Gradle 8.10.2、OpenJDK 21.0.2、Android SDK 34；构建产物为标准 Debug 包，未安装到手机。
本仓库快照中的现有测试全部运行；这些测试不等于 ARM64 native/车机协议重放覆盖，不能据测试总数推断实车兼容性。
以上本地验证不能替代实际 ART/JNI、蓝牙栈及目标车机联调，也不能证明首次握手已经成功。

## Fork 移植与发布验证

2026-11-26，将上述补丁移植到 [EvanDbg/cardeck 的媒体蓝牙功能分支基线][fork-base]
`4229fbc1d1cee8907edc14be254878f7bbfd12c2`，工作分支为
`fix/iap2-lifecycle-debug-0.1.32.1`，应用版本调整为 `0.1.32.1`。

该基线已包含「入网后连接媒体蓝牙」功能。移植保留 `Iap2BluetoothClient` 的双参数
端点/Wi-Fi 回调，在核对当前会话所有权后仍将本轮 `BluetoothSocket` 传给主控；
知识文档中的媒体蓝牙条目也完整保留。通道生命周期修复不改变该功能的触发条件、
开关默认值或蓝牙音源选择行为。

直接应用补丁时，客户端回调块与知识文档尾部存在上下文冲突；三方合并后按上述语义
保留两侧改动。其它补丁文件干净应用。上一节的 145 项单元测试及 13 项生命周期测试
结果属于原始 0.1.31 工作树，不能替代此移植分支的复测。

移植分支已重新运行完整 `:app:testStandardDebugUnitTest`（没有 quick 开关）：
32 个测试类、145 项测试全部通过，0 失败、0 错误、0 跳过，其中生命周期回归为 13 项。
`spotlessApply` 和 `:app:lintStandardDebug` 成功；Lint 为 0 错误、235 警告、2 条信息，
不能将基线通过理解为没有存量警告。

两个 APK 均通过 `:app:assembleStandardDebug` 构建，独立包额外传
`-Pcardeck.parallelDebug=true`。用 `aapt` 和 `apksigner` 核对实际包名、版本、标签、
debuggable、Shizuku authority 和签名；standard Manifest 中没有远程调试桥组件。

| APK | applicationId | versionName | versionCode |
|---|---|---|---|
| `CarDeck-0.1.32.1-debug.apk` | `io.github.shaonialife.cardeck` | `0.1.32.1-debug` | `13200001` |
| `CarDeck-0.1.32.1-iap2debug.apk` | `io.github.shaonialife.cardeck.iap2debug` | `0.1.32.1-iap2debug` | `13200001` |

APK SHA-256：

```text
1384673ffb322ca6518baf51d98ae0954c4dcf5f5266b3dedfdee03f175eceb3  CarDeck-0.1.32.1-debug.apk
f553a0157e1a23e83737a5360ffac5447669ba3c21115ebf40005fd0f1dbd4e7  CarDeck-0.1.32.1-iap2debug.apk
```

两包使用同一测试证书，证书 SHA-256 为
`eb7df5fef1469c163a230bb4e14c953f13c62e90f2928ef9fc016761835decba`。
这与 `v0.1.32-debug` 的 `760e8a96ec944f0a50622ad21730bc08119b9b646d257123a4577e3a6acb8239`
不同，因此推荐独立包保留现有安装与数据；原包名 APK 不能覆盖旧签名版本。
两包中的 ARM64 协议库与原版相同，SHA-256 仍为
`4d3f034c485f7b3125021dc7f03a24cb76552c83cf7806dfac5c46fe57f12806`。
私钥不进入源码或公开发布附件。安装及日志导出见 `docs/DEBUG-IAP2.md`。

这些验证没有连接手机或目标车机；首次握手成功率和此前音频播放后断线的问题仍需实车确认。

## 不可变基线引用

[commit]: https://github.com/shaonialife/cardeck/commit/38a3f2f438d7e48272d3cf42fcecda725f98c5bf
[channel]: https://github.com/shaonialife/cardeck/blob/38a3f2f438d7e48272d3cf42fcecda725f98c5bf/app/src/main/java/io/github/shaonialife/cardeck/transport/Iap2LinkChannel.kt#L86-L257
[sender]: https://github.com/shaonialife/cardeck/blob/38a3f2f438d7e48272d3cf42fcecda725f98c5bf/app/src/main/java/io/github/shaonialife/cardeck/iap2/Iap2SenderSession.kt#L79-L125
[bt-session]: https://github.com/shaonialife/cardeck/blob/38a3f2f438d7e48272d3cf42fcecda725f98c5bf/app/src/main/java/io/github/shaonialife/cardeck/bt/BtSession.kt#L13-L38
[client]: https://github.com/shaonialife/cardeck/blob/38a3f2f438d7e48272d3cf42fcecda725f98c5bf/app/src/main/java/io/github/shaonialife/cardeck/bt/Iap2BluetoothClient.kt#L319-L383
[native]: https://github.com/shaonialife/cardeck/blob/38a3f2f438d7e48272d3cf42fcecda725f98c5bf/app/src/main/jniLibs/arm64-v8a/libcardeck_protocol.so
[legacy-engine]: https://github.com/shaonialife/cardeck/blob/09eebb0f2a51c961c101e8fb49134ca7769b2d94/app/src/main/java/io/github/shaonialife/cardeck/iap2/Iap2LinkEngine.kt
[hypercar]: https://github.com/fw867/hypercar/blob/b271dc1b74adf861a4b6823090cfc3d7dd502641/app/src/main/java/io/github/hypercar/iap2/Iap2Bluetooth.kt#L1228-L1252
[fork-base]: https://github.com/EvanDbg/cardeck/commit/4229fbc1d1cee8907edc14be254878f7bbfd12c2
