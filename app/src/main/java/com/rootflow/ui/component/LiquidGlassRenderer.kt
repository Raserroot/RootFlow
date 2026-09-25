package com.rootflow.ui.component

import android.graphics.RuntimeShader
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.unit.IntSize
import com.rootflow.domain.glass.GlassParams
import com.rootflow.domain.glass.GlassPolicy
import com.rootflow.domain.glass.GlassTier

/**
 * 液态玻璃渲染器（阶段 7）—— **全项目唯一决定"玻璃怎么画"的地方**。
 *
 * ## 职责边界（三层各做一件事，见 `STAGE7-PLAN.md §3`）
 * ```
 * GlassPolicy.decide(...)    只判定档位（纯函数，domain，穷举单测）
 * LiquidGlassRenderer       只做路由与缓存（本文件；缓存/清洗/降级全部可单测）
 * GlassEffectFactory        只碰平台 API（AGSL 构造 + effect 链；真机才是它的考场）
 * FloatingNavBar            只决定"画成什么样"（真机覆盖）
 * ```
 *
 * ## 链顺序（规格 §一.3；**探针刻意省掉的那一趟，完整版已加回**）
 * ```kotlin
 * RenderEffect.createChainEffect(refraction, blur)   // outer = refraction, inner = blur
 * ```
 * 语义是**先 inner（blur）后 outer（refraction）**。
 *
 * ## 缓存纪律（规格 §六，三条）
 * 1. **shader 按尺寸缓存**（在 [GlassEffectFactory] 实现里，尺寸取整、上限防御）
 * 2. **effect 只在"尺寸 / 参数 / 档位变化"时重建** —— 底栏尺寸恒定 ⇒ 正常运行时只建一次
 * 3. **每帧什么都不做** —— `graphicsLayer` 的 block 每帧都跑，因此必须在这里比对缓存键；
 *    少了这一步就退化成"每帧重建 `RenderEffect`"，那正是规格点名禁止的写法
 *
 * ## 为什么把平台调用整体抽成 [GlassEffectFactory]（★ 与 `STAGE7-PLAN.md §6.2` 的差异，已登记）
 * 方案原文只抽了 `RuntimeShaderFactory`（缝的签名是 `fun create(agsl: String): RuntimeShader`），
 * 但那条缝**测不了"路由与缓存"**：`RenderEffect.createBlurEffect` 是 **final 平台类的静态方法**，
 * 纯 JVM 下会抛 `Method … not mocked`（`AGENT_PROTOCOL.md §5.10`）—— 于是
 * "Tier 2 走 blur / Tier 1 走链 / HAZE 不套 effect"这条**最该被钉死的路由逻辑**
 * 会变成只能真机覆盖。
 *
 * 把"造 effect"整体挪到注入的工厂后面之后：
 * - 本类（**路由 + 缓存 + 参数清洗 + 降级**）用假工厂即可完整断言；
 * - 工厂实现（[AndroidGlassEffectFactory]）只剩"调平台 API"这一件事，
 *   而它本来就是**只有真机能验**的部分（诚实登记，不假装有覆盖）。
 *
 * @param effectFactory effect 造法（生产传 [AndroidGlassEffectFactory]）
 */
internal class LiquidGlassRenderer(
    private val effectFactory: GlassEffectFactory,
) {
    /** 最近一次的 effect 与其缓存键（`null` 键 = 尚未构建过）。 */
    private var cachedEffect: RenderEffect? = null

    private var cachedEffectKey: EffectKey? = null

    /**
     * 造出该档位 + 该尺寸 + 该参数对应的 effect。
     *
     * ## 三条早退（**顺序有意义**）
     * 1. [GlassTier.HAZE] ⇒ `null`：不套任何本地 effect，毛玻璃交给 Haze
     * 2. 尺寸非正 ⇒ `null`：`graphicsLayer` 的首帧拿不到尺寸，此时造 effect 毫无意义
     *    （否则会白白触发一次 shader 构造）
     * 3. 缓存命中 ⇒ 直接返回原对象（**同一个实例**，因为重新赋值会让 Compose 判定
     *    `graphicsLayer` 的 block 结果变化而触发重绘）
     *
     * @return `null` = 不要套 effect；非 `null` = 可赋给 `graphicsLayer.renderEffect`
     */
    fun effect(
        tier: GlassTier,
        size: IntSize,
        params: GlassParams,
    ): RenderEffect? {
        if (tier == GlassTier.HAZE) return null
        if (size.width <= 0 || size.height <= 0) return null

        // ★ 清洗只在这里做一次，**传出去的已经是合法值**（工厂实现不必再防）
        val sanitized = params.sanitized()
        val key = EffectKey(tier = tier, size = size, params = sanitized)
        cachedEffect?.let { existing ->
            if (cachedEffectKey == key) return existing
        }

        val created = effectFactory.create(tier = tier, size = size, params = sanitized)
        cachedEffect = created
        cachedEffectKey = key
        return created
    }

    /** 离开组合时释放（缓存里持有的是 GPU 侧资源句柄，不该跨界面存活）。 */
    fun release() {
        cachedEffect = null
        cachedEffectKey = null
        effectFactory.release()
    }

    /**
     * effect 的缓存键。
     *
     * 用 `IntSize`（**值类，相等性按宽高**）而不是 `Size`（`Float`，测量抖动时的亚像素差异
     * 会让缓存反复失配 ⇒ 退化成每帧重建）。
     * [GlassParams] 的相等性按**数组内容**比较（见其 `equals` 覆写），因此这里的键是可靠的。
     */
    private data class EffectKey(
        val tier: GlassTier,
        val size: IntSize,
        val params: GlassParams,
    )
}

