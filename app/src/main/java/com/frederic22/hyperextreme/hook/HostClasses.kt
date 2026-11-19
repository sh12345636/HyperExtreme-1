package com.frederic22.hyperextreme.hook

/**
 * 宿主类/方法的**跨版本解析**（2026-11-19，HyperOS 3 适配）。
 *
 * ## 为什么需要它
 *
 * 模块里所有 hook 目标都是按「澎湃 OS4 的类名 + 方法名」硬编码的（见 [HookEntry] 的常量区）。
 * 真机实测澎湃 OS3（OS3.0.5.0 / Android 15 / Redmi Note 12 Turbo）时，SystemUI 里
 * **类名换了、方法名没换**：
 *
 * | 模块写的类（OS4） | 本 ROM（OS3）实际所在类 | 实测依据 |
 * | --- | --- | --- |
 * | `com.android.keyguard.panel.KeyguardPanelViewController` | `com.android.keyguard.injector.KeyguardPanelViewInjector` | `updateKeyguardElementsVisibility()` / `updateShowDepthState()` 均声明于此 |
 * | 同上 | `com.android.systemui.shade.MiuiNotificationPanelViewController` | `doWallpaperBlackAnim(int,float,boolean,boolean)` / `doBlurAndDim(float,boolean,boolean)` / `startUnlockKeyguardDismissAnim(...)` 均声明于此 |
 *
 * 于是 32 条「未找到 KeyguardPanelViewController」把**空间壁纸的撤前景层、AOD 压暗、解锁淡出**
 * 与**视差的模糊同步**一起带走了 —— 壁纸主体还能显示（那是轮询 + SurfaceControl 驱动的），
 * 附属效果全静默失效。
 *
 * ## 解决方式（刻意做成"只改解析、不改业务"）
 *
 * 调用方仍然写 OS4 的类名；[resolve] 先按原名找，**原名存在且带这个方法就照旧用**（OS4 行为
 * 逐字不变）；只有原名找不到（或原名里没有这个方法）时，才按**方法名**去候选类里找。
 * 这样：
 * * OS4 上零行为变化 —— 首选永远是原名；
 * * OS3 上自动落到替代类；
 * * 解析结果只打一条日志（含 fallback 标记），排障时一眼能看出到底挂到了谁身上。
 *
 * ⚠️ 只按「方法名 + 是否存在」判断，**不猜签名**：签名差异由各链自己按名字扫描重载处理
 * （OS3 的 `doBlurAndDim` 是 3 参、OS4 是 4 参，硬取签名在两个版本里必坏一个）。
 */
internal object HostClasses {

    /**
     * 方法名 → 替代类名候选（新→旧）。**只列实测存在的那几个**，不猜。
     *
     * 键是方法名而不是类名，因为 OS3 把同一族方法拆到了两个类里：
     * `updateKeyguardElementsVisibility` / `updateShowDepthState` 留在 keyguard 的 injector；
     * `doWallpaperBlackAnim` / `doBlurAndDim` / `startUnlockKeyguardDismissAnim` 跑到 shade 的
     * `MiuiNotificationPanelViewController`。
     */
    private val METHOD_FALLBACKS: Map<String, List<String>> = mapOf(
        // —— 锁屏面板视图族（HyperOS 3：keyguard/injector/KeyguardPanelViewInjector）——
        "updateKeyguardElementsVisibility" to listOf(
            "com.android.keyguard.injector.KeyguardPanelViewInjector",
        ),
        "updateShowDepthState" to listOf(
            "com.android.keyguard.injector.KeyguardPanelViewInjector",
        ),
        // —— 通知/锁屏面板控制器族（HyperOS 3：shade/MiuiNotificationPanelViewController）——
        "doWallpaperBlackAnim" to listOf(
            "com.android.systemui.shade.MiuiNotificationPanelViewController",
        ),
        "doBlurAndDim" to listOf(
            "com.android.systemui.shade.MiuiNotificationPanelViewController",
        ),
        "startUnlockKeyguardDismissAnim" to listOf(
            "com.android.systemui.shade.MiuiNotificationPanelViewController",
        ),
        // —— 空间壁纸的模糊同步（OS3 实测 SystemUI 里没有 setWallpaperSyncBlur，列出来是为了
        //    以后某个版本回来了能自动接上；找不到就是 null，调用方本来就允许 null）——
        "setWallpaperSyncBlur" to listOf(
            "com.android.systemui.shade.MiuiNotificationPanelViewController",
            "com.android.keyguard.injector.KeyguardPanelViewInjector",
        ),
    )

    /** 同一个 (类, 方法) 只打一次日志，避免每帧刷屏。 */
    private val logged = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /**
     * 解析出**真正要 hook 的类**。
     *
     * @param logicalClass 模块里写的类名（OS4 口径）
     * @param methodName 即将 hook 的方法名；用它判断"原类里有没有这个方法"
     * @return 找到的类；没有返回 null（调用方按"该链本版本不支持"处理）
     */
    fun resolve(logicalClass: String, methodName: String, loader: ClassLoader): Class<*>? {
        // ① 原名优先：存在**且**自己声明了这个方法就用它 —— OS4 走的就是这条。
        val direct = runCatching { Class.forName(logicalClass, false, loader) }.getOrNull()
        if (direct != null && declaresMethod(direct, methodName)) return direct

        // ② 原名不在（或没有这个方法）→ 按方法名找替代类。
        val candidates = METHOD_FALLBACKS[methodName].orEmpty()
        for (name in candidates) {
            if (name == logicalClass) continue
            val cls = runCatching { Class.forName(name, false, loader) }.getOrNull() ?: continue
            if (!declaresMethod(cls, methodName)) continue
            logOnce(
                "$logicalClass#$methodName -> $name",
                "宿主类解析：$logicalClass#$methodName 本 ROM 不存在，改用 $name（跨版本 fallback）",
            )
            return cls
        }

        val why = if (direct == null) "类不存在" else "类里没有该方法"
        logOnce(
            "$logicalClass#$methodName:null",
            "宿主类解析：$logicalClass#$methodName 无法解析（$why，且无替代）—— 该链在本版本停用",
        )
        return null
    }

    /** 类自己（含父类）是否声明了某方法。`hookAll` 会沿父类找，这里判定口径保持一致。 */
    fun declaresMethod(clazz: Class<*>, methodName: String): Boolean {
        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            if (current.declaredMethods.any { it.name == methodName }) return true
            current = current.superclass
        }
        return false
    }

    /**
     * 收集**同名全部重载**（跳过 Kotlin 生成的 `$default` 桥接）。
     *
     * 用于"名字对得上、签名可能随版本变"的方法：OS3 的 `doBlurAndDim(float,boolean,boolean)`
     * 与 OS4 的 4 参版本，用名字扫描一次就能同时覆盖，不必写死参数表。
     */
    fun overloads(clazz: Class<*>, methodName: String): List<java.lang.reflect.Method> {
        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            val found = current.declaredMethods.filter {
                it.name == methodName && !it.name.endsWith("\$default")
            }
            if (found.isNotEmpty()) return found
            current = current.superclass
        }
        return emptyList()
    }

    private fun logOnce(key: String, message: String) {
        if (logged.putIfAbsent(key, true) == null) HookRuntime.log(message)
    }
}
