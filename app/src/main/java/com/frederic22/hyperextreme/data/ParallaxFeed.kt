package com.frederic22.hyperextreme.data

import android.content.Context

/**
 * 把视差用的两张图推送进 `com.miui.miwallpaper` 进程（2026-10-21）。
 *
 * ## 为什么需要推送
 *
 * 桌面壁纸由 miwallpaper 的 GL 管线绘制，视差要复刻锁屏效果就得在那一层画我们的图。
 * 但 miwallpaper 是**独立 app**（uid `u0_a184`），跨进程读不到模块私有目录：
 * 实测 `getRemotePreferences` / `openRemoteFile` / provider 在它里面全不可用（见 ParallaxDesktopGuard 注释），
 * 所以图片只能**落进它自己的 data 目录**，靠 root 复制 + chown。
 *
 * ## 落点
 *
 * ```
 * /data/data/com.miui.miwallpaper/files/he_parallax_bg.png       背景（用户导入的视差背景）
 * /data/data/com.miui.miwallpaper/files/he_parallax_subject.png  主体（theme_magic 的 crop_subject 抠像）
 * ```
 *
 * 主体缺失（用户没设主体）时删掉旧文件，让绘制侧按"单层"处理。
 * 图更新后由 miwallpaper 侧按文件 mtime 自动重载，不需要重启进程。
 */
internal object ParallaxFeed {

    /** miwallpaper 的私有 files 目录（root 可写）。 */
    const val DIR = "/data/data/com.miui.miwallpaper/files"
    const val BG_NAME = "he_parallax_bg.png"
    const val SUBJECT_NAME = "he_parallax_subject.png"

    /**
     * 主体的源头：编辑器生成的透明抠像，与「已应用壁纸」逐像素对齐。
     *
     * ⚠️ 2026-11-19：原来硬编码 `users/0`，多用户 / 工作资料下必然指错；
     * 改成按本进程 uid 反推 userId（与 HookRuntime.currentUserId 同一套口径：
     * uid = userId*100000 + appId）。副用户里跑时也能拿到自己那份。
     */
    private fun cropSubjectPath(): String =
        "/data/system/theme_magic/users/${android.os.Process.myUid() / 100_000}/wallpaper/crop_subject"

    /**
     * 把当前背景（+ 主体）推进 miwallpaper。需 root；同步执行，调用方自己丢后台线程。
     *
     * **背景可以没有**：锁屏视差的背景本来就是系统壁纸（多数用户没导入自定义背景），
     * 那种情况下 GL 层只叠主体，背景交给桌面壁纸层自己的位移。
     *
     * @return true = 至少有一张图就位
     */
    fun push(context: Context): Boolean {
        val bg = MaskStore.parallaxFile(context)
        val hasBg = bg.isFile && bg.length() > 0L
        // chown 到 miwallpaper 自己的 uid/gid：目录 owner 就是它的 uid，直接借用。
        // restorecon 让 SELinux 上下文落到 app_data_file（cp 过去默认可能继承成别的类型）。
        val script = buildString {
            append("D=$DIR; ")
            append("mkdir -p \$D; ")
            if (hasBg) {
                append("cp '${bg.absolutePath}' \$D/$BG_NAME || exit 3; ")
            } else {
                append("rm -f \$D/$BG_NAME; ")
            }
            append("if [ -f ${cropSubjectPath()} ]; then cp ${cropSubjectPath()} \$D/$SUBJECT_NAME; ")
            append("else rm -f \$D/$SUBJECT_NAME; fi; ")
            append("OWN=\$(stat -c %u:%g /data/data/com.miui.miwallpaper); ")
            append("chown \$OWN \$D/$BG_NAME \$D/$SUBJECT_NAME 2>/dev/null; ")
            append("chmod 600 \$D/$BG_NAME \$D/$SUBJECT_NAME 2>/dev/null; ")
            append("restorecon \$D/$BG_NAME \$D/$SUBJECT_NAME 2>/dev/null; ")
            append("ls -l \$D/$BG_NAME \$D/$SUBJECT_NAME 2>/dev/null")
        }
        // 2026-11-19：**「没东西可推」不是失败**。原实现里 bg=false 且没有 crop_subject 时，
        // 脚本只剩 rm，`ls` 无输出 ⇒ listed 为空 ⇒ 判失败，run_log 里留下一条
        // 「视差推图（失败）失败（bg=false）：」—— 用户看到的就是"视差永远推不上去"，
        // 而真实状态只是"还没导入背景图"。这里先短路，日志也换个说法。
        val hasSubject = java.io.File(cropSubjectPath()).isFile
        if (!hasBg && !hasSubject) {
            RunLog.append(
                context,
                "视差推图",
                listOf("无需推送：没有自定义背景，也没有锁屏主体（先在视差页导入一张背景图）"),
                true,
            )
            return true
        }
        val result = RootShell.run(script)
        val listed = result.output.trim()
        if (!result.ok || listed.isEmpty()) {
            RunLog.append(
                context,
                "视差推图",
                listOf("失败（bg=$hasBg，subject=$hasSubject）：${listed.take(200)}"),
                false,
            )
            return false
        }
        RunLog.append(
            context,
            "视差推图",
            listOf("bg=$hasBg | " + listed.lines().joinToString(" | ") { it.trim() }),
            true,
        )
        return true
    }

    /** 关掉开关时清掉，避免 miwallpaper 侧还留着旧图（不清也不影响，只是占空间）。 */
    fun clear(): Boolean =
        RootShell.run("rm -f $DIR/$BG_NAME $DIR/$SUBJECT_NAME").ok
}