/**
 * effect 的**造法**（平台 API 的唯一入口 + 单测的注入缝）。
 *
 * ## 契约
 * - 返回 `null` 表示"该档位没有可用的 effect"（**不得抛异常**：调用方拿不到 effect
 *   时应当退化成"内容原样画一遍"，而不是让底栏崩掉）
 * - 实现必须自行消化 `RuntimeShader` 构造失败（规格 §六「try/catch shader 编译，失败就 fallback」）
 * - [release] 之后实现应当丢弃自己的缓存（`shaderCache` 持有 GPU 侧句柄）
 */
internal interface GlassEffectFactory {
    fun create(
        tier: GlassTier,
        size: IntSize,
        params: GlassParams,
    ): RenderEffect?

    /** 释放缓存（默认无事可做）。 */
    fun release() = Unit
}

/**
 * 生产实现：AGSL 折射 + `RenderEffect` 链（**唯一接触 `RuntimeShader` 的地方**）。
 *
 * ## 构造期失败 ⇒ 降级，不崩（规格 §六）
 * `RuntimeShader(agsl)` 对语法错 / 图形能力不足会抛异常。此时**返回 Tier 2 的纯 blur**
 * 而不是 `null` —— 观感仍然是一块玻璃，只是没有折射；返回 `null` 会让玻璃整块消失。
 *
 * ## 三个平台调用点（**全部只有真机能验**）
 * `RuntimeShader(agsl)` · `createRuntimeShaderEffect(shader, "content")` ·
 * `createBlurEffect` / `createChainEffect`。
 * 单测只覆盖"调用方怎么用它"（`LiquidGlassRendererTest`），
 * **不覆盖它们本身**（纯 JVM 下 `Method … not mocked`）。
 */
@RequiresApi(GlassPolicy.MIN_REFRACTION_API_LEVEL)
internal class AndroidGlassEffectFactory : GlassEffectFactory {
    private val shaderCache: MutableMap<Long, RuntimeShader> = mutableMapOf()

    override fun create(
        tier: GlassTier,
        size: IntSize,
        params: GlassParams,
    ): RenderEffect? {
        val blur =
            android.graphics.RenderEffect
                .createBlurEffect(
                    params.blurRadius,
                    params.blurRadius,
                    android.graphics.Shader.TileMode.CLAMP,
                ).asComposeRenderEffect()

        if (tier == GlassTier.BLUR_ONLY) return blur

        // Tier 1（REFRACTION）：**任何**异常都降级到纯 blur（不崩、不黑屏）。
        // 注意这里不再走 asComposeRenderEffect(null)：平台桩返回 null 时直接降级。
        val shader = runCatching { shaderFor(size) }.getOrNull() ?: return blur
        return runCatching {
            setUniforms(shader = shader, size = size, params = params)
            android.graphics.RenderEffect
                .createChainEffect(
                    android.graphics.RenderEffect.createRuntimeShaderEffect(shader, CONTENT_SHADER_NAME),
                    android.graphics.RenderEffect.createBlurEffect(
                        params.blurRadius,
                        params.blurRadius,
                        android.graphics.Shader.TileMode.CLAMP,
                    ),
                ).asComposeRenderEffect()
        }.getOrElse { blur }
    }

    override fun release() {
        shaderCache.clear()
    }

