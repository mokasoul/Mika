# 让 Mac 上的 Hermes 直接控制 Mika(壳模式)

Mika 原本的设计是:电脑把**整件事**交给手机(`mika_run_task`),手机上的模型自己决定怎么点。
这份改动加的是**另一条路**:电脑直接调 Mika 已经装配好的工具(`phone_query` / `phone_gesture` …),
手机上的模型完全不参与。电脑就是那只手,手机只是一个有手有眼的壳。

上游在 `core/mcp/McpServer.kt` 里其实留了个「无模型工具会话」的类,但**一行都没接线**
(`docs/dev/architecture.md` 称它为「低层工具会话…是另一个方向」)。这里把它接上。

## 改动清单(为上游同步而保持最小)

新增文件(与上游零冲突):

| 文件 | 作用 |
|---|---|
| `core/.../core/mcp/McpToolSet.kt` | MCP 工具面的接口,两个来源共用 |
| `core/.../core/mcp/BridgeToolSet.kt` | 把 ToolDispatcher 包成工具面,带危险动作闸门 |
| `app/.../bridge/BridgeSessions.kt` | 进程内会话登记处(起会话、等待装配、关闭) |
| `app/.../task/RunBridgeStage.kt` | 只装配不跑模型的运行逻辑 + 工具白名单 |

改了上游文件的地方(**都是加行,不是改行**,rebase 冲突面极小):

| 文件 | 改动 |
|---|---|
| `core/.../core/mcp/McpRpc.kt` | 构造参数类型 `McpTaskTools` → `McpToolSet`(1 行) |
| `core/.../core/mcp/McpTaskTools.kt` | 声明实现 `McpToolSet` + 3 个 `override`(4 行) |
| `core/.../core/remote/RemoteEndpoints.kt` | 新增 `bridgeTools` 参数、`/tools` 路由、`BRIDGE_PATH` 常量(约 10 行) |
| `app/.../task/TaskRun.kt` | `execute()` 里装配完工具后一个 `if` 分支(2 行) |
| `app/.../task/TaskService.kt` | `ACTION_START_BRIDGE` 分支、常量、`startBridge()`(约 20 行) |
| `app/.../mcp/McpRuntime.kt` | 把 `BridgeSessions.await` 传给 `RemoteEndpoints`(1 行) |

判定「这是不是一个工具会话」用的是已有的 `mcpExternalId` 前缀 `bridge:`,
所以没有新增 `RunOrigin` 枚举值,不会引起任何 `when` 分支的连锁改动。

## 上游更新了怎么办

```bash
tools/hermes-bridge/upstream-sync.sh          # 只检查:拉上游、在临时 worktree 里试算我们那几个补丁还能不能干净应用
tools/hermes-bridge/upstream-sync.sh --apply  # 干净就真的 rebase
```

冲突大概率只落在 `TaskService.kt` 的 `when (intent?.action)` 和 `RemoteEndpoints.handle()` 这两处,
因为它们本身就在频繁加分支。按上面的改动清单重新打一遍即可。

## 安全语义(和任务级入口的区别)

- `BridgeToolSet` 恒用 `autonomous = false` + `ApprovalSettings.ASK`。
  危险动作(付款、确认、交给人、改 Mika 自己)在工具级调用里**没有人应答**,
  `ConfirmDecision` 给出 `Required/Denied`,由 `ActionExecutor` 转成拒绝回执 —— **不做,不是放行**。
- ⚠️ 不要照抄 `McpToolSession`(上游那个未接线的类)的开箱配置:它传了 `autonomous = true`,
  按 `ConfirmDecision.kt:65` 的逻辑,自主即「付款也不问、界面目标猜测也不阻断」。
- 仍然走 `OperationGuards`、`ActionRecorder`、`OffScreenGuard`,和任务内调工具是同一条路。

## 第一版只放四个工具

```kotlin
// app/.../task/RunBridgeStage.kt
setOf("phone_query", "phone_find", "phone_launch_app", "phone_gesture")
```

够跑通「打开 App → 读屏 → 点一下」。要扩,改这一处白名单即可 —— 源里还有 `phone_collect`、
`phone_wait`、`phone_dialog`、`phone_extract`、`phone_open_link` 以及整套 `web_*`。

## 接入 Mac 上的 Hermes

1. 手机装包后,在 Mika 里打开「连接电脑」(MCP),选 USB 或局域网。
2. USB 方式(推荐):

   ```bash
   adb shell sh /sdcard/Android/data/com.wangzan101.mika/files/connect-computer.sh
   adb forward tcp:8765 tcp:8765
   cat /sdcard/Android/data/com.wangzan101.mika/files/mcp-usb.json   # 拿 token
   ```

3. Hermes 配置里加一个 MCP server,URL 用**工具级**入口 `/tools`(不是任务级的 `/mcp`):

   ```yaml
   mcp_servers:
     mika-bridge:
       type: http
       url: http://127.0.0.1:8765/tools
       headers:
         Authorization: Bearer <token>
   ```

   `/mcp` 仍然是「把整件事交给手机」,`/tools` 才是「电脑直接调工具」。两者可同时开着。

## 已知限制(第一版)

- **单会话**:一台手机同时只有一条工具会话,第二个请求复用同一条。
- **占一个任务槽**:工具会话本身是一个运行,手机上会看到它的卡片。
  如果你在手机上同时下任务又开了并行,可能会排队;第一版没有做「不占槽」。
- **装配依赖手机上配了主模型**:`Execute()` 里 `assembleModels` 为空会直接失败。
  纯壳用途也建议先随便配一个模型,避免这条路径走不通。
- **没有自动关闭**:会话会一直挂着直到被取消。后续可以加空闲超时。
- 虚拟屏操作:和你自己那套青鸟一样,不占主屏,但也意味着盯着手机看不到它在点哪。

## 在线更新(本 fork 自己发版)

发到 fork 的 release,手机 App 里「检查更新」就会拉它。三件事缺一不可:

| 环节 | 位置 | 为什么 |
|---|---|---|
| 固定签名 | `app/build.gradle.kts` 的 `signingConfigs.bridge` | 证书变了,更新包就装不上去,只能卸载重装(会丢数据和授权) |
| 更新源 | `about/MikaLinks.kt` 的 `REPO` / `LATEST_RELEASE_API` | 硬编码官方仓库的话永远查不到我们的版本 |
| 附件命名 | `tools/hermes-bridge/release.sh` | `Releases.apkPreference()` 按 `-arm64.apk` 后缀挑包,`app-arm64-v8a-release.apk` 直接发上去匹配不到 |

证书与口令存在 `~/.android/mika-bridge.jks` 和 `~/.android/mika-bridge.properties`(600 权限),
**不进仓库**。当前证书 `CN=Mika Bridge`,SHA-256 `60fa7642…a7dd`。

发版:

```bash
tools/hermes-bridge/release.sh --publish   # 构建 + 验签 + 改名为 mika-arm64/mika-universal + gh release create
```

版本号要同时在 `app/build.gradle.kts` 里加:只改 `versionName` 不改 `versionCode`,
Android 仍按 versionCode 判新旧,装不上。

下载侧的两道校验(都会被我们的 fork 满足,但要记住它们的存在):

- `ApkIntegrity.requireOfficialUrl()`:HTTPS + host 必须是 `github.com` +
  路径以 `${MikaLinks.RELEASES}/download/` 开头。因为 `official` 是从常量派生的,改常量即自动放行。
- 装之前还验包名、版本号、签名证书,任何一项不符都会报 `ApkRejected`。

