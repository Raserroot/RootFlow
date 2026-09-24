package com.rootflow.domain.glass

import com.rootflow.domain.settings.BlurPolicy

/**
 * 底栏玻璃的**档位**（阶段 7；规格 `docs/liquid-glass-spec.md` 的三档降级表）。
 *
 * ```
 * 33+ 且图形能力可用且用户开关开     -> REFRACTION   AGSL 折射（+ 链式 blur）
 * 33+ 但能力不可用 / shader 构造失败 -> BLUR_ONLY    RenderEffect 模糊（无折射）
 * 31-32                             -> BLUR_ONLY    同上
 * <= 30                             -> HAZE         Haze 现有材质（无 RenderEffect）
 * ```
 *
 * ## 为什么 BLUR_ONLY 与 HAZE 不合并
 * 两者的实现路径完全不同：前者是**本地 layer 上的 `RenderEffect`**（自己画 blur），
 * 后者是**采样另一个节点**的 Haze（`hazeEffect`）。合并会让"到底该调谁"在实现侧
 * 变成又一次判定，而那正是本枚举要消灭的东西。
 */
enum class GlassTier {
    /** API 33+：`RuntimeShader` 折射 + `createChainEffect(refraction, blur)`。 */
    REFRACTION,

    /** API 31–32（或 33+ 但能力不可用）：仅 `RenderEffect.createBlurEffect`。 */
    BLUR_ONLY,

    /** API 26–30，或用户关掉开关：Haze 现有材质（`hazeEffect`）。 */
    HAZE,
}

/**
 * 玻璃档位的**判定**（规格 §六「API < 33 降级」+ `STAGE7-PLAN.md §3`）。
 *
 * ## 为什么是 domain 的纯函数（与 `BlurPolicy` 同款）
 * 它是一条**判定**，不是一次绘制。`apiLevel` / `graphicsCapabilityOk` / `lowRam`
 * 全部是**显式形参**，因此可以在纯 JVM 单测里穷举 26/30/31/32/33/34/35
 * —— 与 `BlurPolicy.effectiveBlur(userSetting, isLowRamDevice, apiLevel)` 的做法完全一致
 * （`Build.VERSION.SDK_INT` 的读取点是 UI 层）。
 *
 * ## 关于 `graphicsCapabilityOk` 的诚实表述（**不要改写成"ES 3.0"**）
 * `RuntimeShader` 是 API 33+ 的框架类（已解包 `android.jar` 核实），这一点确定；
 * 但它**在运行期具体依赖哪个 GL ES 等级**没有查到权威口径。
 * 因此：
 * 1. 形参名刻意**不叫** `es3Supported` —— 名字里写死成因会变成一句没有证据的断言；
 * 2. **真正兜底的是 `try/catch`**（见 `ui/component/LiquidGlassRenderer`）：
 *    shader 构造抛异常 ⇒ 降级到 [GlassTier.BLUR_ONLY]（不崩、不黑屏）。
 *    即使能力查询误判成"可用"，也不会让底栏消失。
 *
 * 咨询口径若将来更新，只需把查询实现补齐，**判定函数与降级路径都不用改**。
 *
 * ## 与 `BlurPolicy` 的分工（两者并存，不是替代）
 * `BlurPolicy` 管的是 **Haze 的模糊**（硬门 API 31）；本判定管**整条玻璃路线**（硬门 API 33）。
 * `blurSupported == false` 时本判定会拒绝 [GlassTier.BLUR_ONLY]，避免出现
 * "用户关了毛玻璃、底栏却仍在合成模糊"这种自相矛盾的状态。
 */
object GlassPolicy {
    /**
     * `RuntimeShader` 的最低 API（规格 §一.1：`RuntimeShader` / `createRuntimeShaderEffect`
     * 均从 API 33 起）。
     */
    const val MIN_REFRACTION_API_LEVEL: Int = 33

    /**
     * 判定底栏用哪一档玻璃。
     *
     * @param apiLevel `Build.VERSION.SDK_INT`（由 UI 层读取后传入，便于单测穷举）
     * @param graphicsCapabilityOk 设备图形能力查询结果（**成因刻意不写死**，见类 KDoc）
     * @param liquidGlassEnabled 用户开关（设置页「液态玻璃（实验）」）
     * @param lowRam `ActivityManager.isLowRamDevice`（需求 §6「低端机默认关闭」）
     * @param blurSupported `BlurPolicy.effectiveBlur` 的结果（Haze 的模糊是否生效）
     */
    fun decide(
        apiLevel: Int,
        graphicsCapabilityOk: Boolean,
        liquidGlassEnabled: Boolean,
        lowRam: Boolean,
        blurSupported: Boolean,
    ): GlassTier =
        when {
            // 硬门 1：用户关掉开关 ⇒ 立刻回到 Haze（回到 v0.6-ui 的样子）。
            // 放在最前，因为这是用户**显式**表达的意愿，优先于任何设备能力判定。
            !liquidGlassEnabled -> GlassTier.HAZE
            // 硬门 2：用户关掉了毛玻璃 ⇒ 不得在本机合成任何模糊。
            // Tier 1 的链里也含一趟 blur（`createChainEffect(refraction, blur)`），
            // 因此它同样必须被这道门挡住 —— 否则会出现"用户关了毛玻璃，底栏却更糊了"。
            !blurSupported -> GlassTier.HAZE
            // 硬门 3：低端机默认关闭（需求 §6）。上限 BLUR_ONLY 而不是 HAZE ——
            // 低端机也可能有 API 31+ 的 RenderEffect，模糊比纯 tint 更接近"玻璃"。
            lowRam -> if (apiLevel >= BlurPolicy.MIN_BLUR_API_LEVEL) GlassTier.BLUR_ONLY else GlassTier.HAZE
            // 硬门 4：API < 31 没有 RenderEffect（`Modifier.blur` / Haze 的 GPU 路径同理）
            apiLevel < BlurPolicy.MIN_BLUR_API_LEVEL -> GlassTier.HAZE
            // 硬门 5：AGSL 折射（API 33+ 且图形能力可用）
            apiLevel >= MIN_REFRACTION_API_LEVEL && graphicsCapabilityOk -> GlassTier.REFRACTION
            // 其余一律 BLUR_ONLY（31–32，以及 33+ 但能力查询不通过）
            else -> GlassTier.BLUR_ONLY
        }
}
