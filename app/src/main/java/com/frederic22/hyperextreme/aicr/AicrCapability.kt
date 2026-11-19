package com.frederic22.hyperextreme.aicr

import android.content.Context
import android.content.pm.PackageManager

/**
 * AICR（`com.xiaomi.aicr`，澎湃 AI 引擎）的**本机能力档案**（2026-11-19）。
 *
 * ## 为什么要单独记一份
 *
 * 抠图这条路有三个"看起来都成功、其实没结果"的坑，光看返回值分不清：
 *
 * 1. **服务能绑上、插件能拿到，但 `segmentByFd` 回空列表** ——
 *    本机 AICR **4.13.6** 实测就是这样（`run_log` 连续 3 组「服务返回空位图」，
 *    每组还会因为"直送 + PNG 兜底"各等一次 8s 超时）。模块对标的却是 **4.13.10**。
 * 2. 插件还在下载（`STATUS_DOWNLOADING`）—— 这条 `AicrProtocol.awaitPlugin` 已经能区分。
 * 3. 图里确实没有可抠的主体 —— 与 (1) 的表象完全相同。
 *
 * 三种都表现为"失败"，但用户该做的事完全不同：升级引擎 / 等下载 / 换张图。
 * 所以这里做两件事：
 *
 * * **记下装了哪个版本**（写进 run_log 与日志，排查时第一眼就能对上口径）；
 * * **连续空位图就短路**（不再每组白等 8s×2 + 3 张图连跑），
 *   并把原因固化成一句可执行的话。任何一次成功都会清零 —— 换图/升级后自动恢复。
 */
internal object AicrCapability {

    /** 连续多少次"服务回 OK 但没有位图"就认为本机这条路不可用。 */
    private const val EMPTY_STREAK_LIMIT = 2

    @Volatile private var emptyStreak = 0

    @Volatile private var disabledReason: String? = null

    /** 版本号只查一次（PackageManager 是 binder 调用，别每张图都问）。 */
    @Volatile private var versionCache: String? = null

    @Volatile private var versionQueried = false

    /** 已安装的 AICR 版本名；装不上/查不到返回 null。 */
    fun installedVersion(ctx: Context): String? {
        if (versionQueried) return versionCache
        versionQueried = true
        versionCache = runCatching {
            ctx.packageManager.getPackageInfo(AicrProtocol.PKG, 0).versionName
        }.getOrNull()
        return versionCache
    }

    /** 版本是否与模块对标的口径一致（只用于日志措辞，不做功能门控）。 */
    fun matchesReferenceVersion(ctx: Context): Boolean {
        val v = installedVersion(ctx) ?: return false
        return v == REFERENCE_VERSION
    }

    /** 当前是否已被判定为不可用；可用返回 null。 */
    fun unavailableReason(): String? = disabledReason

    /** 成功一次就把短路状态清掉（换图 / 升级引擎后不必重启）。 */
    fun noteSuccess() {
        emptyStreak = 0
        disabledReason = null
    }

    /**
     * 记一次"服务回 OK 但没有位图"。
     *
     * @return true = 本次之后进入短路状态（调用方可以据此把文案换成"本机不可用"口径）
     */
    fun noteEmpty(ctx: Context): Boolean {
        emptyStreak += 1
        if (emptyStreak < EMPTY_STREAK_LIMIT) return false
        if (disabledReason == null) {
            val v = installedVersion(ctx) ?: "未知版本"
            disabledReason = buildString {
                append("本机 AICR $v 的抠图服务连续 ${emptyStreak} 次返回空结果")
                if (!matchesReferenceVersion(ctx)) {
                    append("（模块对标 $REFERENCE_VERSION）")
                }
                append("：请先升级「澎湃 AI 引擎」，或在相册里用一次「AI 抠图」让插件就绪后重试")
            }
        }
        return true
    }

    /** 模块里写死的对标版本 —— 与 `HookEntry.BUILD_TAG` 记的那台机器一致。 */
    const val REFERENCE_VERSION = "4.13.10"

    /** 版本口径差异（用于 run_log 头一行，别在每张图里重复）。 */
    fun describeVersion(ctx: Context): String {
        val v = installedVersion(ctx) ?: return "AICR 未安装"
        return if (v == REFERENCE_VERSION) "AICR $v（与模块对标一致）" else "AICR $v（模块对标 $REFERENCE_VERSION）"
    }

    /** 仅供测试/诊断：把状态清空（`clear()` 之类的入口可以调）。 */
    fun reset() {
        emptyStreak = 0
        disabledReason = null
    }
}
