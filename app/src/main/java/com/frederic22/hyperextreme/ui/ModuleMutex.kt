package com.frederic22.hyperextreme.ui

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.SystemClock
import com.frederic22.hyperextreme.data.RootShell
import java.io.File

/**
 * **外部模块互锁**（2026-11-15 建立）：本软件与"提取过代码"的三个开源仓库
 * 对应的模块互斥，任一在设备上生效时，本软件相关功能拒绝开启并明确提示。
 *
 * | 仓库 | 对应模块包名 |
 * |---|---|
 * | ReChronoRain/HyperCeiler | `com.sevtinge.hyperceiler` |
 * | leohearts/AlternativeUnlockXposed | `com.leohearts.alternativeUnlockHook` |
 * | Mahmud0808/PixelLauncherEnhanced | `com.drdisagree.pixellauncherenhanced` |
 *
 * ## 检测分层（**全程只读**：拷贝副本查询，不改对方与 LSPosed 的任何文件）
 * 1. **启用态（最准）**：root 把 LSPosed 配置 `modules_config.db`(+`-wal`/`-shm`)
 *    `cp` 到本模块私有目录 → `SQLiteDatabase` 只读查
 *    `modules_state WHERE enabled=1` → 对方**已启用**才拦（装了没启用不拦）。
 * 2. **降级（读不到 db）**：无 root / 非 LSPosed 环境 / 拷贝失败 → 退化为
 *    `PackageManager` **安装态**判定（从严：无法确认启用状态时按互锁规则拦下）。
 * 3. 结果缓存 30s（`TTL`），避免连续点击反复拉起 su；`force` 可绕过。
 *
 * ## 反向半边（本软件对外公示）
 * 本软件的运行状态走既有公开系统属性：`debug.hyperextreme.spatial_active` /
 * `raster_active` / `alive_active`（见 `SpatialFlag/RasterFlag/AliveFlag`）。
 * 对方仓库（或任何第三方）读取这些属性即可实现"对方→本软件"的互锁判断；
 * 本仓库不改对方代码，故该半边以属性协议形式交付。
 *
 * ## 实现备注
 * KDoc 与行内注释中**禁止出现未转义的块注释起始序列**（编译器会当作嵌套注释导致
 * 文件尾部"Unclosed comment"——Beta6 迁移前实测踩过，见 CI run#6 的 DIAG）。下文的
 * 目录描述一律用 `.../config/` 形式，不写通配符星号紧跟斜杠。
 */
internal object ModuleMutex {

    /** 互锁目标：三个开源仓库对应的模块。 */
    enum class External(val pkg: String, val label: String) {
        HYPER_CEILER("com.sevtinge.hyperceiler", "HyperCeiler"),
        ALT_UNLOCK("com.leohearts.alternativeUnlockHook", "AlternativeUnlockXposed"),
        PIX_ENHANCED("com.drdisagree.pixellauncherenhanced", "PixelLauncherEnhanced"),
    }

    /** 命中状态。 */
    enum class State {
        /** 已在 LSPosed 中启用（读到 enabled=1）。 */
        ENABLED,

        /** 已安装，但启用状态未知（降级路径，从严视为占用）。 */
        INSTALLED,
    }

    /** 一次互锁命中：哪个模块 + 什么状态。 */
    data class Hit(val ext: External, val state: State)

    /** 检测结果缓存时长（ms）。 */
    private const val TTL_MS = 30_000L

    /** LSPosed 配置目录（只读来源）。 */
    private const val LSDIR = "/data/adb/lspd/config"

    private const val DB_NAME = "modules_config.db"

    /** su 超时：点击路径在主线程附近执行，必须有界（默认 60s 会 ANR）。 */
    private const val SU_TIMEOUT_S = 3L

    @Volatile
    private var cached: List<Hit>? = null

    @Volatile
    private var cachedAt = 0L

    /**
     * 检测互锁目标（命中返回非空 = 本软件不允许开启）。
     *
     * 耗时量级：命中缓存约 0ms；未命中一次 root 拷贝 + sqlite 只读查询约 100ms
     * （与既有 `RootShell.run("setprop ...")` 点击路径同量级，且有 [SU_TIMEOUT_S] 有界兜底）。
     */
    fun detect(ctx: Context, force: Boolean = false): List<Hit> {
        if (!force) {
            cached?.let { if (SystemClock.elapsedRealtime() - cachedAt < TTL_MS) return it }
        }
        val enabledPkgs = loadEnabledFromLspd(ctx)
        val hits = ArrayList<Hit>(2)
        for (ext in External.entries) {
            if (!installed(ctx, ext.pkg)) continue
            if (enabledPkgs == null) {
                // 读不到 LSPosed 配置（无 root / 非 LSPosed / 拷贝失败）：无法确认启用状态 ⇒ 从严。
                hits.add(Hit(ext, State.INSTALLED))
            } else if (enabledPkgs.contains(ext.pkg)) {
                hits.add(Hit(ext, State.ENABLED))
            }
            // 装了但 enabled=0 / 无记录 ⇒ 未生效，不拦。
        }
        cached = hits
        cachedAt = SystemClock.elapsedRealtime()
        return hits
    }

    /** 提示文案片段（供 [FeatureMutex.deniedMessage] 组装"明确提示"）。 */
    fun describe(h: Hit): String = when (h.state) {
        State.ENABLED -> "${h.ext.label} 已在 LSPosed 中启用"
        State.INSTALLED -> "${h.ext.label} 已安装（当前无法确认其启用状态）"
    }

    /** 是否已安装（进程级查询，无权限要求）。 */
    private fun installed(ctx: Context, pkg: String): Boolean = runCatching {
        ctx.packageManager.getPackageInfo(pkg, 0)
        true
    }.getOrDefault(false)

    /**
     * 读 LSPosed 模块启用集合；`null` = 读不到（调用方降级为安装态判定）。
     *
     * 只读三连：拷贝 db 三件套 → 私有目录 → `OPEN_READONLY` 打开副本查询。
     * 源文件（LSPosed 配置目录下的 db 三件套）只被读取，绝不写入。
     */
    private fun loadEnabledFromLspd(ctx: Context): Set<String>? {
        val dir = File(ctx.filesDir, "lspd_probe").apply { mkdirs() }
        val r = runCatching {
            RootShell.run(
                "cp -f $LSDIR/$DB_NAME* '${dir.absolutePath}/' && " +
                    "chmod 666 '${dir.absolutePath}/'$DB_NAME* && echo probe_ok",
                SU_TIMEOUT_S,
            )
        }.getOrNull()
        if (r == null || !r.ok || !r.output.contains("probe_ok")) return null
        val dbFile = File(dir, DB_NAME)
        if (!dbFile.exists()) return null
        return runCatching {
            val db = SQLiteDatabase.openDatabase(
                dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY,
            )
            db.use { d ->
                d.rawQuery(
                    "SELECT module_pkg_name FROM modules_state WHERE enabled=1",
                    null,
                ).use { c ->
                    val set = HashSet<String>()
                    while (c.moveToNext()) set.add(c.getString(0))
                    set
                }
            }
        }.getOrNull()
    }

    /** 诊断：清缓存（停用对方后立即复检用）。 */
    fun invalidate() {
        cached = null
        cachedAt = 0L
    }
}
