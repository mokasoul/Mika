package com.wangzan101.mika.core.mcp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * 电脑经 MCP 能用的那套工具。两种来源:
 * - [McpTaskTools]:把整件事交给手机上的 Mika(手机自己决定怎么做)
 * - [BridgeToolSet]:把这次运行装配好的工具面直接交给电脑(电脑自己决定怎么点,手机模型不参与)
 *
 * 协议层([McpRpc])只认这个接口,不认具体来源。
 */
interface McpToolSet {
    /** MCP 的 tools/list 内容。 */
    fun definitions(): JsonArray
    fun has(name: String): Boolean

    /** 调用一个工具;业务错误以 `{ok:false,error:...}` 返回,不抛异常。 */
    suspend fun call(name: String, args: JsonObject): JsonObject
}
