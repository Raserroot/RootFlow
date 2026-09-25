package com.rootflow.ui.settings

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.ImageVector.Builder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * 权限总览每项自己的图标（11e 补丁9）。
 *
 * ## ★ 这些图形从哪来（**必读，别把它当"手画的"**）
 * 它们**逐字节**来自官方的 `androidx.compose.material:material-icons-extended`：
 * 从那个 AAR 的 `classes.jar` 里取出对应 `*Kt.class`、反编译出 `PathBuilder` 的
 * 调用序列，再原样还原成这里的 `ImageVector`。**图形与官方一模一样**，没有任何一个点是估的。
 *
 * ## 为什么不直接依赖 `material-icons-extended`
 * 那个 AAR 是 **35.7 MB**（2083 个 filled 图标全量）。而本项目的
 * `app/build.gradle.kts` 里 **`isMinifyEnabled = false`**（release 也不跑 R8），
 * 因此引进来**没有任何裁剪兜底** ⇒ APK 会从 9.1 MB 涨到约 44 MB。
 * `gradle/libs.versions.toml` 里那条「core set only」的注释就是为此写的。
 *
 * 抄这 8 个进项目：**零新依赖、APK 增量不到 10 KB、图形完全一致**。
 *
 * ## 为什么不能像 core 那样写 `Icons.Filled.X`
 * `material-icons-core` 只有 **49** 个 filled 图标（已解包核实），其中
 * **没有**电源 / 网络 / 闹钟 / 电池 —— 8 项权限里只有 `Notifications` 一个能对上。
 * 要"每项一个语义图标"就必须离开 core。
 *
 * ## 对照表
 * | 权限 | 图标 | 依据 |
 * |---|---|---|
 * | 开机自启 | `PowerSettingsNew` | 电源开关 |
 * | 网络状态读取 | `Wifi` | 无线信号 |
 * | 通知 | `Notifications` | 铃铛 |
 * | 精确闹钟 | `Alarm` | 闹钟 |
 * | 使用情况访问 | `Insights` | 图表 / 统计 |
 * | 前台服务 | `MiscellaneousServices` | 服务集合 |
 * | 前台服务（specialUse） | `Tune` | 需单独配置的档位（与上一项必须可区分） |
 * | 电池优化白名单 | `BatterySaver` | 电池 |
 *
 * ## 缓存
 * 与官方实现同款：首次访问构造、之后复用同一个实例（`ImageVector` 构造不便宜，
 * 而权限总览会随重组反复取用）。形状固定 `24 × 24`、`viewport 24 × 24`，
 * 与 Material 图标标准尺寸一致。
 *
 * ## 版权
 * Material Icons 采用 **Apache License 2.0**，允许提取与再分发（保留声明即可）。
 * 图形本身是 Google 的设计资产，本文件不含任何原创图形。
 */

internal val PermissionIconBoot: ImageVector by lazy {
    Builder(
        name = "Filled.PowerSettingsNew",
        defaultWidth = 24.0.dp,
        defaultHeight = 24.0.dp,
        viewportWidth = 24.0f,
        viewportHeight = 24.0f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(13.0f, 3.0f)
            horizontalLineToRelative(-2.0f)
            verticalLineToRelative(10.0f)
            horizontalLineToRelative(2.0f)
            lineTo(13.0f, 3.0f)
            close()
            moveTo(17.83f, 5.17f)
            lineToRelative(-1.42f, 1.42f)
            curveTo(17.99f, 7.86f, 19.0f, 9.81f, 19.0f, 12.0f)
            curveToRelative(0.0f, 3.87f, -3.13f, 7.0f, -7.0f, 7.0f)
            reflectiveCurveToRelative(-7.0f, -3.13f, -7.0f, -7.0f)
            curveToRelative(0.0f, -2.19f, 1.01f, -4.14f, 2.58f, -5.42f)
            lineTo(6.17f, 5.17f)
            curveTo(4.23f, 6.82f, 3.0f, 9.26f, 3.0f, 12.0f)
            curveToRelative(0.0f, 4.97f, 4.03f, 9.0f, 9.0f, 9.0f)
            reflectiveCurveToRelative(9.0f, -4.03f, 9.0f, -9.0f)
            curveToRelative(0.0f, -2.74f, -1.23f, -5.18f, -3.17f, -6.83f)
            close()
        }
    }.build()
}

