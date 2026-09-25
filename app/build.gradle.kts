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
        // ★ 阶段 11e 补丁7~10（此前 1.4.4 / code 12）。用户第十三条反馈起的四轮改动：
        //   ① 「设置页最底部加一张关于卡片，点击进我的 GitHub 仓库」→ `AboutSection`
        //      收敛成一行可点项，跳 `PROJECT_HOME_URL`（`ui/settings/SettingsScreen.kt`）。
        //   ② 「权限总览照这个设计重做」→ `PermissionsSection` 从纯文字列表改成卡片列：
        //      每项 = 语义图标 + 权限名 + 右侧状态胶囊（已授予**绿** / 未授予**橙**）。
        //      用户裁定未授予用橙色（"没授权"不等于"出错了"），并**去掉具体用途**
        //      （"不然显得有点乱"）⇒ `PermissionRow.detail` 随之删除。
        //   ③ 「单一的对钩太普通，每项权限给不同的图标」→ 8 个 ImageVector 从
        //      `material-icons-extended` 的字节码里逐个取出、落进
        //      `ui/settings/PermissionIcons.kt`。代价对照：APK 只涨 16 KB，
        //      而加那条依赖要 +35 MB。
        //   ④ 「把原本的 icon 图标改成这个」→ 换应用图标。**顺带挖出一个一直存在的真缺陷**：
        //      `android:icon` / `roundIcon` 原本只挂在 `<activity>` 上、`<application>` 是空的
        //      ⇒ `aapt2 dump badging` 一直报 `icon=''`，系统设置里显示的是系统默认图标，
        //      只是被"部分启动器回退到 launcher activity 图标"掩盖了。已搬到 `<application>`。
        //   versionName 会经 BuildConfig 显示在主页「App 版本」与设置页「关于」，
        //   因此改这里就等于改了真机上看到的版本号 —— 发布时**必须**与 tag 一致。
        //   ⚠️ ③ ④ **已在真机（OnePlus PLK110 / Android 16）实测通过**（桌面与应用详情页
        //      图标、权限总览观感都看过）；①② 的跳转分支只过了构建 ⇒ tag 仍带 `-unverified`。
        // ★ 12a / 12b / 12c（此前 1.4.5 / code 13）。三条用户要求串成一条线：
        //   ① **脚本危险指令扫描**（12a）：保存前弹「你确定吗」，**只做模式匹配**、不阻断保存；
        //   ② **保活知情同意的存储层**（12b）：`keepAliveConsent` 三态（`null` = 还没读到，
        //      **不可持久化**；磁盘上键缺失 ⇒ `false`，否则新用户会永远停在加载态）；
        //   ③ **保活三件套 + UI 接入**（12c）：
        //      - **安全护栏**：安全模式下看门狗**不拉服务**，`register()` 也不启动常驻监管（两处都有测试）；
        //      - **保活看门狗**：`AlarmManager` 一次性闹钟自续期（15 分钟），
        //        **零新依赖**；被系统拒绝后台重启时降级为一条如实通知；
        //      - **首启知情同意声明页**：三态分流（加载 / 声明 / 主界面），返回键 = 不同意，
        //        前台服务与通知权限的申请**一并挪到同意之后**；
        //      - 危险指令确认弹窗（12a 的 UI 半边）。
        //   真机（OnePlus PLK110 / Android 16）已验证主路径：声明页三态与返回键、
        //   同意前不拉起服务、`KEEPALIVE_HEALTH_ARMED`、服务级自愈、
        //   安全护栏的对照实验、危险弹窗三态。**两项未验**（`onTaskRemoved`、
        //   被系统拒绝时的降级通知）⇒ tag 仍带 `-unverified`。
        //   一条实测负结果：整个进程消失时本机不为心跳广播启动 App 进程（OEM 策略）
        //   ⇒ 声明页第 2 条文案已按实测改写（详见 PROJECT_STATE.md「阶段 12c 收尾记录」）。
        //   versionName 会经 BuildConfig 显示在主页「App 版本」与设置页「关于」，
        //   因此改这里就等于改了真机上看到的版本号 —— 发布时**必须**与 tag 一致。
        //   ⚠️ 这段注释会随快照发布 ⇒ **别在这里写任何本地绝对路径或设备序列号**。
        versionCode = 14
        versionName = "1.4.6"

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
            // ★ 关掉 AGP 自动塞进 APK 的 `META-INF/version-control-info.textproto`。
            // 那里面写着本次构建的 git revision，而 release APK 是在一个**独立的开发仓库**
            // （含项目状态文档、真机日志、设备标识，**从未推送**）里构建的
            // ⇒ 打进 APK 就等于把一个私有仓库的 commit SHA 挂到公开 release 上。
            // SHA 不可逆、在公开仓库里也查不到内容，但本仓库 README 声明
            // 「是有意构建的干净快照、不含开发历史」，留着它就与那句话自相矛盾。
            // 实测：加上这一行后该文件不再产出（APK 条目 163 → 162，字节数 -85）。
            // ⚠️ 这段注释本身也会被发布 ⇒ **别在这里写任何本地绝对路径**。
            vcsInfo.include = false
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
