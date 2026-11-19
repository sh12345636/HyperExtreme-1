package com.frederic22.hyperextreme.ui

import android.content.Context
import android.os.Handler
import android.os.Looper

/**
 * 功能 A / B **拒绝式互斥锁**（2026-11-15 建立；2026-11-19 迁移适配 Beta6 的异步注入结构）。
 *
 * 与 [FeatureConflictDialog]（弹窗式，允许"关掉对方再开"）分工：
 * **A=空间壁纸 ↔ B=光栅壁纸** 这一对走本锁 —— 对方已在运行时**直接拒绝开启并提示**，
 * 不给"顺手关掉"的选项；视差 / Alive 与其他链的冲突仍走原弹窗，行为不变。
 *
 * ## 两层设计（适配 Beta6 的 `xxxNow` 拆分）
 *
 * Beta6 起两页的开启都拆成「入口」与「注入」两段，且入口到注入之间可能**排队等待**
 * （`WallpaperProc.applyWhenReady`，等壁纸进程可注入）：
 *
 * | 层 | 调用点 | 方法 | 作用 |
 * |---|---|---|---|
 * | 入口层 | 点按路径（`enableSpatial` / `applyGroup` 调用点之前） | [checkOnly] | **只检查不占用**：快速失败、提示友好 |
 * | 注入层 | `enableSpatialNow` / `applyGroupNow`（真正写 prefs 的那一帧） | [tryBeginEnable] | **检查并占位**，写完后 [endEnable] 释放 |
 *
 * 两层合起来才能覆盖 TOCTOU：排队等待期间对方可能被开启 ⇒ 注入层必须再检一次。
 * 入口层只检查，所以不会与注入层的占位互相卡死（同一功能的两次点击也不会自锁）。
 *
 * ## 状态标识（需求 1）
 * * [Feature]：互斥对成员（A/B），label 直接用于提示文案；
 * * [holder]：当前占用"注入窗口"的成员（仅注入进行中非空，供诊断与防呆查询）；
 * * **长期互斥真值不在锁里**：以 [WallpaperConflict.detect]（prefs + 壁纸服务 + 视差 state）
 *   为准 —— 即使脏数据让两条链同时"开着"，下一次开启仍会在检测处被拦下。
 *
 * ## 检测逻辑（需求 2）
 * * 对偶成员在跑 ⇒ [Begin.Denied]，[deniedMessage] 提示，功能保持关闭；
 * * 视差 / Alive（不在本对内）⇒ 放行 [Begin.Ok]，由调用方原有弹窗路径接管；
 * * 外部三仓模块（HyperCeiler / AU / PLE）已生效 ⇒ [Begin.DeniedExternal]，见 [ModuleMutex]。
 *
 * ## 并发 / 重复点击（需求 3）
 * `synchronized(lock)` 内完成"读真值 + 置 acquiring"两步 ⇒ 双击 / 并发只有一个拿到
 * [Begin.Ok]，其余拿到 [Begin.Busy]。acquiring 另有 5s 看门狗兜底释放，
 * 防调用方异常路径漏调 [endEnable] 导致锁永久卡死。
 */
internal object FeatureMutex {

    /** 状态标识：互斥对的两个成员。 */
    enum class Feature(val label: String) {
        /** 功能 A：空间壁纸。 */
        A_SPATIAL("空间壁纸"),

        /** 功能 B：光栅壁纸。 */
        B_RASTER("光栅壁纸"),
        ;

        /** 对偶成员。 */
        val pair: Feature
            get() = when (this) {
                A_SPATIAL -> B_RASTER
                B_RASTER -> A_SPATIAL
            }

        /** 对应的壁纸链（本锁只管辖 A/B 对；其他链的冲突仍走 FeatureConflictDialog）。 */
        val chain: WallpaperChain
            get() = when (this) {
                A_SPATIAL -> WallpaperChain.SPATIAL
                B_RASTER -> WallpaperChain.RASTER
            }
    }

    /** 一次开启请求的裁决（调用方必须穷尽处理三个分支）。 */
    sealed class Begin {
        /** 通过：可继续（[checkOnly] 不占用；[tryBeginEnable] 已占用）。 */
        data object Ok : Begin()

        /** 上一次注入还在进行（重复点击 / 并发点击）：本次点击丢弃。 */
        data object Busy : Begin()

        /** 被对偶成员占用：拒绝开启，本功能保持关闭（[deniedMessage] 给出提示文案）。 */
        data class Denied(val holder: Feature, val self: Feature) : Begin()