internal val PermissionIconNetwork: ImageVector by lazy {
    Builder(
        name = "Filled.Wifi",
        defaultWidth = 24.0.dp,
        defaultHeight = 24.0.dp,
        viewportWidth = 24.0f,
        viewportHeight = 24.0f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(1.0f, 9.0f)
            lineToRelative(2.0f, 2.0f)
            curveToRelative(4.97f, -4.97f, 13.03f, -4.97f, 18.0f, 0.0f)
            lineToRelative(2.0f, -2.0f)
            curveTo(16.93f, 2.93f, 7.08f, 2.93f, 1.0f, 9.0f)
            close()
            moveTo(9.0f, 17.0f)
            lineToRelative(3.0f, 3.0f)
            lineToRelative(3.0f, -3.0f)
            curveToRelative(-1.65f, -1.66f, -4.34f, -1.66f, -6.0f, 0.0f)
            close()
            moveTo(5.0f, 13.0f)
            lineToRelative(2.0f, 2.0f)
            curveToRelative(2.76f, -2.76f, 7.24f, -2.76f, 10.0f, 0.0f)
            lineToRelative(2.0f, -2.0f)
            curveTo(15.14f, 9.14f, 8.87f, 9.14f, 5.0f, 13.0f)
            close()
        }
    }.build()
}

internal val PermissionIconNotification: ImageVector by lazy {
    Builder(
        name = "Filled.Notifications",
        defaultWidth = 24.0.dp,
        defaultHeight = 24.0.dp,
        viewportWidth = 24.0f,
        viewportHeight = 24.0f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(12.0f, 22.0f)
            curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
            horizontalLineToRelative(-4.0f)
            curveToRelative(0.0f, 1.1f, 0.89f, 2.0f, 2.0f, 2.0f)
            close()
            moveTo(18.0f, 16.0f)
            verticalLineToRelative(-5.0f)
            curveToRelative(0.0f, -3.07f, -1.64f, -5.64f, -4.5f, -6.32f)
            lineTo(13.5f, 4.0f)
            curveToRelative(0.0f, -0.83f, -0.67f, -1.5f, -1.5f, -1.5f)
            reflectiveCurveToRelative(-1.5f, 0.67f, -1.5f, 1.5f)
            verticalLineToRelative(0.68f)
            curveTo(7.63f, 5.36f, 6.0f, 7.92f, 6.0f, 11.0f)
            verticalLineToRelative(5.0f)
            lineToRelative(-2.0f, 2.0f)
            verticalLineToRelative(1.0f)
            horizontalLineToRelative(16.0f)
            verticalLineToRelative(-1.0f)
            lineToRelative(-2.0f, -2.0f)
            close()
        }
    }.build()
}

