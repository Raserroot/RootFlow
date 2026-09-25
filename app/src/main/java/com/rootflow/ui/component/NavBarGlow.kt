package com.rootflow.ui.component

import android.graphics.Paint
import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas

/**
 * 底栏的**交互高光**（阶段 11b 实现 → **阶段 11c 起不参与生产，纯记录**）。
 *
 * # ★★ 现状：**不要在 `FloatingNavBar` 里挂回它**
 * 本效果在真机上**两次**造成"拖动时整块底栏变死白"：
 *
 * | 轮次 | 表面现象 | 真因 |
 * |---|---|---|
 * | 11b | 拖动时一团不透明纯白 | `main` 返回了**非 premultiplied** 颜色：`float4(1,1,1,0.1)` 里 RGB(1.0) > A(0.1) 是非法值，渲染器当成不透明的白画出来 |
 * | 11c | 修完①后**仍然死白** | `smoothstep(radius, radius * 0.5, dist)` 的 **edge0 >= edge1 是未定义行为**。模拟器（SwiftShader 软件渲染）恰好给出"中心亮、边缘淡"，**真机 GPU 返回恒定 1** ⇒ 整块拉满 |
 *
 * ## 为什么整个摘掉，而不是继续修
 * 1. **两个参照对象的底栏都没有这个效果**：`OPCameraPro 2.10` 是"悬浮胶囊 + 药丸指示器"，
 *    `LSPosed 2.1.1` 是"贴底平栏 + 无指示器、纯靠颜色与字重区分"。跟手光晕是抄第三方 demo
 *    （`Kyant0/AndroidLiquidGlass`）时自己加的，**不是需求**。
 * 2. **它的收益远小于代价**：收益是"按下去有一团光"，代价是**AGSL 在不同 GPU 后端上的
 *    行为差异无法在本项目的验证能力内保证** —— 而本项目没有真机在环（`STAGE11-PLAN.md §5`）。
 * 3. 留档而不是删除：这是一次**有据可查的否决**，将来若要重做，
 *    至少知道"未定义行为"与"premultiplied"这两个坑在这里踩过。
 *    处置方式与 [NavBarElastic] 一致（已评估、不参与生产、保留实现与 KDoc）。
 *
 * ## 若将来真要复活它，必须同时满足
 * - 用**合法顺序**的边沿（`1.0 - smoothstep(0.5r, r, dist)`），不依赖任何未定义行为
 * - 返回 **premultiplied** 颜色（`half4(rgb * a, a)`）
 * - **在真机上按 §8 的判据验过**（模拟器的软件渲染**不能**作为这个效果通过的依据 ——
 *   这正是 11b 误判"已修"的原因）
 *
 * ---
 *
 * ## 原始设计（保留作为技术记录）
 * 一枚跟着手指跑的光晕，实时渲染：AGSL 里按到手指的距离做径向衰减，
 * uniform 每帧更新、shader 对象只建一次。
 *
 * 与参考实现的差异（当时是为了避开浅色底饱和）：
 * | 项 | 参考实现 | 本实现 |
 * |---|---|---|
 * | 混合 | `BlendMode.Plus` / `PorterDuff.Mode.ADD` | `SrcOver`（浅色底上用加法会瞬间饱和）|
 * | 半径 | `1.5 × minDimension` | `0.7 ×`（1.5 几乎盖满整条胶囊）|
 * | 颜色 uniform | `layout(color)` | 普通 `float4`（绕开颜色空间转换）|
 *
 * ## 无可测性
 * `android.graphics.RuntimeShader` 是 final 平台类 ⇒ 纯 JVM 下无法断言，
 * **本文件的行为只能由设备承担**。这也是它被摘掉的原因之一。
 */
