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

## 工具面:只放「Mac 侧不可替代」的

判定原则**不是按前缀打包**,而是逐个问:这件事 Mac 上能不能自己做?

| 判据 | 处置 |
|---|---|
| Mac 侧有更强替代(浏览器自动化、生成类、转写) | 不放 |
| 需要人参与(问人、请接手、请同意、确认登录) | 不放 —— 电脑侧无人应答,只会把会话卡住 |
| 会把手机侧模型拉进来(`spawn_agent` / `do_each`) | 不放 —— 违背「手机模型不参与」这个前提 |
| 依赖已裁掉的内置 rootfs(`SandboxGroup` 那几个) | 不放 —— 必然失败 |
| 闸门按 Irreversible 拦下的(`root_run_command`) | 不放 —— 放出去也用不了 |
| Mika 自身运行时(状态/记忆/技能/知识/任务记忆/画像) | 不放 —— 那是手机模型的上下文,不是电脑的 |
| **只有手机上才有的眼睛和手、手机上的文件** | **放** |

实现见 `app/.../task/RunBridgeStage.kt`:**`phone_` 前缀动态匹配** + 一份额外的文件类名单。
按前缀而不是写死清单,上游以后新增手机工具会自动包含,不必每次同步上游都重新改代码发版。

### 放出去的(静态统计 35)

| 类 | 数量 | 内容 |
|---|---|---|
| `phone_*` | 25 | 读屏 `phone_query`、结构化提取 `phone_extract`、多屏采集 `phone_collect`、滚动查找 `phone_find`、等界面事件 `phone_event`、查弹窗 `phone_dialog`、批量操作 `phone_run`、手势 `phone_gesture`、开 App `phone_launch_app`、列 App `phone_list_apps`、滚轮选择 `phone_set_wheel`、等待 `phone_wait`、短信验证码登录 `phone_sms_login`、分享 `phone_share_text`/`phone_share_image`/`phone_share_audio`/`phone_share_video`、日历闹钟 `phone_add_calendar_event`/`phone_calendar_bulk_insert`/`phone_set_alarm`/`phone_set_timer`/`phone_show_alarms`、打开链接 `phone_open_link`、设备 shell `phone_system_command`、任务后处理 `phone_app_after_task` |
| 设备文件 | 9 | `list_device_files`、`list_folder_files`、`list_device_directory_hints`、`search_device_files`、`read_file`、`write_file`、`copy_device_file`、`view_image`、`share_file` |
| 回看屏幕 | 1 | `view_past_screens` —— 回看刚才的屏幕,电脑侧没有等价能力 |

> `phone_system_command` 走的是 **Shizuku 提供的设备 shell**(uid 2000),**不是**被裁掉的内置 rootfs,所以保留。
> 实际数量以运行时返回的工具清单为准(源码静态统计出 82 个定义,少数在动态构造里,正则数不到)。

### 不放的:逐个核对过 label 与实现

| 类 | 数量 | 具体 | 理由 |
|---|---|---|---|
| `web_*` | 13 | `web_navigate`、`web_read_page`、`web_interact`、`web_evaluate_js`、`web_upload`、`web_tabs`、`web_site_mode`、`web_keep_open`、`web_wait_for`、`web_downloads`、`web_capture`、`web_script`、`web_run_site_command` | Mac 有 CDP/Playwright,能力和可控性都更强,绕道手机更慢更绕 |
| `mika_*` | 4 | `mika_status`(Mika 自身运行状态)、`mika_sites`(**它自己的常用网站清单**,不是搜索)、`mika_user_page`(内置浏览器的当前页)、`mika_confirm_login`(等你登录) | 看的是 Mika 自己,不是手机环境 |
| 生成类 | 7 | `image_generate`/`image_edit`、`video_generate`/`video_frames`/`video_info`、`audio_generate`/`audio_transcribe` | Mac 侧工具更强(生图、ffmpeg、whisper) |
| Mika 记忆与知识 | 11 | `memory_*`、`skill_*`、`knowledge_search`、`task_memory_*` | 那是手机模型自己的上下文 |
| 交互类 | 6 | `ask_user`、`request_confirmation`、`hand_to_user`、`handle_obstacle`、`mika_confirm_login`、`end_not_done` | 无人应答;`end_not_done` 是任务收尾语,壳模式没有「任务」概念 |
| 编排队列 | 2 | `spawn_agent`、`do_each` | 会启动子 agent,把手机侧模型拉进来 |
| 依赖已裁 rootfs | 3 | `run_command`、`run_bundled_script`、`search_files` | 都属 `SandboxGroup`,rootfs 已从 APK 移除,必然失败 |
| root | 1 | `root_run_command` | 走真 root,闸门按 Irreversible 拦下,放出去也用不了 |
| 其他 | 4 | `wait`(纯延时 0.2–10 秒,电脑侧 sleep 即可)、`profile_edit`(用户画像)、`schedule_task`(手机侧定时)、`speak`(手机喇叭 TTS) | 电脑侧有等价手段,或与手机操作无关 |

## 包体:发布包不放内置 Linux 沙箱

APK 里 `assets/runtime/rootfs.tar.xz` 一个文件占 **56.4 MB(47%)**,是
`tools/sandbox/build-runtime.py` 组装出来的 Alpine minirootfs,只服务 App 内的 Linux 沙箱(终端)。

```kotlin
// app/build.gradle.kts
androidResources {
    noCompress += "xz"
    ignoreAssetsPattern = "!rootfs.tar.xz"   // 壳模式:不打包内置沙箱 rootfs
}
```

实测:arm64 **121 MB → 65 MB**,universal 133 MB → 77 MB。

两个前提都核实过,缺一个就不能裁:

1. `BundledRuntimeTest` 读的是**源码树** `app/src/main/assets/runtime/rootfs.tar.xz` 而不是 APK,
   所以源码树保留该文件、测试照常通过(3 用例 0 失败)。**这也是它不影响 fork 同步的原因**。
2. `RuntimePreparer.ensure()` 对缺文件是 `catch (e: Exception) → failed = true + Log.w`,
   降级范围仅限沙箱功能,不会崩启动。

两个 Gradle 坑:

- `packaging { assets { excludes += … } }` **不存在**(AGP 8.7 的 `Packaging` 只有 `resources`/`jniLibs`/`dex`),
  写上去是 `Unresolved reference: assets`;排除 asset 只能走 `androidResources.ignoreAssetsPattern`。
- `variant.packaging.resources.excludes` 管的是 java resources,对 assets 无效,**且改了不报错、只是静默不生效**。

不建议继续裁 OCR(~13 MB):MLKit 是读屏的后备路径(canvas/游戏/纯图片界面读不到无障碍树时用),
裁它还要动代码引用点,会扩大与上游的差异面。dex 那 48 MB 是功能代码本体。65 MB 是该配置下的底线。


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

