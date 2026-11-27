package com.wangzan101.mika.bridge

import android.content.Context
import com.wangzan101.mika.core.mcp.BridgeToolSet
import com.wangzan101.mika.task.TaskService
import kotlinx.coroutines.CompletableDeferred

/**
 * 工具级会话(电脑直接调工具)的进程内登记处。
 *
 * 一条会话的生命周期:电脑第一次请求 `/bridge` → 这里起一个"只装配、不跑模型"的运行 →
 * 那个运行把装配好的工具面交到这里([install])并挂起 → 电脑逐个调工具 → 会话关闭时运行结束。
 *
 * 只用一把锁和几个状态位:每台手机同时只有一条工具会话(电脑就是这一个主人),
 * 第二个请求复用同一条,不另起。
 */
object BridgeSessions {
    /** 电脑第一次请求时起的那个运行的标记;`mcpExternalId` 带这个前缀的就是工具会话。 */
    const val ID_PREFIX = "bridge:"

    private val lock = Any()
    @Volatile private var set: BridgeToolSet? = null
    @Volatile private var closed: CompletableDeferred<Unit>? = null
    @Volatile private var starting = false
    @Volatile private var lastError: String? = null

    /** 手机上这个运行的标记要不要当作工具会话。 */
    fun isBridge(externalId: String?): Boolean = externalId?.startsWith(ID_PREFIX) == true

    /** 装配完成:把工具面交出来。 */
    fun install(tools: BridgeToolSet, closed: CompletableDeferred<Unit>) {
        synchronized(lock) {
            set = tools
            this.closed = closed
            starting = false
            lastError = null
        }
    }

    /** 装配失败(没助手、没无障碍、手机正忙等):记下原因,请求方据此报错,不用干等到超时。 */
    fun fail(reason: String) {
        synchronized(lock) {
            starting = false
            lastError = reason
        }
    }

    /** 会话结束(被关闭或运行自己结束):清掉,下一个请求重新起。 */
    fun clear(tools: BridgeToolSet?) {
        synchronized(lock) {
            if (set === tools) {
                set = null
                closed = null
                starting = false
            }
        }
    }

    /** 当前能用的工具面;还没起来为 null。 */
    fun current(): BridgeToolSet? = set

    /** 最近一次装配失败的原因;没有为 null。 */
    fun lastError(): String? = lastError

    /**
     * 取一条能用的会话:没有就起一个并等到装配完成。
     * 在 HTTP 请求线程上阻塞调用;超时返回 null(调用方回 503,电脑稍后重试)。
     */
    fun await(context: Context, timeoutMs: Long = 60_000): BridgeToolSet? {
        set?.let { return it }
        synchronized(lock) {
            if (set == null && !starting) {
                starting = true
                lastError = null
                runCatching { TaskService.startBridge(context) }
                    .onFailure { starting = false; lastError = it.message ?: it::class.simpleName }
            }
        }
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            set?.let { return it }
            // 装配失败就不必干等:原因已经记下,让电脑知道这次为什么没有工具
            if (lastError != null) return null
            Thread.sleep(200)
        }
        return null
    }

    /** 关闭当前会话:挂着的运行随之结束,工具面下次重新装配。 */
    fun close() {
        val signal = synchronized(lock) {
            val s = closed
            closed = null
            set = null
            starting = false
            s
        }
        signal?.complete(Unit)
    }
}
