package com.wangzan101.mika.core.mcp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 把 observe / list_elements 的 JSON 压成一段中文摘要,给电脑侧模型省 token。
 *
 * 为什么不做成新工具:形态和 [BridgeToolSet] 里已有的读屏工具完全一样,只是排版不同;
 * 多挂一个工具名会让工具面从 36 涨到 37,模型选工具的负担是实打实的。走 `format=brief`
 * 参数,同一个工具两种回执。
 *
 * 为什么值得做(实测,见评测文档):
 * 同一份 14 元素的界面,JSON 约 2100 字符、这份摘要约 400 字符,差 5 倍。
 * 而且摘要不给坐标 —— 坐标是一次性产物,给了反而诱导调用方去算,算错就点空。
 *
 * 编号 `[n]` 只在**本次回执内**有效,和 `ref` 同源,下一次读屏会重排。
 * 要写进可复用流程的,只能是「角色 + 名字」。
 */
object BridgeBrief {

    /** 只对这几种回执做摘要;别的工具原样返回。 */
    val APPLIES_TO = setOf("phone_observe", "phone_list_elements", "web_observe", "web_list_elements")

    fun render(tool: String, content: JsonObject): JsonObject {
        if (tool !in APPLIES_TO) return content
        val elements = content["elements"] as? JsonArray ?: return content

        val head = buildString {
            append("屏幕 ").append(content.str("snapshotId") ?: "?")
            (content.str("currentApp") ?: content.str("currentSite"))?.let { append(" · ").append(it) }
            append(" · ").append(elements.size).append(" 项")
            if (content["truncated"]?.jsonPrimitive?.booleanOrNull == true) append("（已截断）")
            append("\n")
        }

        val body = buildString {
            elements.forEachIndexed { i, raw ->
                val e = raw as? JsonObject ?: return@forEachIndexed
                val name = e.str("name").orEmpty()
                val role = roleCn(e.str("role"))
                // 没名字又不可交互的节点摘要里没信息量,压成一行「序号 角色」反而占地方
                if (name.isBlank() && role == "节点") return@forEachIndexed
                append('[').append(i + 1).append("] ").append(role)
                if (name.isNotBlank()) append(' ').append(name.replace('\n', ' ').take(60))
                stateSuffix(e)?.let { append(" · ").append(it) }
                append('\n')
            }
        }

        return buildJsonObject {
            put("ok", true)
            put("brief", head + body.trimEnd())
            content.str("snapshotId")?.let { put("snapshotId", it) }
            put("count", elements.size)
            content.str("currentApp")?.let { put("currentApp", it) }
        }
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    /** 类名 → 中文角色。认不出就退回去掉包名的短类名,至少比原样可读。 */
    private fun roleCn(role: String?): String {
        if (role.isNullOrBlank()) return "节点"
        val r = role.lowercase()
        return when {
            "switch" in r || "checkbox" in r -> "开关"
            "radio" in r -> "单选项"
            "edittext" in r || "autocomplete" in r || "textfield" in r -> "输入框"
            "button" in r || "imagebutton" in r -> "按钮"
            "tab" in r -> "标签页"
            "seekbar" in r || "progressbar" in r || "slider" in r -> "滑条"
            "recyclerview" in r || "listview" in r || "gridview" in r -> "列表"
            "scrollview" in r || "nestedscroll" in r -> "滚动区"
            "spinner" in r || "dropdown" in r -> "下拉框"
            "imageview" in r || "image" in r -> "图片"
            "textview" in r || "text" in r -> "文本"
            else -> role.substringAfterLast('.').removeSuffix("View").takeIf { it.isNotBlank() } ?: "节点"
        }
    }

    /** 状态后缀:`开` / `已选中` / `不可用` / `可下滑`,空则不追加。 */
    private fun stateSuffix(e: JsonObject): String? {
        val parts = mutableListOf<String>()
        val states = e.str("state").orEmpty().split(',').filter { it.isNotBlank() }.toSet()
        if ("checked" in states) parts += "开"
        if ("selected" in states) parts += "已选中"
        if ("expanded" in states) parts += "已展开"
        if ("disabled" in states) parts += "不可用"
        if (e["scrollable"]?.jsonPrimitive?.booleanOrNull == true) parts += "可下滑"
        if (e["editable"]?.jsonPrimitive?.booleanOrNull == true) parts += "可输入"
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }
}