internal val PermissionIconAlarm: ImageVector by lazy {
    Builder(
        name = "Filled.Alarm",
        defaultWidth = 24.0.dp,
        defaultHeight = 24.0.dp,
        viewportWidth = 24.0f,
        viewportHeight = 24.0f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(22.0f, 5.72f)
            lineToRelative(-4.6f, -3.86f)
            lineToRelative(-1.29f, 1.53f)
            lineToRelative(4.6f, 3.86f)
            lineTo(22.0f, 5.72f)
            close()
            moveTo(7.88f, 3.39f)
            lineTo(6.6f, 1.86f)
            lineTo(2.0f, 5.71f)
            lineToRelative(1.29f, 1.53f)
            lineToRelative(4.59f, -3.85f)
            close()
            moveTo(12.5f, 8.0f)
            lineTo(11.0f, 8.0f)
            verticalLineToRelative(6.0f)
            lineToRelative(4.75f, 2.85f)
            lineToRelative(0.75f, -1.23f)
            lineToRelative(-4.0f, -2.37f)
            lineTo(12.5f, 8.0f)
            close()
            moveTo(12.0f, 4.0f)
            curveToRelative(-4.97f, 0.0f, -9.0f, 4.03f, -9.0f, 9.0f)
            reflectiveCurveToRelative(4.02f, 9.0f, 9.0f, 9.0f)
            curveToRelative(4.97f, 0.0f, 9.0f, -4.03f, 9.0f, -9.0f)
            reflectiveCurveToRelative(-4.03f, -9.0f, -9.0f, -9.0f)
            close()
            moveTo(12.0f, 20.0f)
            curveToRelative(-3.87f, 0.0f, -7.0f, -3.13f, -7.0f, -7.0f)
            reflectiveCurveToRelative(3.13f, -7.0f, 7.0f, -7.0f)
            reflectiveCurveToRelative(7.0f, 3.13f, 7.0f, 7.0f)
            reflectiveCurveToRelative(-3.13f, 7.0f, -7.0f, 7.0f)
            close()
        }
    }.build()
}

internal val PermissionIconUsage: ImageVector by lazy {
    Builder(
        name = "Filled.Insights",
        defaultWidth = 24.0.dp,
        defaultHeight = 24.0.dp,
        viewportWidth = 24.0f,
        viewportHeight = 24.0f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(21.0f, 8.0f)
            curveToRelative(-1.45f, 0.0f, -2.26f, 1.44f, -1.93f, 2.51f)
            lineToRelative(-3.55f, 3.56f)
            curveToRelative(-0.3f, -0.09f, -0.74f, -0.09f, -1.04f, 0.0f)
            lineToRelative(-2.55f, -2.55f)
            curveTo(12.27f, 10.45f, 11.46f, 9.0f, 10.0f, 9.0f)
            curveToRelative(-1.45f, 0.0f, -2.27f, 1.44f, -1.93f, 2.52f)
            lineToRelative(-4.56f, 4.55f)
            curveTo(2.44f, 15.74f, 1.0f, 16.55f, 1.0f, 18.0f)
            curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
            curveToRelative(1.45f, 0.0f, 2.26f, -1.44f, 1.93f, -2.51f)
            lineToRelative(4.55f, -4.56f)
            curveToRelative(0.3f, 0.09f, 0.74f, 0.09f, 1.04f, 0.0f)
            lineToRelative(2.55f, 2.55f)
            curveTo(12.73f, 16.55f, 13.54f, 18.0f, 15.0f, 18.0f)
            curveToRelative(1.45f, 0.0f, 2.27f, -1.44f, 1.93f, -2.52f)
            lineToRelative(3.56f, -3.55f)
            curveTo(21.56f, 12.26f, 23.0f, 11.45f, 23.0f, 10.0f)
            curveTo(23.0f, 8.9f, 22.1f, 8.0f, 21.0f, 8.0f)
            close()
            moveTo(15.0f, 9.0f)
            lineToRelative(0.94f, -2.07f)
            lineToRelative(2.06f, -0.93f)
            lineToRelative(-2.06f, -0.93f)
            lineToRelative(-0.94f, -2.07f)
            lineToRelative(-0.92f, 2.07f)
            lineToRelative(-2.08f, 0.93f)
            lineToRelative(2.08f, 0.93f)
            close()
            moveTo(3.5f, 11.0f)
            lineToRelative(0.5f, -2.0f)
            lineToRelative(2.0f, -0.5f)
            lineToRelative(-2.0f, -0.5f)
            lineToRelative(-0.5f, -2.0f)
            lineToRelative(-0.5f, 2.0f)
            lineToRelative(-2.0f, 0.5f)
            lineToRelative(2.0f, 0.5f)
            close()
        }
    }.build()
}

