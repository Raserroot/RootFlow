package com.rootflow.ui.component

import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb

/**
 * 底栏的**交互高光**（阶段 11b）—— 一枚跟着手指跑的光晕，实时渲染。
 *
 * ## 它解决什么问题
 * 阶段 11 的第一版底栏在按住时**毫无反馈**：指示器只是按弹簧滑过去，
 * 玻璃本身不动。用户实测反馈「还是不行」—— 缺的正是"液态"最直观的那一半：
 * **手指按住哪里，哪里就应该亮起来**。
 *
 * 本文件移植参考实现 [`Kyant0/AndroidLiquidGlass`](https://github.com/Kyant0/AndroidLiquidGlass)
 * 的 `InteractiveHighlight.kt`：
 *
 * | 要素 | 参考实现 | 本文件 |
 * |---|---|---|
 * | 光晕形状 | AGSL 里按到手指的距离做 `smoothstep` | [NAV_BAR_GLOW_AGSL] 逐字一致 |
 * | 混合方式 | `BlendMode.Plus`（加色） | 同 |
 * | 光晕半径 | `size.minDimension * 1.5f` | [GLOW_RADIUS_FRACTION] |
 * | 叠加底色 | 整层 `White.copy(0.08f * progress)` | 同 |
 * | 降级 | 无 shader 时画均匀的 `White.copy(0.25f * progress)` | 同 |
 *
 * ## 为什么是"实时"的
 * `RuntimeShader` 的 uniform 在**每次绘制**前重设（`position` 直接取手势当前坐标），
 * 而 shader 对象本身**只建一次**。这与 `LiquidGlassRenderer` 的纪律一致：
 * **缓存对象、更新参数**，绝不每帧重建。
 *
 * ## 无可测性（诚实声明）
 * `android.graphics.RuntimeShader` 是 final 平台类，纯 JVM 下会抛
 * `Method … not mocked` ⇒ **本文件的行为 100% 由真机承担**。
 * 能测的只有源码字符串的静态断言（见 [NAV_BAR_GLOW_AGSL] 的契约注释）。
 */
internal const val NAV_BAR_GLOW_AGSL: String =
    """
    // ── 契约（GlassShaderSourceTest 风格的静态断言可钉住本段）──────────────────
    // @uniform size      float2   每帧由 navBarGlow 设置
    // @uniform color     half4    layout(color) 限定符 ⇒ Kotlin 侧走 setColorUniform
    // @uniform radius    float    光晕半径（minDimension * GLOW_RADIUS_FRACTION）
    // @uniform position  float2   手指在本组件局部坐标里的位置（每帧更新 ⇒ "实时"）

    uniform float2 size;
    layout(color) uniform half4 color;
    uniform float radius;
    uniform float2 position;

    half4 main(float2 coord) {
        float dist = distance(coord, position);
        float intensity = smoothstep(radius, radius * 0.5, dist);
        return color * intensity;
    }
    """

/**
 * 光晕半径 = 本组件**短边**的多少倍。
 *
 * 1.5 来自参考实现。它大于 1 是有意的：光晕要**溢出**底栏边界，
 * 让边缘那圈玻璃也被照亮 —— 若取 1.0，光晕正好被边框切断，看起来像"贴了个圆片"。
 */
internal const val GLOW_RADIUS_FRACTION: Float = 1.5f

/** 整层提亮的 alpha 系数（乘 `pressProgress`）。 */
internal const val GLOW_WASH_ALPHA: Float = 0.08f

/** 光晕自身颜色的 alpha 系数（乘 `pressProgress`）。 */
internal const val GLOW_CORE_ALPHA: Float = 0.15f

/** 无 shader 时的均匀提亮 alpha 系数（乘 `pressProgress`）。 */
internal const val GLOW_FALLBACK_ALPHA: Float = 0.25f

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
 * 而那是只有真机能发现的失败。`runCatching` 之后返回 `null`，
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
 * 底栏的内容（图标 + 文字）相对光晕是高频细节，若光晕在下层，
 * 加色混合会被不透明的图标整块吃掉，只在图标之间的缝隙里露出来 ——
 * 那样"手指按住哪里哪里亮"就不成立了。
 *
 * 画在上层 = 一层掠过玻璃表面的反光。用 `BlendMode.Plus` 而不是普通叠加，
 * 是为了让它**提亮**而不是**覆盖**：盖一层半透明白会让图标发灰，
 * 加色混合则只在亮处叠光，暗处保持原样。
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

        // 整层提亮：让"按住了"这件事在光晕之外也成立
        drawRect(
            color = Color.White.copy(alpha = GLOW_WASH_ALPHA * press),
            blendMode = BlendMode.Plus,
        )

        if (shader == null) {
            drawRect(
                color = Color.White.copy(alpha = GLOW_FALLBACK_ALPHA * press),
                blendMode = BlendMode.Plus,
            )
            return@drawWithContent
        }

        val point = position()
        shader.setFloatUniform("size", size.width, size.height)
        shader.setColorUniform("color", Color.White.copy(alpha = GLOW_CORE_ALPHA * press).toArgb())
        shader.setFloatUniform("radius", size.minDimension * GLOW_RADIUS_FRACTION)
        shader.setFloatUniform(
            "position",
            point.x.coerceIn(0f, size.width),
            point.y.coerceIn(0f, size.height),
        )
        // ★ 走原生 Canvas 而不是 `ShaderBrush`：Compose 没有公开
        //   `android.graphics.Shader → ui.graphics.Shader` 的转换
        //   （Kyant 的 `asComposeShader` 是他自己库里的封装）。
        //   `PorterDuff.Mode.ADD` 与上面的 `BlendMode.Plus` 是同一个混合模式。
        drawIntoCanvas { canvas ->
            paint.shader = shader
            paint.xfermode = ADD_XFERMODE
            canvas.nativeCanvas.drawRect(0f, 0f, size.width, size.height, paint)
            paint.xfermode = null
        }
    }
}

/**
 * 加色混合的 `Xfermode`（与 Compose 的 `BlendMode.Plus` 等价）。
 *
 * 做成单例而不是每帧 new：`PorterDuffXfermode` 会被 `Paint` 长期持有，
 * 每帧新建一个只会给 GC 添堵。
 */
private val ADD_XFERMODE = PorterDuffXfermode(PorterDuff.Mode.ADD)