    /**
     * 取（或建）该尺寸的 shader。
     *
     * 缓存上限用**先清空**而不是 LRU：底栏尺寸恒定，缓存里通常只有一项；
     * 真出现多项只可能是旋转/折叠屏切换这类罕见事件 —— 那时重建一次 shader 的成本
     * 远低于维护一个 LRU 的复杂度。
     */
    private fun shaderFor(size: IntSize): RuntimeShader {
        val key = size.width.toLong() shl 32 or (size.height.toLong() and 0xFFFFFFFFL)
        shaderCache[key]?.let { return it }
        if (shaderCache.size >= MAX_CACHED_SHADERS) shaderCache.clear()
        val shader = RuntimeShader(LIQUID_GLASS_AGSL)
        shaderCache[key] = shader
        return shader
    }

    /**
     * 设 uniform（**名字必须与 [LIQUID_GLASS_AGSL] 的声明逐字一致**）。
     *
     * 规格 §一.2：名字不匹配会**直接报错或黑屏**，而那是只有真机能发现的失败。
     * `GlassShaderSourceTest` 因此对"源码声明的 uniform 集合"与"这里的契约清单"
     * 做**三向一致断言**（`AGENT_PROTOCOL.md §8.0`：跨模块契约要有跨界断言）。
     */
    private fun setUniforms(
        shader: RuntimeShader,
        size: IntSize,
        params: GlassParams,
    ) {
        shader.setFloatUniform("size", size.width.toFloat(), size.height.toFloat())
        shader.setFloatUniform("cornerRadii", params.cornerRadii)
        shader.setFloatUniform("refractionHeight", params.refractionHeight)
        shader.setFloatUniform("refractionAmount", params.refractionAmount)
        shader.setFloatUniform("depthEffect", params.depthEffect)
        shader.setFloatUniform("samplingStability", params.samplingStability)
        shader.setFloatUniform("stabilizeCenter", size.width / 2f, size.height / 2f)
        shader.setFloatUniform("refractionDirection", REFRACTION_DIRECTION_X, REFRACTION_DIRECTION_Y)
        shader.setFloatUniform("dispersion", params.dispersion)
        shader.setFloatUniform("highlightAlpha", params.highlightAlpha)
        // 阶段 11：光学增强（顺序与 AGSL 里的声明顺序无关，但四个名字都必须逐字一致）
        shader.setFloatUniform("fresnelPower", params.fresnelPower)
        shader.setFloatUniform("fresnelStrength", params.fresnelStrength)
        shader.setFloatUniform("vibrancy", params.vibrancy)
        shader.setFloatUniform("lightDirection", params.lightDirection)
    }

    private companion object {
        /** 规格 §一.2：这个名字必须与 `createRuntimeShaderEffect(shader, "content")` 一致。 */
        const val CONTENT_SHADER_NAME: String = "content"

        /** shader 缓存上限（防御性；底栏尺寸恒定 ⇒ 实际只有一项）。 */
        const val MAX_CACHED_SHADERS: Int = 4

        /** 折射方向：本阶段固定为"向右"（规格 §五 的 `refractionDirection` 归一化 float2）。 */
        const val REFRACTION_DIRECTION_X: Float = 1f
        const val REFRACTION_DIRECTION_Y: Float = 0f
    }
}