internal val PermissionIconService: ImageVector by lazy {
    Builder(
        name = "Filled.MiscellaneousServices",
        defaultWidth = 24.0.dp,
        defaultHeight = 24.0.dp,
        viewportWidth = 24.0f,
        viewportHeight = 24.0f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(14.17f, 13.71f)
            lineToRelative(1.4f, -2.42f)
            curveToRelative(0.09f, -0.15f, 0.05f, -0.34f, -0.08f, -0.45f)
            lineToRelative(-1.48f, -1.16f)
            curveToRelative(0.03f, -0.22f, 0.05f, -0.45f, 0.05f, -0.68f)
            reflectiveCurveToRelative(-0.02f, -0.46f, -0.05f, -0.69f)
            lineToRelative(1.48f, -1.16f)
            curveToRelative(0.13f, -0.11f, 0.17f, -0.3f, 0.08f, -0.45f)
            lineToRelative(-1.4f, -2.42f)
            curveToRelative(-0.09f, -0.15f, -0.27f, -0.21f, -0.43f, -0.15f)
            lineTo(12.0f, 4.83f)
            curveToRelative(-0.36f, -0.28f, -0.75f, -0.51f, -1.18f, -0.69f)
            lineToRelative(-0.26f, -1.85f)
            curveTo(10.53f, 2.13f, 10.38f, 2.0f, 10.21f, 2.0f)
            horizontalLineToRelative(-2.8f)
            curveTo(7.24f, 2.0f, 7.09f, 2.13f, 7.06f, 2.3f)
            lineTo(6.8f, 4.15f)
            curveTo(6.38f, 4.33f, 5.98f, 4.56f, 5.62f, 4.84f)
            lineToRelative(-1.74f, -0.7f)
            curveToRelative(-0.16f, -0.06f, -0.34f, 0.0f, -0.43f, 0.15f)
            lineToRelative(-1.4f, 2.42f)
            curveTo(1.96f, 6.86f, 2.0f, 7.05f, 2.13f, 7.16f)
            lineToRelative(1.48f, 1.16f)
            curveTo(3.58f, 8.54f, 3.56f, 8.77f, 3.56f, 9.0f)
            reflectiveCurveToRelative(0.02f, 0.46f, 0.05f, 0.69f)
            lineToRelative(-1.48f, 1.16f)
            curveTo(2.0f, 10.96f, 1.96f, 11.15f, 2.05f, 11.3f)
            lineToRelative(1.4f, 2.42f)
            curveToRelative(0.09f, 0.15f, 0.27f, 0.21f, 0.43f, 0.15f)
            lineToRelative(1.74f, -0.7f)
            curveToRelative(0.36f, 0.28f, 0.75f, 0.51f, 1.18f, 0.69f)
            lineToRelative(0.26f, 1.85f)
            curveTo(7.09f, 15.87f, 7.24f, 16.0f, 7.41f, 16.0f)
            horizontalLineToRelative(2.8f)
            curveToRelative(0.17f, 0.0f, 0.32f, -0.13f, 0.35f, -0.3f)
            lineToRelative(0.26f, -1.85f)
            curveToRelative(0.42f, -0.18f, 0.82f, -0.41f, 1.18f, -0.69f)
            lineToRelative(1.74f, 0.7f)
            curveTo(13.9f, 13.92f, 14.08f, 13.86f, 14.17f, 13.71f)
            close()
            moveTo(8.81f, 11.0f)
            curveToRelative(-1.1f, 0.0f, -2.0f, -0.9f, -2.0f, -2.0f)
            curveToRelative(0.0f, -1.1f, 0.9f, -2.0f, 2.0f, -2.0f)
            reflectiveCurveToRelative(2.0f, 0.9f, 2.0f, 2.0f)
            curveTo(10.81f, 10.1f, 9.91f, 11.0f, 8.81f, 11.0f)
            close()
            moveTo(21.92f, 18.67f)
            lineToRelative(-0.96f, -0.74f)
            curveToRelative(0.02f, -0.14f, 0.04f, -0.29f, 0.04f, -0.44f)
            curveToRelative(0.0f, -0.15f, -0.01f, -0.3f, -0.04f, -0.44f)
            lineToRelative(0.95f, -0.74f)
            curveToRelative(0.08f, -0.07f, 0.11f, -0.19f, 0.05f, -0.29f)
            lineToRelative(-0.9f, -1.55f)
            curveToRelative(-0.05f, -0.1f, -0.17f, -0.13f, -0.28f, -0.1f)
            lineToRelative(-1.11f, 0.45f)
            curveToRelative(-0.23f, -0.18f, -0.48f, -0.33f, -0.76f, -0.44f)
            lineToRelative(-0.17f, -1.18f)
            curveTo(18.73f, 13.08f, 18.63f, 13.0f, 18.53f, 13.0f)
            horizontalLineToRelative(-1.79f)
            curveToRelative(-0.11f, 0.0f, -0.21f, 0.08f, -0.22f, 0.19f)
            lineToRelative(-0.17f, 1.18f)
            curveToRelative(-0.27f, 0.12f, -0.53f, 0.26f, -0.76f, 0.44f)
            lineToRelative(-1.11f, -0.45f)
            curveToRelative(-0.1f, -0.04f, -0.22f, 0.0f, -0.28f, 0.1f)
            lineToRelative(-0.9f, 1.55f)
            curveToRelative(-0.05f, 0.1f, -0.04f, 0.22f, 0.05f, 0.29f)
            lineToRelative(0.95f, 0.74f)
            curveToRelative(-0.02f, 0.14f, -0.03f, 0.29f, -0.03f, 0.44f)
            curveToRelative(0.0f, 0.15f, 0.01f, 0.3f, 0.03f, 0.44f)
            lineToRelative(-0.95f, 0.74f)
            curveToRelative(-0.08f, 0.07f, -0.11f, 0.19f, -0.05f, 0.29f)
            lineToRelative(0.9f, 1.55f)
            curveToRelative(0.05f, 0.1f, 0.17f, 0.13f, 0.28f, 0.1f)
            lineToRelative(1.11f, -0.45f)
            curveToRelative(0.23f, 0.18f, 0.48f, 0.33f, 0.76f, 0.44f)
            lineToRelative(0.17f, 1.18f)
            curveToRelative(0.02f, 0.11f, 0.11f, 0.19f, 0.22f, 0.19f)
            horizontalLineToRelative(1.79f)
            curveToRelative(0.11f, 0.0f, 0.21f, -0.08f, 0.22f, -0.19f)
            lineToRelative(0.17f, -1.18f)
            curveToRelative(0.27f, -0.12f, 0.53f, -0.26f, 0.75f, -0.44f)
            lineToRelative(1.12f, 0.45f)
            curveToRelative(0.1f, 0.04f, 0.22f, 0.0f, 0.28f, -0.1f)
            lineToRelative(0.9f, -1.55f)
            curveTo(22.03f, 18.86f, 22.0f, 18.74f, 21.92f, 18.67f)
            close()
            moveTo(17.63f, 18.83f)
            curveToRelative(-0.74f, 0.0f, -1.35f, -0.6f, -1.35f, -1.35f)
            reflectiveCurveToRelative(0.6f, -1.35f, 1.35f, -1.35f)
            reflectiveCurveToRelative(1.35f, 0.6f, 1.35f, 1.35f)
            reflectiveCurveTo(18.37f, 18.83f, 17.63f, 18.83f)
            close()
        }
    }.build()
}

