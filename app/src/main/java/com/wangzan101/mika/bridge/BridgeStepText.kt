package com.wangzan101.mika.bridge

import androidx.annotation.StringRes
import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 把一次电脑调用翻成人话,写进任务状态的 narration,让胶囊的步骤流显示「电脑此刻在做什么」。
 *
 * 为什么需要:壳模式只装配工具、不跑模型,于是 [com.wangzan101.mika.core.agent.TaskState.Running]
 * 里没有模型写的 narration/reason,胶囊的 runningText 只能回退成「正在准备」—— 用户看不到
 * 电脑到底点了哪、输了什么。这里补的就是那句话。
 *
 * 只求一眼看懂,不求精确:认不出的工具退回「执行 <工具名>」,不编造动作。
 * 文案取不到时(资源未安装等极端情况)退回工具名,绝不因为写一句话把调用打断。
 */
internal fun bridgeStepText(name: String, args: JsonObject): String {
    fun arg(key: String): String? =
        runCatching { args[key]?.jsonPrimitive?.contentOrNull }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }

    /** 点击类:优先用可见文字,其次用调用方给的 target。 */
    fun target(): String? = arg("element") ?: arg("target") ?: arg("name")

    return safe {
        when (name) {
            "phone_tap" -> target()?.let { format(R.string.bridge_step_tap, it) } ?: tr(R.string.bridge_step_tap_blank)
            "phone_type_text" -> arg("text")?.let { format(R.string.bridge_step_type, it) } ?: tr(R.string.bridge_step_type_blank)
            "phone_clear_text" -> tr(R.string.bridge_step_clear)
            "phone_launch_app" -> arg("package")?.let { format(R.string.bridge_step_launch, it) } ?: tr(R.string.bridge_step_launch_blank)
            "phone_back" -> tr(R.string.bridge_step_back)
            "phone_press_key" -> arg("key")?.let { format(R.string.bridge_step_key, it) } ?: tr(R.string.bridge_step_key_blank)
            "phone_swipe", "phone_gesture" -> tr(R.string.bridge_step_swipe)
            "phone_scroll", "phone_set_wheel" -> tr(R.string.bridge_step_scroll)
            "phone_find" -> (arg("text") ?: arg("pattern"))?.let { format(R.string.bridge_step_find, it) }
                ?: tr(R.string.bridge_step_find_blank)
            "phone_wait" -> (arg("text") ?: arg("gone"))?.let { format(R.string.bridge_step_wait, it) }
                ?: tr(R.string.bridge_step_wait_blank)
            "phone_query", "phone_observe", "phone_list_elements", "phone_extract", "phone_collect" ->
                tr(R.string.bridge_step_read)
            "phone_screenshot" -> tr(R.string.bridge_step_screenshot)
            "phone_diagnose" -> tr(R.string.bridge_step_diagnose)
            "phone_dialog" -> tr(R.string.bridge_step_dialog)
            "phone_list_apps" -> tr(R.string.bridge_step_list_apps)
            "phone_run" -> tr(R.string.bridge_step_run)
            "phone_system_command" -> arg("commands")?.let { format(R.string.bridge_step_shell, it.take(24)) }
                ?: tr(R.string.bridge_step_shell_blank)
            "phone_share_text", "phone_share_audio", "phone_share_image", "phone_share_video" ->
                tr(R.string.bridge_step_share)
            "phone_app_after_task" -> tr(R.string.bridge_step_after_task)
            "phone_add_calendar_event" -> (arg("title") ?: arg("summary"))?.let { format(R.string.bridge_step_calendar_add, it) }
                ?: tr(R.string.bridge_step_calendar_add_blank)
            "phone_calendar_bulk_insert" -> tr(R.string.bridge_step_calendar_bulk)
            "phone_event" -> arg("name")?.let { format(R.string.bridge_step_event, it) }
                ?: tr(R.string.bridge_step_event_blank)
            "phone_set_alarm" -> arg("time")?.let { format(R.string.bridge_step_alarm_set, it) }
                ?: tr(R.string.bridge_step_alarm_set_blank)
            "phone_set_timer" -> arg("duration")?.let { format(R.string.bridge_step_timer_set, it) }
                ?: tr(R.string.bridge_step_timer_set_blank)
            "phone_show_alarms" -> tr(R.string.bridge_step_alarms_show)
            "view_past_screens" -> tr(R.string.bridge_step_review)
            "read_file" -> arg("path")?.let { format(R.string.bridge_step_read_file, it.take(28)) }
                ?: tr(R.string.bridge_step_read_file_blank)
            "write_file" -> arg("path")?.let { format(R.string.bridge_step_write_file, it.take(28)) }
                ?: tr(R.string.bridge_step_write_file_blank)
            "share_file" -> arg("path")?.let { format(R.string.bridge_step_share_file, it.take(28)) }
                ?: tr(R.string.bridge_step_share_file_blank)
            else -> format(R.string.bridge_step_other, name)
        }
    } ?: name
}

private fun safe(block: () -> String): String? = runCatching(block).getOrNull()

private fun format(@StringRes id: Int, arg: String): String = tr(id, arg)
