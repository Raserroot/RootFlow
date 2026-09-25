import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ktlint)
}

// ── ❌ 这里曾经登记过一个"release 构建做不出来"的 blocker —— ★ 已解决，且**根因判断是错的** ──
//
// ## 当时的现象与（错误的）结论
// `assembleRelease` 失败在 `mergeReleaseNativeLibs`：
// `org.jetbrains.compose.ui:ui-backhandler-android:1.8.0` 的 **release 变体**
// 不在本工作区的 Gradle 缓存里（缓存只存了 `-android-debug`），
// 于是当时判定为"**沙箱无外网** + 离线缓存缺口"，并尝试了两条绕过（均失败）：
// 1. `dependencySubstitution` 换成缓存的 `-debug` 坐标 ⇒ **变体属性不匹配**
//    （该 AAR 声明 `BuildTypeAttr=debug`，release 消费方要 `release`）。
// 2. 叠加 `attributesSchema` 兼容规则放宽该属性 ⇒ `.kts` 里写不干净
//    （`compatibilityRules.add(...)` 要的是**规则类**而非 lambda）。
//
// ## ★ 真正的根因（2026-09-20 查清）
// **不是没网。** 实测：
// - DNS 正常、**TCP 443 可连**
// - `http://repo1.maven.org/maven2/` ⇒ **HTTP 301**（说明 TCP 与 HTTP 都通）
// - `https://…` ⇒ `schannel: AcquireCredentialsHandle failed: SEC_E_NO_CREDENTIALS`
//
// 即 **Windows Schannel 在本环境拿不到凭据**，导致所有**走 Schannel 的**客户端
// （`curl.exe`、.NET 的 `Invoke-WebRequest` / `HttpClient`）都无法完成 TLS 握手。
// 我当初就是**只用这两类工具探测**，才误判成"无外网"。
//
// **Gradle 不受影响**：它跑在 JVM 上、用的是 **Java 自己的 TLS 栈**（不碰 Schannel）。
// ⇒ **直接 `assembleRelease`（不加 `--offline`）即可**，它会自己把缺的 release 变体拉进缓存。
//
// ## 已完成的验证
// `:app:assembleRelease` 在 **2m13s** 内成功，产出 9.09 MB 的
// `app/build/outputs/apk/release/app-release.apk`：
// - 签名 `CN=RootFlow`（本仓库 `app/keystore.properties` 指定的 release keystore）
// - `versionCode=2` / `versionName=1.0.0`；**无 `android:debuggable`**
// - **覆盖安装成功且数据保留**（脚本 zz6e 与 5 个触发器都在）
//
// ## 教训（写给下一个会话）
// 判断"有没有网"**不能只用 Schannel 系工具**（curl / .NET）。
// 至少要用一个 **JVM 侧**的探测（Gradle 自身最直接），否则会把
// "本机 TLS 凭据问题"误判成"网络不可达"，并因此绕一大圈去改构建脚本。

