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

    /**
     * 超过这么久没有电脑调用,就当这轮活儿干完了。
     *
     * 电脑是"调一次工具、等结果、再调下一次",中间不会告诉手机"我说完了"。
     * 没有这道闸,`closed.await()` 会一直挂着,任务状态停在 Running,胶囊就永远显示在干活。
     */
    // 电脑调工具时不会说「我说完了」,只能靠空闲判据收尾。
    // 30 秒:干完停手就回空闲;多步任务中间的思考停顿也可能触发,但会话重建是自动的
    // (下次调用会重新起),代价只是胶囊闪一下。RunBridgeStage 直接引用这个常量。
    internal const val IDLE_MS = 30_000L

    /** 步骤流最多记几条:胶囊那块地方只放得下几行。 */
    private const val MAX_STEPS = 8

    private val lock = Any()
    @Volatile private var set: BridgeToolSet? = null
    @Volatile private var closed: CompletableDeferred<Unit>? = null
    @Volatile private var starting = false
    @Volatile private var lastError: String? = null
    @Volatile private var lastActive = 0L
    private val steps = mutableListOf<String>()

    /** 手机上这个运行的标记要不要当作工具会话。 */
    fun isBridge(externalId: String?): Boolean = externalId?.startsWith(ID_PREFIX) == true

    /** 装配完成:把工具面交出来。 */
    fun install(tools: BridgeToolSet, closed: CompletableDeferred<Unit>) {
        synchronized(lock) {
            set = tools
            this.closed = closed
            starting = false
            lastError = null
            steps.clear()
            lastActive = System.currentTimeMillis()
        }
    }

    /**
     * 记一步人话,给胶囊的步骤流用。
     *
     * 壳模式不跑模型,没有 [Exchange] 那样的历史步骤,`steps(ex, line)` 只能拿到当前一句 ——
     * 于是每来一次新调用上一条就被覆盖,用户永远只看见最后一句。这里自己攒一份。
     */
    fun record(said: String) {
        synchronized(lock) {
            lastActive = System.currentTimeMillis()
            if (said.isNotBlank()) {
                steps.add(said)
                while (steps.size > MAX_STEPS) steps.removeAt(0)
            }
        }
    }

    /** 距离上一次电脑调用过了多久(给空闲闸用)。 */
    fun idleFor(): Long = System.currentTimeMillis() - lastActive

    /** 这一轮已经做过的步骤;没有会话时为空。 */
    fun stepList(): List<String> = synchronized(lock) { steps.toList() }

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
                steps.clear()
                lastActive = 0L
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