internal const val NAV_BAR_GLOW_AGSL: String =
    """
    // ── 契约（静态断言可钉住本段）──────────────────────────────────────────
    // @uniform size       float2   每帧由 navBarGlow 设置
    // @uniform glowColor  float4   RGBA，0..1，**不带 layout(color) 限定符**（见下）
    // @uniform radius     float    光晕半径（minDimension * GLOW_RADIUS_FRACTION）
    // @uniform position   float2   手指在本组件局部坐标里的位置（每帧更新 ⇒ "实时"）

    uniform float2 size;
    uniform float4 glowColor;
    uniform float radius;
    uniform float2 position;

    half4 main(float2 coord) {
        float dist = distance(coord, position);
        // ★★★ 光晕的径向分布 —— **这里曾经是死白的真机根因**（2026-09-25 第二轮）
        //
        // 上一版写的是 `smoothstep(radius, radius * 0.5, dist)`，即 **edge0 > edge1**。
        // GLSL/AGSL 规范明写：**edge0 >= edge1 时结果是未定义的**。
        // - 模拟器（SwiftShader 软件渲染）恰好给出"中心亮、边缘淡"的合理结果 ⇒ 我据此判了"已修"
        // - **真机 GPU 返回了恒定 1** ⇒ 整个矩形强度拉满 ⇒ 用户看到的"拖动时整块死白"
        //
        // 现在写成**合法顺序**的 `1.0 - smoothstep(0.5r, r, dist)`：
        //   dist <= 0.5r ⇒ smoothstep = 0 ⇒ intensity = 1（中心最亮）
        //   dist >= r    ⇒ smoothstep = 1 ⇒ intensity = 0（边缘透明）
        // 这个写法**不依赖任何后端实现**，软件与硬件渲染结果一致。
        float intensity = 1.0 - smoothstep(radius * 0.5, radius, dist);
        // ★★ 必须返回 **premultiplied** 颜色（AGSL/SkSL 的 main 约定）。
        //   写成 `return glowColor * intensity;` 会得到 float4(1, 1, 1, 0.1) ——
        //   RGB(1.0) > A(0.1) 是**非法的 premultiplied 值**，渲染器会把它当成
        //   **不透明的白**画出来。
        //   先把 alpha 算出来、再让 rgb 乘上它，才满足 rgb <= a 的约束。
        float a = glowColor.a * intensity;
        return half4(glowColor.rgb * a, a);
    }
    """

/**
 * 光晕半径 = 本组件**短边**的多少倍。
 *
 * ## 为什么从参考实现的 1.5 改成 0.7
 * 1.5 × 190px（胶囊高）= **285px**，而胶囊总高只有 190px —— 光晕上下都被切掉，
 * 视觉上就是"一整块亮斑"。0.7 得到约 133px 的半径，读起来才是"手指附近的一团光"。
 */
internal const val GLOW_RADIUS_FRACTION: Float = 0.7f

/** 光晕自身颜色的 alpha 系数（乘 `pressProgress`）。 */
internal const val GLOW_CORE_ALPHA: Float = 0.10f

/**
 * 光晕的 RGB 分量（白）。
 *
 * 与 [GLOW_CORE_ALPHA] 一起组成 `glowColor` uniform 的四个分量。
 * 写成常量而不是让调用处散落三个 `1f`：将来若要改成"主题色光晕"，只需要改这一处。
 */
private const val GLOW_RGB: Float = 1f

/** 无 shader 时的均匀提亮 alpha 系数（乘 `pressProgress`）。 */
internal const val GLOW_FALLBACK_ALPHA: Float = 0.12f

/**
 * `RuntimeShader` 的最低 API（与 `GlassPolicy.MIN_REFRACTION_API_LEVEL` 同源）。
 *
 * 26–32 上不建 shader，由 [navBarGlow] 走均匀提亮的降级路径 ——
 * 底栏在那两档仍然"按下去有反应"，只是没有跟随光晕。
 */
private const val MIN_GLOW_API_LEVEL: Int = 33

/**
 * 建一枚（并记住）光晕 shader；不支持的设备返回 `null`。
 *
 * 构造失败**不抛**：`RuntimeShader` 对语法错或图形能力不足会抛异常，
 * 而那是只有设备上能发现的失败。`runCatching` 之后返回 `null`，
 * 调用方据此走降级 —— 与 `AndroidGlassEffectFactory` 的处置完全一致。
 */