android {
    namespace = "com.rootflow"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.rootflow.app"
        minSdk = 26
        targetSdk = 34
        // ★ 阶段 11e（此前 1.4.0 / code 8）。用户第六~十条反馈的落地：
        //   ① 「液态玻璃还是没用」→ 用户直接裁定**移除液态玻璃**，底栏只留毛玻璃（Haze）。
        //      整条链路（AGSL 折射 / 离屏捕获 / 三档降级 / 设置页开关）撤出生产；
        //      实现与理由留在 GlassLayer / LiquidGlassRenderer / GlassParams / GlassTier /
        //      NavBarGlow 的文件头（「已评估、不参与生产」）。
        //   ② 「底栏字体调小一点」→ 新增 NavBarLabelFontSize = 11.sp（1.4.1）。
        //   ③ 「字要往上调，符号下面一点就行」→ ICON_FRAME_SIZE 44 → 24dp +
        //      新增 NavBarLabelLineHeight = 14.sp（1.4.2）。
        //   ④ 「服务卡跟总开关调换位置」→ HomeScreen 的两个 item 交换，服务卡提到最顶（1.4.3）。
        //   ⑤ 「服务卡完全照 LSPosed 复刻」→ ServiceStatusCard 重写为两行 + 右侧大图标
        //      右下角溢出被裁 + 强调色 5% 叠 surface，参数逐像素取自参照截图（1.4.3）。
        //   ⑥ 1.4.0 的 NavBarPalette（选中蓝/未选中灰）与玻璃参数（blur 8dp / scrim 0.10）**未动**。
        //   versionName 会经 BuildConfig 显示在主页「App 版本」与设置页「关于」，
        //   因此改这里就等于改了真机上看到的版本号 —— 发布时**必须**与 tag 一致。
        //   ⚠️ 本版本**未经真机验证**（`STAGE11-PLAN.md §5`）⇒ tag 只能带 `-unverified`。
        versionCode = 11
        versionName = "1.4.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    /**
     * release 签名（v1.0 发布时接入）。
     *
     * ## 为什么从 `keystore.properties` 读，而不是写死在脚本里
     * 口令绝不能进版本控制。`keystore.properties` 与 `*.jks` 都已被 `.gitignore`
     * 明确忽略（见其中「签名材料（严禁提交）」一节），本文件只负责**读**它们。
     *
     * ## 文件不存在时**不报错**
     * 没有签名材料的人（或 CI）仍应能跑 `assembleDebug` 与单测。
     * 因此这里只在"文件存在"时才创建 signingConfig；release 变体的签名在缺失时
     * 由 AGP 报"unsigned"（而不是让整个配置阶段失败）。
     * **代价（须知）**：`assembleRelease` 在缺材料时产出的是**未签名 APK，装不上手机**。
     */
    val keystorePropsFile = rootProject.file("app/keystore.properties")
    val keystoreProps = Properties()
    if (keystorePropsFile.exists()) {
        keystorePropsFile.inputStream().use { stream -> keystoreProps.load(stream) }
    }
    val releaseStoreFile = keystoreProps.getProperty("storeFile")?.let { name -> rootProject.file("app/$name") }

    signingConfigs {
        if (releaseStoreFile != null && releaseStoreFile.exists()) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // 有签名材料就接上；没有则保持 AGP 默认（产出 unsigned，装不上）
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
        // Stage 6: the Settings "About" section (and the environment card on Home)
        // must report the real version instead of a hard-coded string.
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all { test ->
                // 单测基线为 JUnit5（AGENTS.md / 需求第 9 节）
                test.useJUnitPlatform()

                /*
                 * ★ 测试 JVM 的堆必须显式给足（阶段 10 补）。
                 *
                 * ## 为什么需要
                 * `gradle.properties` 的 `org.gradle.jvmargs=-Xmx2048m` 只作用于 **Gradle
                 * 守护进程**，**不会**传给 fork 出来的测试 worker（后者默认约 512MB）。
                 *
                 * 阶段 10 新增 `DaemonSupervisorImplTest`（用 MockK 伪造 `TriggerRepository`
                 * 等接口）后，整套测试开始以
                 * `java.lang.OutOfMemoryError: Java heap space`
                 * 在 `kotlin.reflect...ProtoBuf$PackageFragment.parseFrom`（即 ByteBuddy /
                 * kotlin-reflect 解析内建类路径）处**整体崩掉执行器** —— 症状是
                 * `TestSuiteExecutionException: Could not complete execution for Gradle Test
                 * Executor`，且**一条断言信息都没有**，很容易误判成代码问题。
                 *
                 * ⇒ 给测试 worker 单独设 2GB，与守护进程同量级。
                 */
                test.maxHeapSize = "2048m"

                // MockK 需要 ByteBuddy agent 才能伪造类。默认路径是
                // ByteBuddyAgent.installExternal()：它会 spawn 一个外部进程来执行
                // attach，而本机沙箱禁止子进程管道，导致
                //   IllegalStateException: Could not self-attach to current VM
                //     using external process
                // 用 -javaagent 预先加载 agent 后，ByteBuddy 直接复用已加载的
                // instrumentation，不再走外部进程。agent jar 由 mockk 传递引入。
                test.doFirst {
                    val agentJar =
                        test.classpath
                            .filter { it.name.startsWith("byte-buddy-agent") }
                            .joinToString("") { it.absolutePath }
                    check(agentJar.isNotEmpty()) {
                        "byte-buddy-agent jar not found on the unit test classpath; " +
                            "MockK cannot stub classes without it (see app/build.gradle.kts)."
                    }
                    test.jvmArgs("-javaagent:$agentJar")
                }
            }
        }
    }
}

ktlint {
    android.set(true)
    ignoreFailures.set(false)
}

// Room schema export: the generated JSON is committed to git and is the only
// verifiable evidence that an entity change was accompanied by a version bump
// (see RootFlowDatabase / RootFlowMigrations and the schema test).
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    // 阶段 6：UI 层的两个 lifecycle-compose 构件（与上面的 lifecycle 同版本 2.9.4）
    //   lifecycle-runtime-compose   -> collectAsStateWithLifecycle
    //   lifecycle-viewmodel-compose -> hiltViewModel()（把 ViewModel 注入 @Composable）
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    // activity 显式固定：防止被 navigation 等传递依赖抬到 1.12.x（会引入需 compileSdk 36 的
    // androidx.navigationevent）。navigation-compose 锁在 2.8.9 也是同一原因。
    implementation(libs.androidx.activity)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    // 阶段 6：图标集（只取 core，extended 的 AAR 为 34.8MB，见 libs.versions.toml 的说明）
    implementation(libs.androidx.material.icons.core)

    implementation(libs.androidx.navigation.compose)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    // 阶段 6：设置项持久化。离线缓存只有 1.1.7（见 libs.versions.toml 的 A1 报告）。
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.libsu.core)
    implementation(libs.libsu.service)

    implementation(libs.haze)
    implementation(libs.haze.materials)

    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.turbine)
    testImplementation(libs.mockk)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
}