internal val PermissionIconServiceSpecial: ImageVector by lazy {
    Builder(
        name = "Filled.Tune",
        defaultWidth = 24.0.dp,
        defaultHeight = 24.0.dp,
        viewportWidth = 24.0f,
        viewportHeight = 24.0f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(3.0f, 17.0f)
            verticalLineToRelative(2.0f)
            horizontalLineToRelative(6.0f)
            verticalLineToRelative(-2.0f)
            lineTo(3.0f, 17.0f)
            close()
            moveTo(3.0f, 5.0f)
            verticalLineToRelative(2.0f)
            horizontalLineToRelative(10.0f)
            lineTo(13.0f, 5.0f)
            lineTo(3.0f, 5.0f)
            close()
            moveTo(13.0f, 21.0f)
            verticalLineToRelative(-2.0f)
            horizontalLineToRelative(8.0f)
            verticalLineToRelative(-2.0f)
            horizontalLineToRelative(-8.0f)
            verticalLineToRelative(-2.0f)
            horizontalLineToRelative(-2.0f)
            verticalLineToRelative(6.0f)
            horizontalLineToRelative(2.0f)
            close()
            moveTo(7.0f, 9.0f)
            verticalLineToRelative(2.0f)
            lineTo(3.0f, 11.0f)
            verticalLineToRelative(2.0f)
            horizontalLineToRelative(4.0f)
            verticalLineToRelative(2.0f)
            horizontalLineToRelative(2.0f)
            lineTo(9.0f, 9.0f)
            lineTo(7.0f, 9.0f)
            close()
            moveTo(21.0f, 13.0f)
            verticalLineToRelative(-2.0f)
            lineTo(11.0f, 11.0f)
            verticalLineToRelative(2.0f)
            horizontalLineToRelative(10.0f)
            close()
            moveTo(15.0f, 9.0f)
            horizontalLineToRelative(2.0f)
            lineTo(17.0f, 7.0f)
            horizontalLineToRelative(4.0f)
            lineTo(21.0f, 5.0f)
            horizontalLineToRelative(-4.0f)
            lineTo(17.0f, 3.0f)
            horizontalLineToRelative(-2.0f)
            verticalLineToRelative(6.0f)
            close()
        }
    }.build()
}

