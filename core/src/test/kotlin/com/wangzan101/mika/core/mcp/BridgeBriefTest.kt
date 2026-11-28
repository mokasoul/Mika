package com.wangzan101.mika.core.mcp

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * brief 只改排版,不改内容。这几条断言守的就是「排版对、信息没丢、坐标不外泄」。
 */
class BridgeBriefTest {

    private fun element(
        ref: String,
        role: String,
        name: String,
        state: String? = null,
        scrollable: Boolean = false,
    ): JsonObject = buildJsonObject {
        put("ref", ref)
        put("role", role)
        put("name", name)
        put("center", buildJsonObject { put("x", 500); put("y", 700) })
        state?.let { put("state", it) }
        if (scrollable) put("scrollable", true)
    }

    private fun observed(vararg els: JsonObject): JsonObject = buildJsonObject {
        put("ok", true)
        put("currentApp", "com.demo")
        put("snapshotId", "p9")
        put("elements", buildJsonArray { els.forEach { add(it) } })
    }

    private fun briefOf(out: JsonObject): String = out["brief"]!!.jsonPrimitive.content

    @Test
    fun roleIsTranslatedToChinese() {
        val out = BridgeBrief.render(
            "phone_observe",
            observed(
                element("@e1", "android.widget.Button", "返回"),
                element("@e2", "android.widget.Switch", "手机操控", state = "checked"),
                element("@e3", "android.widget.EditText", "搜索"),
                element("@e4", "androidx.recyclerview.widget.RecyclerView", "会话列表", scrollable = true),
            ),
        )
        val brief = briefOf(out)
        assertTrue(brief, brief.contains("[1] 按钮 返回"))
        assertTrue(brief, brief.contains("[2] 开关 手机操控 · 开"))
        assertTrue(brief, brief.contains("[3] 输入框 搜索"))
        assertTrue(brief, brief.contains("[4] 列表 会话列表 · 可下滑"))
    }

    @Test
    fun briefCarriesNoCoordinates() {
        val out = BridgeBrief.render("phone_observe", observed(element("@e1", "android.widget.Button", "返回")))
        val brief = briefOf(out)
        // 坐标是一次性产物,摘要里出现就说明诱导调用方去算
        assertFalse(brief, brief.contains("center"))
        assertFalse(brief, brief.contains("500"))
        assertNull("brief 模式不该再回元素数组", out["elements"])
    }

    @Test
    fun briefKeepsHandleAndCount() {
        val out = BridgeBrief.render(
            "phone_observe",
            observed(element("@e1", "android.widget.Button", "返回"), element("@e2", "android.widget.TextView", "标题")),
        )
        assertEquals("p9", out["snapshotId"]?.jsonPrimitive?.content)
        assertEquals("2", out["count"]?.jsonPrimitive?.content)
        assertEquals("com.demo", out["currentApp"]?.jsonPrimitive?.content)
    }

    @Test
    fun otherToolsPassThroughUnchanged() {
        val content = observed(element("@e1", "android.widget.Button", "返回"))
        val out = BridgeBrief.render("phone_tap", content)
        // 非读屏工具必须原样返回,不能顺手改别人的回执
        assertTrue("应原样返回同一对象", content === out)
    }

    @Test
    fun disabledStateAndEmptyName() {
        val out = BridgeBrief.render(
            "phone_list_elements",
            observed(
                element("@e1", "android.widget.Button", "提交", state = "disabled"),
                // 无名字又认不出角色 → 摘要里不占一行
                element("@e2", "android.view.View", ""),
            ),
        )
        val brief = briefOf(out)
        assertTrue(brief, brief.contains("提交 · 不可用"))
        assertFalse(brief, brief.contains("[2]"))
    }

    @Test
    fun onlyReadToolsAreFormatted() {
        assertTrue(BridgeBrief.APPLIES_TO.contains("phone_observe"))
        assertTrue(BridgeBrief.APPLIES_TO.contains("phone_list_elements"))
        assertFalse(BridgeBrief.APPLIES_TO.contains("phone_tap"))
    }
}
