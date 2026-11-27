package com.wangzan101.mika.core.remote

import com.wangzan101.mika.core.device.DeviceAccess
import com.wangzan101.mika.core.mcp.McpRpc
import com.wangzan101.mika.core.mcp.McpTaskTools
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.InetAddress

/**
 * HTTP 与 MCP 每次请求都从同一个已认证归属构造任务服务,避免某条协议路径绕过任务、文件或导出权限。
 * 只读 Web 控制台([RemoteCompanion])与任务 API 并列,同样走这里的认证入口。
 */
class RemoteEndpoints(
    private val tasks: RemoteTasks,
    private val serverVersion: String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val device: DeviceAccess? = null,
    private val history: RemoteHistory? = null,
    private val records: RunExportSource? = null,
    private val calls: com.wangzan101.mika.core.calls.PhoneCallPort? = null,
    private val companion: RemoteCompanion? = null,
    /** 工具级入口(MCP /bridge):把本次运行装配好的工具面直接交给电脑;没接线时为 null,路径按不存在处理。 */
    private val bridgeTools: (() -> com.wangzan101.mika.core.mcp.McpToolSet?)? = null,
    /**
     * 电脑说「我说完了」时的收尾回调([BRIDGE_SHUTDOWN_METHOD])。
     *
     * `/bridge` 是无状态 HTTP,手机看不见「会话结束」,只能靠空闲超时猜。给了这个回调,
     * 电脑收尾时能明确说一句,手机立刻收工,不用干等。没接线就只靠超时兜底。
     */
    private val bridgeClose: (() -> Unit)? = null,
    /** 用户暂停了 Mika:MCP 与 CLI 接口共用这一个标记([refuseRemote])。 */
    private val paused: com.wangzan101.mika.core.run.PauseFlag = com.wangzan101.mika.core.run.PauseFlag.Never,
) {
    private val companionApi = companion?.let(::CompanionApi)
    private val clients = LinkedHashMap<String, Long>()
    private val mutable = MutableStateFlow(RemoteActivity())
    val activity = mutable.asStateFlow()

    fun handle(req: HttpRequest, deviceAccess: Boolean = true, historyAccess: Boolean = true): HttpResponse {
        // Relay adapters do not carry the phone's bearer token. They still get one isolated task namespace,
        // never the internal unscoped view and never local history.
        val owner = req.accessOwner ?: "relay"
        val scopedTasks = tasks.scope(owner)
        val service = RemoteTaskService(scopedTasks, records = records?.scope(owner), paused = paused)
        val rpc = McpRpc(McpTaskTools(service, device.takeIf { deviceAccess }, calls.takeIf { deviceAccess }), serverVersion) { remote, name -> seen(remote, name) }
        if (req.path == McpRpc.PATH) return rpc.handle(req)
        // 工具级入口:同一道认证门口,电脑直接调本次运行装配好的工具(手机模型不参与)
        if (bridgeTools != null && req.path == BRIDGE_PATH) {
            // 电脑说「我说完了」。必须拦在 bridgeTools.invoke() 之前 —— 这句话不是工具调用,
            // 为了处理它反而把会话又起一个就荒唐了。
            if (bridgeClose != null && saysShutdown(req)) {
                bridgeClose.invoke()
                return HttpResponse.json(200, """{"jsonrpc":"2.0","result":{"ok":true}}""")
            }
            val set = bridgeTools.invoke()
                ?: return RemoteTaskApi.error(503, "bridge_not_ready", "The tool session is not ready yet; retry in a moment.")
            return McpRpc(set, serverVersion) { remote, name -> seen(remote, name) }.handle(req)
        }
        if (req.path == RemoteTaskApi.ROOT || req.path.startsWith("${RemoteTaskApi.ROOT}/")) {
            seen(req.remote, req.header("x-mika-client")?.take(64))
            if (companionApi != null && CompanionApi.owns(req.path)) return companionApi.handle(req)
            val api = RemoteTaskApi(service, serverVersion, device.takeIf { deviceAccess }, history.takeIf { historyAccess }, companion = companion != null)
            return api.handle(req)
        }
        return RemoteTaskApi.error(404, "not_found", "No such endpoint")
    }

    companion object {
        /** 工具级 MCP 入口的路径,和任务级 `/mcp` 并列。 */
        const val BRIDGE_PATH = "/bridge"
        /** 电脑在这一条 `/bridge` 请求里说「我说完了」。不是 MCP 方法,只在这一条路径上认。 */
        const val BRIDGE_SHUTDOWN_METHOD = "bridge/shutdown"
    }

    /** 这一条是不是电脑在说「我说完了」。解析失败就当不是 —— 不能因为一句话把请求打死。 */
    private fun saysShutdown(req: HttpRequest): Boolean = runCatching {
        val root = kotlinx.serialization.json.Json.parseToJsonElement(String(req.body, Charsets.UTF_8))
        root is kotlinx.serialization.json.JsonObject &&
            (root["method"] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content == BRIDGE_SHUTDOWN_METHOD
    }.getOrDefault(false)

    private fun seen(remote: InetAddress, name: String?) {
        val now = clock()
        synchronized(this) {
            val who = remote.hostAddress ?: "?"
            val key = if (name != null) "$who|$name" else clients.keys.lastOrNull { it.startsWith("$who|") } ?: "$who|"
            clients.remove(key); clients[key] = now
            clients.entries.removeAll { now - it.value > RemoteHost.RECENT_MS }
            mutable.value = RemoteActivity(clients.size, now)
        }
    }
}
