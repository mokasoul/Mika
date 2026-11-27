package com.wangzan101.mika.task

import com.wangzan101.mika.bridge.BridgeSessions
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.mcp.BridgeToolSet
import kotlinx.coroutines.CompletableDeferred

/**
 * 工具会话第一版放出去的工具:够跑通「打开 App → 读屏 → 点一下」这条链。
 * 白名单而不是全放,是因为源里定义了六十多个工具(还有整套网页、沙箱、记忆、生图),
 * 一次全给电脑既没必要,也让对面难以挑。
 */
internal val BRIDGE_TOOLS: Set<String> = setOf(
    "phone_query",
    "phone_find",
    "phone_launch_app",
    "phone_gesture",
)

/**
 * 只装配、不跑模型:把这次运行装配好的工具面交给电脑,然后挂起,直到会话关闭。
 *
 * 走的是和手机上任务完全相同的那套装配([com.wangzan101.mika.task.RunPhoneStage.assemblePhone]
 * 已经接好内置助手、无障碍、虚拟屏),所以工具的行为、节奏、屏幕归属和任务里一模一样 ——
 * 唯一的区别是:决定调哪个工具的是电脑,不是手机上的模型。
 *
 * 危险动作(付款、确认、交给人、改 Mika 自己)没有人应答,由 [BridgeToolSet] 里的闸门拦下不执行。
 */
internal suspend fun TaskRun.runBridgeSession(t: RunTools): TaskState {
    val set = BridgeToolSet(t.tools, BRIDGE_TOOLS)
    val closed = CompletableDeferred<Unit>()
    BridgeSessions.install(set, closed)
    return try {
        closed.await()
        TaskState.Succeeded(answer = "工具会话已关闭", verified = false, reviewed = false)
    } finally {
        BridgeSessions.clear(set)
    }
}