internal val PermissionIconBattery: ImageVector by lazy {
    Builder(
        name = "Filled.BatterySaver",
        defaultWidth = 24.0.dp,
        defaultHeight = 24.0.dp,
        viewportWidth = 24.0f,
        viewportHeight = 24.0f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(16.0f, 4.0f)
            horizontalLineToRelative(-2.0f)
            verticalLineTo(2.0f)
            horizontalLineToRelative(-4.0f)
            verticalLineToRelative(2.0f)
            horizontalLineTo(8.0f)
            curveTo(7.45f, 4.0f, 7.0f, 4.45f, 7.0f, 5.0f)
            verticalLineToRelative(16.0f)
            curveToRelative(0.0f, 0.55f, 0.45f, 1.0f, 1.0f, 1.0f)
            horizontalLineToRelative(8.0f)
            curveToRelative(0.55f, 0.0f, 1.0f, -0.45f, 1.0f, -1.0f)
            verticalLineTo(5.0f)
            curveTo(17.0f, 4.45f, 16.55f, 4.0f, 16.0f, 4.0f)
            close()
            moveTo(15.0f, 14.0f)
            horizontalLineToRelative(-2.0f)
            verticalLineToRelative(2.0f)
            horizontalLineToRelative(-2.0f)
            verticalLineToRelative(-2.0f)
            horizontalLineTo(9.0f)
            verticalLineToRelative(-2.0f)
            horizontalLineToRelative(2.0f)
            verticalLineToRelative(-2.0f)
            horizontalLineToRelative(2.0f)
            verticalLineToRelative(2.0f)
            horizontalLineToRelative(2.0f)
            verticalLineTo(14.0f)
            close()
        }
    }.build()
}
