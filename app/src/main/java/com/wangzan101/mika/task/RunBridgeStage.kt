package com.wangzan101.mika.task

import com.wangzan101.mika.bridge.BridgeSessions
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.mcp.BridgeToolSet
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 壳模式放出去的工具面：只放「只能在手机上做、Mac 侧没有等价能力」的那些。
 *
 * 判定原则(比按前缀打包重要得多):
 * 1. 电脑侧已有更强替代的一律不放 —— 典型是整套 web_*(浏览器操作),
 *    Mac 上有 CDP/Playwright,绕道手机只会更慢更绕。
 * 2. 依赖已裁掉的内置 rootfs 的不放 —— 例如 SandboxGroup 里的 search_files。
 * 3. 只服务手机侧模型自身上下文的元数据不放 —— 例如 update_device_directory_description。
 * 4. 交互/编排类不放(见下)。
 *
 * 按前缀匹配 phone_,上游新增手机工具会自动包含,不必同步上游后重新发版。
 */
internal val BRIDGE_TOOL_PREFIXES = listOf("phone_")

/**
 * 手机侧剩下的两类没有统一前缀,逐个列出:
 * - 设备文件读写/查看:只有手机上有这些文件,Mac 拿不到;
 * - view_past_screens:回看刚才的屏幕,电脑侧没有等价能力。
 */
internal val BRIDGE_EXTRA_TOOLS = setOf(
    "list_device_directory_hints",
    "list_device_files",
    "list_folder_files",
    "search_device_files",
    "read_file",
    "write_file",
    "copy_device_file",
    "view_image",
    "share_file",
    "view_past_screens",
)

/** 从这次运行装配出来的全部工具里挑出壳模式该放出去的那些。 */
internal fun bridgeAllow(all: List<String>): Set<String> = all
    .filter { name -> BRIDGE_TOOL_PREFIXES.any(name::startsWith) || name in BRIDGE_EXTRA_TOOLS }
    .toSet()

/**
 * 只装配、不跑模型:把这次运行装配好的工具面交给电脑,然后挂起,直到会话关闭。
 *
 * 走的是和手机上任务完全相同的那套装配([com.wangzan101.mika.task.RunPhoneStage.assemblePhone]
 * 已经接好内置助手、无障碍、虚拟屏),所以工具的行为、节奏、屏幕归属和任务里一模一样 ——
 * 唯一的区别是:决定调哪个工具的是电脑,不是手机上的模型。
 *
 * 危险动作(付款、确认、交给人、改 Mika 自己)没有人应答,由 [BridgeToolSet] 里的闸门拦下不执行。
 */
internal suspend fun TaskRun.runBridgeSession(t: RunTools): TaskState = coroutineScope {
    val allow = bridgeAllow(t.tools.definitions().map { it.name })
    val set = BridgeToolSet(
        t.tools, allow,
        // 壳模式不跑模型,没有模型写的 narration —— 胶囊只能回退成「正在准备」。
        // 这里把电脑的每次调用翻成一句人话写进状态,步骤流才看得到电脑在点哪。
        onStep = { step, name, args ->
            val said = com.wangzan101.mika.bridge.bridgeStepText(name, args)
            BridgeSessions.record(said)
            runState.setState(TaskState.Running(step, said, narration = said))
        },
    )
    val closed = CompletableDeferred<Unit>()
    BridgeSessions.install(set, closed)
    // 电脑调一次工具、等结果、再调下一次,不会说「我说完了」。没有这道闸,
    // closed.await() 会一直挂着,任务状态停在 Running,胶囊永远显示在干活。
    val idle = launch {
        while (isActive && !closed.isCompleted) {
            delay(5_000)
            if (BridgeSessions.idleFor() > 60_000) {
                BridgeSessions.close()
                break
            }
        }
    }
    return@coroutineScope try {
        closed.await()
        TaskState.Succeeded(answer = "工具会话已关闭", verified = false, reviewed = false)
    } finally {
        idle.cancel()
        BridgeSessions.clear(set)
    }
}
