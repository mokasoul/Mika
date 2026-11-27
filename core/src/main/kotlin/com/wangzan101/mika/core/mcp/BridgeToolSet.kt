package com.wangzan101.mika.core.mcp

import com.wangzan101.mika.core.action.ActionExecutor
import com.wangzan101.mika.core.action.ActionRecorder
import com.wangzan101.mika.core.action.ApprovalSettings
import com.wangzan101.mika.core.action.Origin
import com.wangzan101.mika.core.action.execution
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.run.record.RunEvent
import com.wangzan101.mika.core.run.RunEventBus
import com.wangzan101.mika.core.safety.guard.OperationGuards
import com.wangzan101.mika.core.tool.ToolDispatcher
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * 把一次运行已经装配好的工具面直接交给电脑:电脑自己决定调哪个工具,手机上的模型完全不参与。
 *
 * 和任务级入口([McpTaskTools])的区别:那边电脑只说要什么,手机决定怎么做;这边电脑就是那只手,
 * 每一步由电脑自己决定。所以闸门必须在:危险动作(付款、确认、交给人、改 Mika 自己)没有人应答,
 * 一律拦下不执行 —— 因此 [autonomous] 恒为 false、[ApprovalSettings] 用 ASK,
 * 让 ConfirmDecision 给出 Required/Denied,再由 ActionExecutor 转成拒绝回执。
 *
 * [allow] 是这次放出去的工具名白名单:源里定义了六十多个工具,一次全放给电脑既没必要也不好用。
 */
class BridgeToolSet(
    private val tools: ToolDispatcher,
    allow: Set<String>,
    private val approvals: ApprovalSettings = ApprovalSettings.ASK,
    private val clock: () -> Long = System::currentTimeMillis,
    /**
     * 每次电脑调用前回调一次(步号、工具名、参数),给宿主把「电脑在做什么」写到任务状态上。
     *
     * 壳模式不跑模型,[TaskState.Running] 里没有 narration/reason,胶囊只能回退成「正在准备」——
     * 用户看不到电脑到底在点哪。宿主在这里把人话写进 [_state],胶囊的步骤流才有内容。
     */
    private val onStep: (step: Int, name: String, args: JsonObject) -> Unit = { _, _, _ -> },
) : McpToolSet {
    val events = RunEventBus()
    private var step = 0
    private val json = Json { ignoreUnknownKeys = true }

    private val executor = ActionExecutor(
        tools, OperationGuards(), null,
        // 没有关卡:等锁屏/被拿走的那一类由 OriginPolicy 与守卫处理,这里不给人工确认的机会
        gate = {},
        recorder = ActionRecorder(events, clock),
        offScreen = tools.offScreenGuard(),
        approvals = approvals,
        // 绝不自主:自主等于付款不问、界面目标猜测也不阻断,那是模型在手机上的语义,不是电脑遥控的语义
        autonomous = false,
    )

    /** 白名单过滤后的工具定义(顺序沿用源里的顺序)。 */
    private val offered = tools.definitions().filter { it.name in allow }

    val names: Set<String> = offered.map { it.name }.toSet()

    override fun definitions(): JsonArray = buildJsonArray {
        offered.forEach { t ->
            add(buildJsonObject {
                put("name", t.name)
                put("description", t.description)
                // 源里的 schema 是 JSON 文本;解析不了就不给 schema,让对面按无参调用并收到参数错误
                runCatching { json.parseToJsonElement(t.parametersJsonSchema) }.getOrNull()?.let { put("inputSchema", it) }
                putJsonObject("annotations") { put("readOnlyHint", false) }
            })
        }
    }

    override fun has(name: String): Boolean = name in names

    override suspend fun call(name: String, args: JsonObject): JsonObject {
        if (name !in names) return fail("unknown_tool")
        step++
        onStep(step, name, args)
        val callId = "bridge_$step"
        events.event(RunEvent.State(clock(), "bridge_tool_call", "$callId $name"))
        val outcome = try {
            executor.run(Part.ToolCall(callId, name, args.toString()), Origin.McpTool(step))
        } catch (e: Exception) {
            return fail("tool_failed", e.message ?: e::class.simpleName)
        }
        val content = outcome.execution().content
        events.event(RunEvent.ToolResult(clock(), step, name, content.toString(), null, null, 0, callId = callId))
        return content
    }

    private fun fail(code: String, message: String? = null) = buildJsonObject {
        put("ok", false)
        put("error", code)
        message?.let { put("message", it) }
    }
}