/**
 * 完整版 AGSL（规格 `docs/liquid-glass-spec.md` §三 + §四）。
 *
 * ## 与 Phase 1a 探针的三处差别（**都是"改回规格写法"**）
 * 1. **增量折射带** ← 探针是 `clamp(-sdf / h, 0, 1)`（玻璃**中心**也被偏移 ⇒ 整块糊）；
 *    现在改成 `1 - clamp(-sdf / h, 0, 1)`：折射只发生在边缘 [refractionHeight] 带内，
 *    中心完全不采样偏移
 * 2. **`depthEffect`** ← 探针没有（规格 §三.4 要求"朝中心分量"做厚玻璃感）
 * 3. **链式 blur** ← 探针省掉（规格 §一.3）；blur 由 [AndroidGlassEffectFactory] 的
 *    `createChainEffect(refraction, blur)` 承担，因此 shader 里**不再做**采样近似
 *
 * ## 与规格原文的三处**有意**偏离（都要在变更报告里说清）
 * | 偏离 | 规格原文 | 本项目 | 理由 |
 * |---|---|---|---|
 * | `cornerRadii` 用法 | 四角分别算 SDF | 取**最小**值当单一半径 | 胶囊四角相同；四角独立 SDF 要多 3 组 `min`，收益为 0 |
 * | `samplingStability` 分支 | `if (samplingStability > 0.0)` | 去掉分支、直接 `mix` | `sanitized()` 已把下限钉在 0.001 ⇒ 分支恒真，是死代码 |
 * | `refractionDirection` | 参数化方向 | 声明了但当前未参与运算 | 本阶段固定向右；**保留 uniform 以免将来加参数时要改两处** |
 *
 * ## 三块**由单测钉死**的静态点（见 `GlassShaderSourceTest`）
 * 1. `uniform shader content;` 存在，且名字与 `createRuntimeShaderEffect(shader, "content")` 一致
 * 2. `lensMap` 是 `x^4 * (5 - 4x)`（规格 §一.5：**不是**标准 `smootherstep`，别写错）
 * 3. 源码里**不含** `#include`（AGSL 没有预处理器，写了必然编译失败）
 *
 * ## 阶段 11 新增（三处，全部**未经真机验证** —— `STAGE11-PLAN.md §5`）
 * 1. **vibrancy**：`mix(luma, color, vibrancy)`。取 1.0 时恒等 ⇒ 这一项**永远可以安全地"关掉"**
 * 2. **Fresnel 镜面**：`pow(edgeWeight, fresnelPower)` 把高光压到窄边缘带，
 *    再用 `dot(outward, lightDirection)` 判受光侧。与既有的 `highlightAlpha` **叠加**：
 *    后者是"玻璃还在"的基础辉光（`STAGE7` 验收项 3 的判据），前者是方向性镜面
 * 3. **外法线方向的更正**：`sdfGradient` 的旧注释写的是「指向玻璃内部」，
 *    但按 `sdRoundRect` 的定义（内负外正）梯度只能**指向外部**。
 *    阶段 11 首次真正用到这个方向（Fresnel），因此把推导写在了使用处。
 *    **旧注释不影响 `offset` 的正确性**（那里只关心"沿梯度推多远"，与朝向无关），
 *    因此本次**不改注释、不改既有行为**，只在使用处写清真正的朝向 —— 见 `§8` 项 1
 *    的真机判据（若观感与预期相反，一行 `-grad` 即可纠正）。
 */
