package com.wangzan101.mika.core.mcp

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.run.RunId
import com.wangzan101.mika.core.run.RunSpec
import com.wangzan101.mika.core.tool.FakeScreen
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 工具级入口:白名单只放说好的那几个,其余的既不能列也不给调。 */
class BridgeToolSetTest {
    private val spec = RunSpec(RunId("bridge-test"), "电脑直接操作手机", "bridge-thread")
    private fun tools() = toolDispatcher(FakeScreen(), runSpec = spec)

    @Test fun onlyWhitelistedToolsAreListed() {
        val set = BridgeToolSet(tools(), setOf("phone_query", "phone_launch_app"))
        val names = set.definitions().map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertEquals(listOf("phone_query", "phone_launch_app"), names)
        assertTrue(set.has("phone_query"))
        assertFalse(set.has("phone_gesture"))
        assertFalse(set.has("run_command"))
    }

    @Test fun callingOutsideTheWhitelistIsAnUnknownTool() = runBlocking {
        val set = BridgeToolSet(tools(), setOf("phone_query"))
        val out = set.call("phone_gesture", JsonObject(emptyMap()))
        assertEquals("unknown_tool", out["error"]!!.jsonPrimitive.content)
        assertEquals(false, out["ok"]!!.jsonPrimitive.content.toBoolean())
    }

    /** 白名单里的工具能真的进到执行层(这一步只证明通路,不证明手机上点得动)。 */
    @Test fun aWhitelistedToolReachesTheExecutor() = runBlocking {
        val set = BridgeToolSet(tools(), setOf("phone_list_apps"))
        val out = set.call("phone_list_apps", JsonObject(emptyMap()))
        // 空屏上没有 App:回执是结构化 JSON,不抛异常、也不是 unknown_tool
        assertTrue("应当有 ok 字段: $out", out.containsKey("ok"))
        assertTrue(out["error"]?.jsonPrimitive?.content != "unknown_tool")
    }

    @Test fun everyListedToolCarriesASchema() {
        val set = BridgeToolSet(tools(), setOf("phone_query", "phone_gesture", "phone_launch_app", "phone_find"))
        val defs = set.definitions().jsonArray.map { it.jsonObject }
        assertEquals(4, defs.size)
        defs.forEach { d ->
            assertTrue("缺 description: $d", d["description"] != null)
            assertTrue("缺 inputSchema: $d", d["inputSchema"] != null)
        }
    }
}