        /** 被**外部三仓模块**互锁拦截（HyperCeiler / AU / PLE），详见 [ModuleMutex]。 */
        data class DeniedExternal(val hit: ModuleMutex.Hit, val self: Feature) : Begin()
    }

    /** 注入窗口超时（ms）：看门狗兜底释放，防漏 endEnable 卡死后续开启。 */
    private const val ACQUIRE_TIMEOUT_MS = 5_000L

    private val lock = Any()

    /** 注入窗口是否被占用（true = 某个功能正在执行"检测→写入"）。 */
    private var acquiring = false

    /** 当前占用者（仅 acquiring 期间非空）。 */
    private var holder: Feature? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val watchdog = Runnable {
        synchronized(lock) {
            if (acquiring) {
                acquiring = false
                holder = null
            }
        }
    }

    /** 链 → 本锁成员（视差 / Alive 不在对内，返回 null）。 */
    private fun featureOf(chain: WallpaperChain): Feature? = when (chain) {
        WallpaperChain.SPATIAL -> Feature.A_SPATIAL
        WallpaperChain.RASTER -> Feature.B_RASTER
        WallpaperChain.PARALLAX -> null
        WallpaperChain.ALIVE -> null
    }

    /** 公共检测：对偶成员 / 外部三仓模块是否占用（不含占位）。 */
    private fun detectOccupied(ctx: Context, state: MaskUiState, self: Feature): Begin {
        val other = WallpaperConflict.running(ctx, state, self.chain)
        val otherFeature = other?.let { featureOf(it.chain) }
        if (otherFeature != null) {
            // running() 已排除同 chain，这里剩下的必是对偶成员。
            return Begin.Denied(otherFeature, self)
        }
        val extHit = ModuleMutex.detect(ctx).firstOrNull()
        if (extHit != null) return Begin.DeniedExternal(extHit, self)
        return Begin.Ok
    }

    /**
     * **入口层：只检查、不占用**（不置 acquiring，因此不会阻塞后续 [tryBeginEnable]）。
     *
     * 返回 [Begin.Ok] 表示"当时没有占用"，调用方继续走原流程；
     * 真正占位在注入层（[tryBeginEnable]）完成 —— 排队等待期间的 TOCTOU 由注入层兜住。
     */
    fun checkOnly(ctx: Context, state: MaskUiState, self: Feature): Begin = synchronized(lock) {
        if (acquiring) return Begin.Busy
        detectOccupied(ctx, state, self)
    }

    /**
     * **注入层：检查 + 占位**（在真正写 prefs 的那一帧调用）。
     *
     * 返回 [Begin.Ok] 后调用方必须在注入结束时调 [endEnable]
     * （同步注入放函数尾；有 early return 的用 try/finally；另有 5s 看门狗兜底）。
     */
    fun tryBeginEnable(ctx: Context, state: MaskUiState, self: Feature): Begin =
        synchronized(lock) {
            // 重复 / 并发点击：上一次注入还没走完，直接丢弃。
            if (acquiring) return Begin.Busy

            val verdict = detectOccupied(ctx, state, self)
            if (verdict !is Begin.Ok) return verdict

            // 占用注入窗口（读真值与置位在同一把锁内 ⇒ 并发只有一个能过）。
            acquiring = true
            holder = self
            mainHandler.removeCallbacks(watchdog)
            mainHandler.postDelayed(watchdog, ACQUIRE_TIMEOUT_MS)
            Begin.Ok
        }

    /** 注入结束（无论成败）必须调用；幂等。 */
    fun endEnable() {
        synchronized(lock) {
            mainHandler.removeCallbacks(watchdog)
            acquiring = false
            holder = null
        }
    }

    /** 拒绝提示（需求 2 的"明确提示"）：指名道姓说清谁占着 / 哪个外部模块拦的、要用户做什么。 */
    fun deniedMessage(begin: Begin): String = when (begin) {
        is Begin.Denied ->
            "无法开启${begin.self.label}：${begin.holder.label}正在运行，请先关闭${begin.holder.label}"
        is Begin.DeniedExternal ->
            "无法开启${begin.self.label}：检测到 ${ModuleMutex.describe(begin.hit)}，" +
                "按互锁规则请先停用/卸载对方模块"
        else -> ""
    }

    /** 重复点击 / 并发点击提示（需求 3）。 */
    fun busyMessage(): String = "上一次开启还在处理，请稍候"

    /** 诊断：注入窗口当前是否被占用。 */
    fun isAcquiring(): Boolean = synchronized(lock) { acquiring }

    /** 诊断：当前占用者（null = 无人占用）。 */
    fun currentHolder(): Feature? = synchronized(lock) { holder }
}