internal const val LIQUID_GLASS_AGSL: String = """
    // ── 与 Kotlin 侧的契约（GlassShaderSourceTest 三向断言）─────────────────────
    // @uniform content              shader   由 createRuntimeShaderEffect(shader, "content") 提供
    // @uniform size                 float2   AndroidGlassEffectFactory.setUniforms
    // @uniform cornerRadii          float4   AndroidGlassEffectFactory.setUniforms
    // @uniform refractionHeight     float    AndroidGlassEffectFactory.setUniforms
    // @uniform refractionAmount     float    AndroidGlassEffectFactory.setUniforms
    // @uniform depthEffect          float    AndroidGlassEffectFactory.setUniforms
    // @uniform samplingStability    float    AndroidGlassEffectFactory.setUniforms
    // @uniform stabilizeCenter      float2   AndroidGlassEffectFactory.setUniforms
    // @uniform refractionDirection  float2   AndroidGlassEffectFactory.setUniforms
    // @uniform dispersion           float    AndroidGlassEffectFactory.setUniforms
    // @uniform highlightAlpha       float    AndroidGlassEffectFactory.setUniforms
    // @uniform fresnelPower         float    AndroidGlassEffectFactory.setUniforms（阶段 11）
    // @uniform fresnelStrength      float    AndroidGlassEffectFactory.setUniforms（阶段 11）
    // @uniform vibrancy             float    AndroidGlassEffectFactory.setUniforms（阶段 11）
    // @uniform lightDirection       float2   AndroidGlassEffectFactory.setUniforms（阶段 11）

    uniform shader content;
    uniform float2 size;
    uniform float4 cornerRadii;
    uniform float refractionHeight;
    uniform float refractionAmount;
    uniform float depthEffect;
    uniform float samplingStability;
    uniform float2 stabilizeCenter;
    uniform float2 refractionDirection;
    uniform float dispersion;
    uniform float highlightAlpha;
    uniform float fresnelPower;
    uniform float fresnelStrength;
    uniform float vibrancy;
    uniform float2 lightDirection;

    // 圆角矩形 SDF（负值 = 在内部）
    float sdRoundRect(float2 p, float2 halfSize, float radius) {
        float2 d = abs(p) - halfSize + radius;
        return length(max(d, 0.0)) + min(max(d.x, d.y), 0.0) - radius;
    }

    // 玻璃形状取四角半径里最小的那个（胶囊四角相同；见文件头「有意偏离」）
    float shapeRadius() {
        return min(min(cornerRadii.x, cornerRadii.y), min(cornerRadii.z, cornerRadii.w));
    }

    // SDF 梯度（指向玻璃内部）：前向差分 + 一个极小量避免 normalize(0)
    float2 sdfGradient(float2 p, float2 halfSize, float radius) {
        float sdf = sdRoundRect(p, halfSize, radius);
        float dx = sdRoundRect(p + float2(1.0, 0.0), halfSize, radius) - sdf;
        float dy = sdRoundRect(p + float2(0.0, 1.0), halfSize, radius) - sdf;
        return normalize(float2(dx, dy) + float2(1e-5, 1e-5));
    }

    // lensMap(x) = x^4 * (5 - 4x)：两端平、中间快（规格 §三.3 / §一.5）
    float lensMap(float x) {
        return pow(x, 4.0) * (5.0 - 4.0 * x);
    }

    float4 main(float2 coord) {
        float2 halfSize = size * 0.5;
        float2 p = coord - halfSize;
        float radius = shapeRadius();
        float sdf = sdRoundRect(p, halfSize, radius);

        // ── 玻璃外：原样输出 ───────────────────────────────────────────────
        // 判据（规格 §七 验收）：「外部内容不被模糊或折射」。
        // 链里的 blur 是**整层**生效的 ⇒ 本函数必须显式把外部像素恢复成清晰原图。
        if (sdf > 0.0) {
            return content.eval(coord);
        }

        // ── 边缘环带权重：0 = 玻璃深处，1 = 贴边 ───────────────────────────
        // ★ 1 - clamp(...)：深处为 0 ⇒ **中心不做任何偏移**（探针在这里是反的）
        float edgeWeight = 1.0 - clamp(-sdf / max(refractionHeight, 0.001), 0.0, 1.0);
        float lens = lensMap(edgeWeight);

        // ── 梯度方向 + 朝中心的 depth 分量（规格 §三.2 / §三.4）──────────────
        float2 grad = sdfGradient(p, halfSize, radius);
        float2 toCenter = stabilizeCenter - coord;
        float2 centerDir = toCenter / max(length(toCenter), 0.001);
        float2 offset = grad * lens * refractionAmount + centerDir * depthEffect * edgeWeight;

        // ── 主采样 ─────────────────────────────────────────────────────────
        float3 color = content.eval(coord + offset).rgb;

        // ── 五点平均（规格 §三.5）：静止取 0.001（几乎关闭），滚动中取 1.0 ──
        float3 average = color;
        average += content.eval(coord + offset + float2( 1.0,  0.0)).rgb;
        average += content.eval(coord + offset + float2(-1.0,  0.0)).rgb;
        average += content.eval(coord + offset + float2( 0.0,  1.0)).rgb;
        average += content.eval(coord + offset + float2( 0.0, -1.0)).rgb;
        color = mix(color, average / 5.0, samplingStability);

        // ── 色散（规格 §三.6）：默认 0 ⇒ 走快路径，采样数不 ×7 ──────────────
        if (dispersion > 0.0) {
            float2 spread = grad * lens * refractionAmount * dispersion;
            color = float3(
                content.eval(coord + offset + spread).r,
                color.g,
                content.eval(coord + offset - spread).b
            );
        }

        // ── 背景提饱和 vibrancy（阶段 11）──────────────────────────────────
        // vibrancy == 1.0 ⇒ mix 恒等 ⇒ 与"没有这一项"逐像素等价（可关、可不敢用）
        float luma = dot(color, float3(0.2126, 0.7152, 0.0722));
        color = mix(float3(luma), color, vibrancy);

        // ── 边缘高光（规格 §三.8：靠边最亮）────────────────────────────────
        color += float3(highlightAlpha) * edgeWeight;

        // ── Fresnel 镜面（阶段 11）：受光侧比背光侧亮 ───────────────────────
        // ★ 外法线的推导（**这里与 sdfGradient 的旧注释相反，见文件头第 4 条**）：
        //   sdRoundRect 内部为负、外部为正 ⇒ 梯度指向 SDF **增大**方向 ⇒ 指向外部。
        //   故 grad 本身就是外法线，**不取负**。
        //   若真机上发现亮暗侧反了，把下面这行的 grad 改成 -grad 即可（`STAGE11-PLAN.md §8` 项 1）
        float2 outward = grad;
        float ndl = max(dot(outward, lightDirection), 0.0);
        float rim = pow(edgeWeight, fresnelPower);
        color += float3(rim * (0.35 + 0.65 * ndl) * fresnelStrength);

        return float4(color, 1.0);
    }
"""