@Composable
internal fun rememberNavBarGlowShader(): RuntimeShader? =
    remember {
        if (Build.VERSION.SDK_INT < MIN_GLOW_API_LEVEL) {
            null
        } else {
            runCatching { RuntimeShader(NAV_BAR_GLOW_AGSL) }.getOrNull()
        }
    }

/**
 * 交互高光 modifier：`progress` 为 0 时**什么都不画**（零成本）。
 *
 * ## 为什么画在内容**之上**
 * 参考实现把它画在 `drawContent()` **之前**（光晕在下层）。本版画在**之后**：
 * 底栏的内容（图标 + 文字）是高频细节，若光晕在下层，叠加会被不透明的图标整块吃掉，
 * 只在图标之间的缝隙里露出来 —— 那样"手指按住哪里哪里亮"就不成立了。
 *
 * ## ★ 为什么走原生 Canvas 且**不设 Xfermode**
 * - **原生 Canvas**：Compose 没有公开 `android.graphics.Shader → ui.graphics.Shader`
 *   的转换（Kyant 的 `asComposeShader` 是他自己库里的封装），所以只能 `drawIntoCanvas`。
 * - **不设 Xfermode**：默认即 `SrcOver`。参考实现用 `ADD`，那在**深色底**上没问题，
 *   在 RootFlow 浅色主题的亮底上会**瞬间饱和成纯白**（见文件头对照表）。
 *
 * @param progress 按下进度 `0..1`（由 `pressProgress` 弹簧驱动）。
 *   **传 lambda 而不是值**：它每帧都在变，而 `drawWithContent` 的 lambda 在**绘制阶段**执行
 *   —— 在那里读 State 只会触发重绘；若在组合阶段读，整个底栏会每帧重组。
 * @param position 手指在**本组件局部坐标**里的位置（每帧读取 ⇒ 光晕跟手）
 */
@Composable
internal fun Modifier.navBarGlow(
    progress: () -> Float,
    position: () -> Offset,
): Modifier {
    val shader = rememberNavBarGlowShader()
    // Paint 必须**复用**：每帧 new 一个会被 GC 拖累（底栏是按住的每一帧都在画）
    val paint = remember { Paint() }
    return this.drawWithContent {
        drawContent()
        val press = progress()
        if (press <= 0f) return@drawWithContent

        if (shader == null) {
            drawRect(color = Color.White.copy(alpha = GLOW_FALLBACK_ALPHA * press))
            return@drawWithContent
        }

        val point = position()
        shader.setFloatUniform("size", size.width, size.height)
        // ★★ 用 float4 而**不是** `layout(color)` + `setColorUniform`（真机/模拟器实测踩出来的）：
        //   走 `layout(color)` 那条路时，alpha **没有**传到 shader 端 —— color 拿到的是不透明的默认白，
        //   于是 `intensity == 1` 的内圈被画成**纯白不透明**，整块底栏变成一团死白。
        //   直接传 RGBA 绕开颜色空间转换，alpha 才如实生效（0.10 的白在亮底上只该有约 1/255 的变化）。
        shader.setFloatUniform(
            "glowColor",
            GLOW_RGB,
            GLOW_RGB,
            GLOW_RGB,
            GLOW_CORE_ALPHA * press,
        )
        shader.setFloatUniform("radius", size.minDimension * GLOW_RADIUS_FRACTION)
        shader.setFloatUniform(
            "position",
            point.x.coerceIn(0f, size.width),
            point.y.coerceIn(0f, size.height),
        )
        drawIntoCanvas { canvas ->
            paint.shader = shader
            // 不设 xfermode ⇒ SrcOver。
            // 注意：**不要**在这里加 `paint.xfermode = ADD_XFERMODE` —— 那正是死白的成因，
            // 详见文件头「必须偏离参考实现的地方」。
            canvas.nativeCanvas.drawRect(0f, 0f, size.width, size.height, paint)
        }
    }
}
