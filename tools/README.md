# 持久调度唤醒回归

## 行为与边界

系统闹钟由模块进程通过 `CommandService` 登记和取消，避免目标应用停止时连同恢复闹钟一起被移除。Binder 只传精度通道、时间和窗口，不在服务端重读调度表或获取 planner 锁，避免跨进程锁重入。

用户指定唤醒（`USER_EXACT`）使用 `AlarmClock`，可能在系统状态栏/锁屏显示下一次闹钟。普通轮询继续使用弹性窗口，严格子任务继续使用精确闹钟；无精确闹钟权限时保留原有降级。模块仍需具备后台运行和对应闹钟权限；强制停止模块本身不保证恢复。

定向启动继续通过已授权的 Root/Shizuku 执行器；命令采用经过字符白名单检查的无引号参数，避免 Shizuku 将 shell 引号当成实际 argv。原有允许前台拉起开关、账号/会话校验、启动限流、风险暂停和启动确认逻辑保留。收到闹钟时在跨进程锁内刷新调度快照，随后在锁外路由；过期状态和后续重排仍使用现有逻辑。

## 本地命令参数检查

先按 CONTRIBUTING.md 编译一次，准备 Python 3、JDK 17 及 Gradle 的 Kotlin 编译器缓存：

```sh
python3 tools/check_wakeup_compat.py
```

支持 `JAVA_HOME`、`GRADLE_USER_HOME`。检查抽取实际启动参数构造代码并用 Kotlin 编译，验证 Shizuku argv 没有字面引号且非法参数被拒绝；Android 常量使用桩，不代替设备验证。

## USB 实机检查

准备单台已授权 adb 设备和单个有效账号，确保模块已激活、执行器已授权、当前版本说明已由用户确认、允许定时前台拉起。安装对应构建后重启目标进程。脚本会临时添加约 100 秒后的唤醒点、返回桌面并息屏；实际执行业务任务，可能产生当前配置允许的业务操作。请先自行备份，并在测试期间保持 USB 连接，不修改配置。

```sh
python3 tools/check_wakeup.py --output /path/outside/repo/background
python3 tools/check_wakeup.py --output /path/outside/repo/stopped --kill-host
python3 tools/check_wakeup.py --output /path/outside/repo/expired --kill-host --expire-first
```

- 普通模式要求观察到对应时间的 `ALARM_WAKEUP` 实际执行，正在运行的任务可能使它先排队。
- `--kill-host` 强制停止目标应用，检查恢复闹钟仍在，并等待冷启动后进入执行。
- `--expire-first` 在目标停止后把已登记记录改为过期，加入后续记录，要求看到 `EXPIRED`、后续闹钟重排及冷启动执行；它会修改共享调度表，仅用于可控测试。
- 正常退出、失败或 Ctrl-C 时恢复原唤醒时间；未风险暂停时通知进程重载原配置。设备断连或进程被强杀会阻止清理，需恢复输出目录中的 `original-config.json` 的唤醒时间并重载配置。
- 冷启动会重建账号会话，旧记录可能被清理；脚本此时以对应启动参数后的工作流执行为证据，不声称旧记录已在新会话原样确认。

输出含配置、账号信息和原始日志，务必保存到仓库外，公开前脱敏。当前 USB 回归在 OnePlus Android 16 上验证，闹钟诊断文本依赖该系统的 dumpsys 格式；短时 USB/息屏通过不代表长时间无充电待机、网络受限或其他 ROM 已验证。
