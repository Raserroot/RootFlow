package com.rootflow.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * 圆角体系（需求 §6「大圆角卡片」）。
 *
 * ## 为什么整体比 MD3 默认大一档
 * MD3 默认 `medium = 12.dp`，是"卡片"的量级；而需求 §6 点名的参照物是
 * LSPosed / Magisk 的**大圆角卡片**，其观感来自 20.dp 起的圆角。
 * 因此这里把每一档都抬高，并**保留 MD3 的档位语义**
 * （`extraSmall` 仍是最小的 chip，`extraLarge` 仍是最大的容器）——
 * 这样组件代码里读到的仍是"这是个大卡片"，而不是"这是 28dp"。
 *
 * ## 为什么 `large` 正好是 28.dp
 * 需求 §6 明确要求底栏胶囊用 `RoundedCornerShape(28.dp)`，
 * 而底栏是"浮起的大容器"，语义上正属 `large`。
 * 把需求里的具体数值锚在语义档位上，比在 `FloatingNavBar` 里写死 28.dp 更好：
 * 将来整体调整圆角体系时，底栏会跟着走而不是落单。
 */
internal val RootFlowShapes =
    Shapes(
        extraSmall = RoundedCornerShape(8.dp),
        small = RoundedCornerShape(12.dp),
        medium = RoundedCornerShape(20.dp),
        large = RoundedCornerShape(28.dp),
        extraLarge = RoundedCornerShape(36.dp),
    )

/**
 * 主页卡片的圆角（阶段 6b）。
 *
 * ## 为什么单独成一个常量
 * 三张卡片的圆角是**同一个设计契约**（需求 §6「大圆角卡片」）。让每张卡片
 * 各写一次 `RoundedCornerShape(24.dp)` 就是三个会漂移的字面量——
 * 改一处忘两处，视觉上表现为"卡片圆角不一致"，而那类差异只在真机截图里看得出来。
 *
 * ## 为什么是 24.dp（而不是 `MaterialTheme.shapes.medium` 的 20.dp）
 * 卡片的圆角要比内容层级（`medium`）更"大器"，同时**略小于**底栏胶囊的 28.dp：
 * 底栏是浮在最上层的独立控件，若与卡片同圆角会在视觉上"糊"进内容里。
 * 24.dp 让两者成体系（同为大圆角）又有主次。
 */
internal val CardCornerRadius = RoundedCornerShape(24.dp)

/**
 * 主页卡片的圆角半径（阶段 8，对齐 LSPosed 参考图）。
 *
 * ## 为什么另立一个常量而不是改 [CardCornerRadius]
 * 阶段 8 只重构**主页**的观感，而 [CardCornerRadius] 同时被配置页的脚本行用着
 * （`ScriptListScreen`）—— 改它会把"这一阶段不该动的页面"一起改掉，
 * 而那正是本阶段明令不做的（「不动配置页 / 设置页 / 编辑器」）。
 * 两个常量并存时，**主页的 20dp 与配置页的 24dp 各自是一个需要决定的动作**，
 * 而不是一次静默的连带改动（与 [NavBarCornerRadius] 的 KDoc 同一条理由）。
 */
internal val HomeCardCornerRadius = RoundedCornerShape(20.dp)

/**
 * 顶部 banner（安全模式）的圆角半径（阶段 6b）。
 *
 * 单值而不是 `Dp` 形状：banner 是**贴顶**的，只圆下面两个角，
 * 因此调用方需要的是半径本身（`RoundedCornerShape(bottomStart = …, bottomEnd = …)`）。
 * 与 [CardCornerRadius] 取同一量级，使"贴顶条"与"卡片"看起来属于同一套圆角体系。
 */
internal val BannerCornerRadius = 24.dp
