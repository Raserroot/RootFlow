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
 * 底栏的**交互高光**（阶段 11b）—— 一枚跟着手指跑的光晕，实时渲染。
 *
 * ## 它解决什么问题
 * 阶段 11 的第一版底栏在按住时**毫无反馈**：指示器只是按弹簧滑过去，
 * 玻璃本身不动。缺失的正是"液态"最直观的那一半：
 * **手指按住哪里，哪里就应该亮起来**。
 *
 * ## ★★ 一处**必须偏离参考实现**的地方（真机/模拟器实测踩出来的）
 * 参考实现 [`Kyant0/AndroidLiquidGlass`](https://github.com/Kyant0/AndroidLiquidGlass)
 * 的 `InteractiveHighlight` 用 `BlendMode.Plus`（即 `PorterDuff.Mode.ADD`，**加法饱和**）。
 * 本版照抄之后，**在模拟器上拖动时整块底栏变成一团死白** —— 根因不是 shader 写错，而是**底色不同**：
 *
 * | | 参考实现 | RootFlow |
 * |---|---|---|
 * | 底栏底色 | 深色（`Color(0xFF121212).copy(0.4f)`） | 浅色主题下是 `surface` + 玻璃，约 **`#F7F7F7`** |
 * | ADD 0.15 白的结果 | 明显提亮，不溢出 | `247 + 38 = 285` ⇒ **溢出，纯白** |
 *
 * 加法混合在**已经接近 255 的底色**上没有任何"提亮空间"：任何强度的白都会瞬间饱和。
 * 因此本版改用 **`SrcOver`（普通 alpha 叠加）**，并收窄半径、降低强度：
 *
 * | 常量 | 照抄参考的值 | 本版 | 理由 |
 * |---|---|---|---|
 * | 混合模式 | `ADD` | `SrcOver` | 浅底上不会饱和（见上表） |
 * | [GLOW_RADIUS_FRACTION] | `1.5` | `0.7` | 1.5 × 190px = 285px，几乎盖满整条胶囊 |
 * | [GLOW_CORE_ALPHA] | `0.15` | `0.10` | 配合 SrcOver 的观感取值 |
 * | 整层 wash | 有（`Plus`） | **删除** | 它是"整片白"的直接来源，且 SrcOver 下与光晕重复 |
 *
 * ## 为什么是"实时"的
 * `RuntimeShader` 的 uniform 在**每次绘制**前重设（`position` 直接取手势当前坐标），
 * 而 shader 对象本身**只建一次**。这与 `LiquidGlassRenderer` 的纪律一致：
 * **缓存对象、更新参数**，绝不每帧重建。
 *
 * ## 无可测性（诚实声明）
 * `android.graphics.RuntimeShader` 是 final 平台类，纯 JVM 下会抛
 * `Method … not mocked` ⇒ **本文件的行为由设备承担**。
 * 能测的只有源码字符串的静态断言（见 [NAV_BAR_GLOW_AGSL] 的契约注释）。
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
        // ★ 边沿顺序（radius → radius/2）是**反的**，这一点必须照抄参考实现：
        //   GLSL 规范说 edge0 >= edge1 时结果未定义，但两侧的 clamp 会把 t 拉回 0..1，
        //   于是得到"离手指越近越亮"的径向分布。
        //   若改成 (0.5r → r)，中心会变成暗的、外圈亮 —— 那是空心甜甜圈，不是光晕。
        float intensity = smoothstep(radius, radius * 0.5, dist);
        // ★★ 必须返回 **premultiplied** 颜色（AGSL/SkSL 的 main 约定）。
        //   写成 `return glowColor * intensity;` 会得到 float4(1, 1, 1, 0.1) ——
        //   RGB(1.0) > A(0.1) 是**非法的 premultiplied 值**，渲染器会把它当成
        //   **不透明的白**画出来：这就是"拖动时整块底栏变死白"的最终成因。
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
