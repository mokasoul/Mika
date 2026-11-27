package com.wangzan101.mika.about

import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import java.net.URLEncoder

/** Mika 对外的地址都在这里:仓库、问题、检查更新用的两个来源。换地址只改这一处。 */
object MikaLinks {
    // 本 fork:壳模式(电脑直接调工具)从这里发更新。
    const val REPO = "https://github.com/mokasoul/Mika"
    const val REPO_LABEL = "github.com/mokasoul/Mika"
    const val README = "$REPO#readme"
    /**
     * 官方发布源:保持 GitHub 不动。两个用途都要留着 ——
     * [ApkIntegrity.official] 从这里派生下载校验前缀;API 挂掉时 [Releases.fromPage]
     * 还要爬 GitHub 发布页兜底。
     */
    const val RELEASES = "$REPO/releases"
    const val NEW_ISSUE = "$REPO/issues/new"
    const val X = "https://x.com/wangzan101"
    const val X_HANDLE = "@wangzan101"
    /**
     * 检查更新问自建分发点(国内直连,快),返回与 GitHub releases/latest 同构的 JSON:
     * tag_name / name / body / published_at / html_url /
     * assets[].{name,browser_download_url,size,digest}。
     * 里面的 browser_download_url **仍然写 GitHub 官方附件地址**,
     * 这样 ApkIntegrity 的校验照旧通过,下载再靠镜像前缀换成国内直连。
     */
    const val LATEST_RELEASE_API = "https://moka.leeapp.top:18648/moka/latest.json"

    /** 反馈时预先填进问题模板的设备与版本信息。 */
    data class Device(
        val manufacturer: String,
        val model: String,
        val androidRelease: String,
        val sdk: Int,
        val versionName: String,
        val build: String,
    )

    /** 预先填好的问题正文:机型、安卓版本、Mika 版本三行,下面留空几段给用户写。 */
    fun issueBody(d: Device): String {
        val model = if (d.model.startsWith(d.manufacturer, ignoreCase = true)) d.model else "${d.manufacturer} ${d.model}"
        return buildString {
            append(tr(R.string.issue_body, model.trim(), d.androidRelease, d.sdk, d.versionName, d.build))
        }
    }

    /** 新建问题的地址,标题留空、正文预先填好;参数按 URL 编码(空格写成 %20)。 */
    fun issueUrl(d: Device): String = "$NEW_ISSUE?title=&body=${encode(issueBody(d))}"

    private fun encode(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}
