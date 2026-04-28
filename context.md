# 项目上下文

## 基本信息
- Mod Name: JDK Jar Version 21 Enforcer
- Mod ID: jdkjarversion21enforcer
- Package: `com.andgatech.jdkjarversion21enforcer`
- Version: `0.5.12`
- Target: MC 1.7.10 + GTNH 2.8.4

## 已实现内容

### 启动期逻辑
| 类 | 阶段 | 状态 |
|----|------|------|
| `JarVersionPropertyEnforcer` | 通用属性强制器 + 反射自检 `JarFile.runtimeVersion()` | 已实现 |
| `JavaRuntimeVersion` | Java 运行时版本解析 | 已实现 |
| `JarVersion21CorePlugin` | CoreMod / `IFMLLoadingPlugin`（仅在 Java 22+ 写属性，但**通常已晚于 JarFile 初始化**） | 已实现 |
| `JarVersionAgent` | Java Agent `premain` / `agentmain`（**真正可靠**的注入点） | 已实现 |
| `JdkJarVersion21Tweaker` | LaunchWrapper Tweaker `injectIntoClassLoader`，在 Forge mod 加载**之前**跳 lwjgl3ify config patcher / 弹 RelaunchPromptDialog；同一代码路径被 `CommonProxy.preInit` 稍后作为兜底 | 已实现 |
| `RelaunchService` | 抽象 Tweaker / `preInit` 共享的客户端 fallback 流程（lwjgl3ify patcher → 对话框 → fork JVM），以 system property `jdkjarversion21enforcer.client.handled` 互锁 | 已实现 |
| `CommonProxy.preInit` | 写属性 + 反射自检 + 未生效时 WARN + 服务端脚本补丁 / 客户端兜底调 RelaunchService | 已实现 |
| `Config` | 读写 `config/jdkjarversion21enforcer.cfg`（5 个开关：两个补丁、重启弹窗、预启动重启对话框、永久抑制） | 已实现 |
| `Lwjgl3ifyConfigPatcher` | 客户端：往 `config/lwjgl3ify-relauncher.json` 的 `customOptions` 追加 `-Djdk.util.jar.version=21` | 已实现 |
| `ServerStartScriptPatcher` | 服务端：扫启动脚本，生成并行 `<name>-with-jdk-jar-21.<ext>` | 已实现 |
| `RestartPopup` | 补丁实际写入时异步弹 Swing 对话框，headless 自动跳过 | 已实现 |
| `JvmRelauncher` | 重建当前 JVM 命令行、追加 `-D...=21` 与 relaunch guard、用 `@argfile` 规避 Windows 命令行长度上限、fork 子进程并 `Runtime.halt(child.exitValue)` | 已实现 |
| `RelaunchPromptDialog` | 客户端无 lwjgl3ify 时的模态 Swing 对话框，三选项（Restart Now / Skip / Don't ask again），headless 自动 `SKIP_ONCE` | 已实现 |

### Machines
| Name | Meta ID | Type | Status |
|------|---------|------|--------|
| 无 | 无 | 无 | 未提供机器内容 |

### Items
| Name | Registration | Description |
|------|--------------|-------------|
| 无 | 无 | 未提供物品内容 |

### Blocks
| Name | Registration | Description |
|------|--------------|-------------|
| 无 | 无 | 未提供方块内容 |

### Materials
- 无自定义材料。

### Recipes
| Recipe Pool | Type | Count |
|-------------|------|-------|
| 无 | 无 | 0 |

### Config Options
| Key | Default | Description |
|-----|---------|-------------|
| `jdk.util.jar.version` | `21` | Java 版本高于 21 时由 Java Agent / CoreMod / `preInit` 强制写入的 JVM 系统属性 |
| `auto_patch_lwjgl3ify_config` | `true` | 客户端自检未生效时，自动给 `config/lwjgl3ify-relauncher.json` 的 `customOptions` 追加 `-Djdk.util.jar.version=21` |
| `auto_patch_server_start_scripts` | `true` | 服务端自检未生效时，扫描工作目录已知启动脚本并生成 `<name>-with-jdk-jar-21.<ext>` 并行版本，原脚本不动 |
| `show_restart_popup_after_patch` | `true` | 补丁实际写入时异步弹 Swing 「请重启」对话框；headless 环境自动跳过 |
| `prelaunch_relaunch_prompt` | `true` | 客户端未装 lwjgl3ify、未生效、非 headless、非 relaunch 子进程时，弹模态对话框询问是否 fork 新 JVM；用户选 Restart Now 时立即重启 |
| `prelaunch_relaunch_suppressed` | `false` | 用户选 "Don't ask again" 时被自动写为 `true`，永久关闭上述对话框；用户可以手动改回 `false` |

### Mixins
- 未启用 Mixins。

## Dependencies
- 主代码仅依赖 GTNH Gradle/Forge 开发环境，运行时使用 Mojang 自带的 Gson 2.2.4。
- 测试显式 `testImplementation 'com.google.code.gson:gson:2.2.4'` 与运行时同版本。
- JUnit 5 + JUnit Jupiter 5.10.2。

## 架构说明
- `gradle.properties` 中 `coreModClass = core.JarVersion21CorePlugin`，由 GTNH convention 插件写入 CoreMod manifest。
- `build.gradle` 的 `tasks.named('jar').configure { manifest { attributes(...) } }` 块给最终 jar 追加 `Premain-Class` / `Agent-Class` / `Can-Redefine-Classes` / `Can-Retransform-Classes`，使同一份 jar 既是 CoreMod 也是合法 Java Agent。
- `JarVersion21CorePlugin` 不注册 ASM transformer，只利用 CoreMod 加载时机尽早调用 `JarVersionPropertyEnforcer.enforce()`。**注意此时 `JarFile` 通常已被 FML 扫描 `mods/` 时初始化**，所以这里 `setProperty` 通常已晚。
- `JarVersionAgent` 提供 `premain(String)`、`premain(String, Instrumentation)`、`agentmain(String)`、`agentmain(String, Instrumentation)` 四个入口，全部委派到 `JarVersionPropertyEnforcer.enforce()`。这是**唯一保证生效**的注入点。
- `JarVersionPropertyEnforcer.detectEffectiveJarRuntimeFeatureVersion()` 反射调用 `java.util.jar.JarFile.runtimeVersion().feature()`，返回 `OptionalInt`。注意：**调用此方法会触发 `JarFile.<clinit>`**，所以严禁在 agent premain 阶段调用，只能在 `preInit` 等正常生命周期内调用。
- `CommonProxy.preInit` 在写属性后调用上述检测：
  - `EFFECTIVE`：INFO 表明已生效，**不再调用任何 patcher**。
  - `INEFFECTIVE`：WARN 给出手动加参指引，并继续走自动 patcher 链路。
  - `UNKNOWN`（pre-Java 9 / 反射失败）：跳过日志，仍尝试运行 patchers（无害）。
- `runAutoPatchersIfPossible(event)` 加载 `Config`，再根据 `FMLCommonHandler.getSide()` 分流：
  - `Side.SERVER` → `ServerStartScriptPatcher.run(gameDir)`。
  - 其他（`Side.CLIENT` / 默认） → `Lwjgl3ifyConfigPatcher.patchIfNeeded(gameDir)`。
  - 配置开关关闭时打 INFO 跳过。
- **Tweaker 加载机制**：jar manifest 声明 `TweakClass: com.andgatech.jdkjarversion21enforcer.tweaker.JdkJarVersion21Tweaker`。FML `CoreModManager` 在扫 `mods/*.jar` 时读到这一行，把 jar 加入 `Launch.classLoader` 并把类名追加到 `Launch.blackboard["TweakClasses"]`。LaunchWrapper 处理完 FMLTweaker 后会调 `JdkJarVersion21Tweaker.injectIntoClassLoader(LaunchClassLoader)`——这是 mod 住在 `mods/` 里**能运行代码的最早阶段**，与 lwjgl3ify Tweaker 同阶。
- **`JdkJarVersion21Tweaker` 职责**：仅调 `RelaunchService.runClientFlow(gameDir, configDir, Phase.TWEAKER, LOG)` 后 `RelaunchService.markHandled()`。严禁调 `JarFile.runtimeVersion()`（会锁死 `RUNTIME_VERSION`）。任何 `Throwable` 都被吞掉，保证即使失败也不会炸 LaunchWrapper；失败后 preInit 还会再试一次。指定 `getLaunchTarget() = null` / `getLaunchArguments() = []`，不介入 LaunchWrapper 的主启动目标。服务端检测通过 `FMLLaunchHandler.side().isServer()`——服务端不走 Tweaker 流程，所有动作下放到 preInit。
- **`RelaunchService.runClientFlow`** 逻辑线：
  - Java ≤ 21 → `SKIPPED_JAVA_LE_21`。
  - `JvmRelauncher.isRelaunchedChild()` → `SKIPPED_RELAUNCHED_CHILD`（防 fork 循环）。
  - **v0.5.1 新增：PROPERTY 短路**：`System.getProperty("jdk.util.jar.version") == "21"` → `SKIPPED_PROPERTY_ALREADY_SET`（启动器已传 -D，INFO 后静默）。
  - **v0.5.2 反转：RFB 不再短路**。v0.5.1 曾加过 `SKIPPED_RFB_BOOTED`（遵守 lwjgl3ify Tweaker 的 `lwjgl3ify:rfb-booted` 契约）；但实测发现 GTNH 官方 Java 17–25 整合包在 RFB 模式下**不会**传 `-Djdk.util.jar.version=21`，导致 jar 压制根本没生效。v0.5.2 删除了 `Outcome.SKIPPED_RFB_BOOTED` 枚举并取消这个短路——RFB 启动不再被信任，检测到只打一行 INFO 注解后照常进入 patcher / 对话框流程。
  - **v0.5.3 重构**：`Lwjgl3ifyConfigPatcher.patchOrCreate(gameDir)` 不再提前 return，而是将结果转换为 `RelaunchPromptDialog.PatcherStatus`（CREATED / APPLIED / ALREADY_PRESENT / NO_CONFIG_OR_ERROR）后继续走对话框流程。骨架创建是 v0.5.3 新行为：文件不存在时主动写 `{"customOptions":["-D..."]}`，这样下次非 RFB 启动时 lwjgl3ify Relauncher 会读到。
  - 对话框流程：`prelaunchRelaunchPrompt=false` → `DIALOG_DISABLED` + 兑底弹窗；`prelaunchRelaunchSuppressed=true` → `DIALOG_SUPPRESSED` + 兑底弹窗；headless → `DIALOG_HEADLESS`（兑底弹窗也 no-op）；否则弹 `RelaunchPromptDialog`。
  - 对话框选项：`RESTART_NOW` → `JvmRelauncher.relaunchAndExit(...)`（`Runtime.halt` 后不返回）；如果 return 了（fork 失败）补一个兑底弹窗。`SUPPRESS_FOREVER` → 写回 `prelaunch_relaunch_suppressed=true` + 兑底弹窗。`SKIP_ONCE` → WARN + 兑底弹窗。
- **`RelaunchService.isRfbBooted()`** ：v0.5.2 仅作为 INFO 日志注解使用（告诉用户“环境是 RFB 启动”），不再决定是否跳过流程。实现：反射读 `net.minecraft.launchwrapper.Launch.blackboard.get("lwjgl3ify:rfb-booted")`，为 `Boolean.TRUE` 时返回 `true`。任何异常都映射为 `false`。**重要认知**：GTNH 官方 RFB 整合包所谓“无感启动”是针对 lwjgl3ify GUI 而言，**不意味着 RFB 会自动帮用户传齐 `-Djdk.util.jar.version=21`**。这个认识在 v0.5.1→v0.5.2 被修正。
- **Tweaker 与 preInit 互锁**：Tweaker `injectIntoClassLoader` 结束后调 `markHandled()`，设置 system property `jdkjarversion21enforcer.client.handled=true`。`CommonProxy.preInit` 检测该标志，已处理则跳过客户端 fallback 流程。服务端脚本补丁不受这个标志影响（那是 preInit 专属路径）。
- **`ManualLauncherInstructionsPopup`**（v0.5.3 新增）：非阻塞的 INFO 提示弹窗，仅在【未设置成功】路径上调用（Skip / SUPPRESS_FOREVER / DIALOG_DISABLED / DIALOG_SUPPRESSED / fork 意外返回）。内容告诉用户怎么手动加 `-Djdk.util.jar.version=21` 或 `-javaagent:` 到各种常见启动器。`SwingUtilities.invokeLater` 异步弹出，headless 环境下 no-op。
- **`JvmRelauncher.relaunchAndExit(extraJvmArgs, gameDir, logger)`**（v0.5.4 重构签名 / v0.5.7 放弃 @argfile）：从 `RuntimeMXBean.getInputArguments()` + `sun.java.command` 重建命令行，追加 `extraJvmArgs`（含 `-Djdkjarversion21enforcer.relaunched=true` guard）。**v0.5.7 关键修复**：之前用 `@argfile` 传 args 会在中文 Windows 安装路径（如 `F:\Minecraft\1.12.2mod服务器\...`）上静默崩坏——argfile 内容用 UTF-8 写，但 java.exe 是 native C 程序读 argfile 使用系统 ANSI codepage（GBK），中文乱码后 classpath 路径错误 → RFB jar 加载失败 → `ClassNotFoundException`。v0.5.7 改用 `new ProcessBuilder(command)` 直接传 List，Windows 上 `start()` 调用 `CreateProcessW`（Unicode-safe Win32 API），完美支持任何 Unicode 路径。子进程 stdout/stderr 重定向到 `<gameDir>/logs/jdkjarversion21enforcer-relaunch-{out,err}.log`（v0.5.4 从 inheritIO 改过来，便于诊断 fork 失败）。子进程 5 秒内非零退出视为 fast-fail，返回 `OptionalInt.of(exitCode)` 让调用方保留父进程（`RelaunchService` 会在这种情况下 ERROR 日志 + 调起 `ManualLauncherInstructionsPopup` 后允许原 LaunchWrapper 继续启动游戏，避免启动器看到“游戏崩溃”）。成功后 `System.exit(子进程 exitCode)`（从 v0.5.3 的 `Runtime.halt` 改过来，让 shutdown hooks 跑完）。
- **`JvmRelauncher.discoverExtraClasspathEntries(existingJvmArgs, logger)`**（v0.5.5 新增 / v0.5.6 加强 / **v0.5.12 去掉过度收集策略**）：两个精确策略同时运行找 `-Djava.system.class.loader` 指定类所在的 jar，以修复 GTNH RFB 整合包（`RfbSystemClassLoader`）启动后清理 `java.class.path` 系统属性导致子 JVM `initPhase3` `ClassNotFoundException` 的问题：
  - **策略 A**：`Class.forName(FQN).getProtectionDomain().getCodeSource().getLocation()`。仅适用于 ProtectionDomain 带 CodeSource 的 ClassLoader。
  - **策略 B**（v0.5.6 新增）：`ClassLoader.getResource(<class file>)` 拿到形如 `jar:file:/.../foo.jar!/com/.../X.class` 的 URL，用 `parseJarPathFromResourceUrl` 解析出 jar 路径。**不依赖 ProtectionDomain**，可应付自定义 ClassLoader。
  - **策略 C（已移除）**：v0.5.6 加的 `harvestUrlClassLoaderChain` 扫描所有 URLClassLoader URLs——v0.5.12 诊断后发现过度收集：`LaunchClassLoader extends URLClassLoader` 里有所有 156 个 mod jar，prepend 到子进程 -cp 后、与 `java.class.path` 里原本就有的 mod jar dedupe 不完全路径格式稍有差异会漏出重复项，子 JVM FML 报 `Found a duplicate mod`。v0.5.12 去掉这个策略。
  - 所有策略在 `[relaunch-cp]` 标签下打详细 INFO/WARN 日志。`mergeClasspath(extras, existing)` 用 `LinkedHashSet` 去重 + `File.pathSeparator` join。
- **主 args 获取优先级**（v0.5.11）：
  1. **`LaunchArgsCapture.rebuildMainArgs()`**首选：在 `JdkJarVersion21Tweaker.acceptOptions(...)` 里 LaunchWrapper 会传给我们已解析的 `(args, gameDir, assetsDir, profile)`（`args` 是 nonOption args 的 `List<String>`，元素边界保留原始 `String[]` 的 quote 边界）。`LaunchArgsCapture.capture(...)` 保存到 static 字段；`rebuildMainArgs()` 拼接 `--version <profile> --gameDir <p> --assetsDir <p> --tweakClass <cls> + args` 返回完整 main args。`--tweakClass` 从 `Launch.tweakClassNames` 等字段反射读取（或 fallback 到 `cpw.mods.fml.common.launcher.FMLTweaker`）。**不依赖 OS / java.exe 启动方式，是最可靠的源**。
  2. **`tryGetMainArgsFromProcessHandle`**（v0.5.8 新增 / v0.5.9 多策略 + commandLine 回退 / v0.5.10 修 Java 9+ 模块访问）：反射调 `ProcessHandle.current().info().arguments()`（Java 9+ API）拿 OS 级别原始 argv（保留 token 边界）。v0.5.9 加三个并联策略找 main args 边界（`locateMainArgsStart`）：(1) `-jar <path>` 模式 → main args 从 -jar+2 开始；(2) main class 精确匹配；(3) main class 是 jar basename 时路径后缀模糊匹配。如果 `arguments()` 返回 Optional.empty()（Windows 某些 OS 拒绝查询），fallback 到 `info().commandLine()` 完整命令行字符串，用 `tokenizeCommandLine` 自己解析（支持 `"`/`'` 引号、`\\` `\"` 转义）。这是为了修复 HMCL 用 `-jar mmc-bootstrap-1.0.jar` 启动时 sun.java.command 是 jar basename 但 OS argv 是全路径、v0.5.8 精确匹配失败的问题。**v0.5.10 关键修复**：v0.5.9 部署后发现两个反射都报 `IllegalAccessException: cannot access a member of class java.lang.ProcessHandleImpl$Info (in module java.base)`——原因是 `info.getClass()` 返回实现类 `ProcessHandleImpl$Info`（package-private），Java 9+ 模块系统禁止跨模块反射访问实现类的成員。修复：在公开接口 `java.lang.ProcessHandle$Info`（`Class.forName("java.lang.ProcessHandle$Info")`）上查找 method，避开实现类的模块访问限制。`appendMainClassAndArgs` 是主入口；诊断日志以 `[main-args]` 标签。
- **`RelaunchPromptDialog`**（v0.5.3 重写）：EDT 上 `JOptionPane.showOptionDialog`，三按钮文本是 「Apply & Restart Now (recommended)」 / 「Skip this time」 / 「Don't ask again」。标题从 v0.5.2 的 「Restart required」 改为 「Configure JVM startup arguments」，图标从 WARNING 改为 QUESTION，文案从“出问题了请你修”转变为“我帮你做了 / 会做，请确认”。`PatcherStatus` 参数让上半部分反映 patcher 实际发生了什么。headless 环境直接返回 `SKIP_ONCE`，绝不初始化 AWT。
- `Lwjgl3ifyConfigPatcher`（v0.5.3 加 `patchOrCreate` 与 `Result.CREATED`）用 Gson 旧版 API（`new JsonParser().parse(text)`）以保持与 Mojang 自带的 Gson 2.2.4 兼容；输出禁用 HTML escaping，保证 `=` 不变成 `\u003d`，方便用户人肉编辑。不存在时创建骨架 `{"customOptions":["-Djdk.util.jar.version=21"]}`。
- `ServerStartScriptPatcher` 用 `\bjava\w*(?:\.exe)?\b` 定位 java 命令，跳过注释行（`#`、`//`、`::`、`REM`），只补丁第一条形如 java 命令的行；CRLF 与 LF 行尾被保留；并行文件已存在则不覆盖。
- `RestartPopup` 仅在 patcher **真的写了文件**（`Lwjgl3ifyConfigPatcher.APPLIED` / `ServerStartScriptPatcher.PATCHED`）且配置开关为 `true` 时调用；用 `SwingUtilities.invokeLater` 异步弹，不阻塞 preInit；`GraphicsEnvironment.isHeadless()` 检查后才尝试初始化 AWT，所有异常都被吞掉。
- 由于全部行为都被 `activationLogMessage(...)` 在 preInit 第一行 gate 住（Java ≤ 21 时直接 return），所有 patcher 与弹窗都**仅在 Java 22+ 时**才有机会执行。
- `build.gradle` 的开发运行参数也包含 `-Djdk.util.jar.version=21`，方便本地 `runClient` / `runServer` 与实际目标保持一致。
- 构建产物 `jdkjarversion21enforcer-0.5.12.jar` 的 manifest 同时包含 `FMLCorePlugin`、`FMLCorePluginContainsFMLMod: true`、`Premain-Class`、`Agent-Class`、`TweakClass`。**同一份 jar 同时是 mod / CoreMod / Java Agent / LaunchWrapper Tweaker**。
- 最新发布 jar 由 `gradlew.bat spotlessApply build` 生成，路径为 `build/libs/jdkjarversion21enforcer-0.5.12.jar`。
- `README.md` 引用 `docs/assets/QQ_1777283414511.png` 解释模组诞生原因。

## 可靠性矩阵
| Java 版本 | 场景 | 本 mod 动作 |
|-----------|------|--------------|
| ≤ 21 | 任何 | no-op |
| 22+ | 启动器已传 `-Djdk.util.jar.version=21` 或 `-javaagent:` | INFO 后静默不动，JVM 本身保证生效 |
| 22+ | **GTNH 官方 RFB 整合包（实测不传 -D）** | v0.5.2 不再静默；Tweaker 弹模态框 + fork 子 JVM，1 次重启后生效 |
| 22+ | 独立启动器（手动装机）、装了 lwjgl3ify | Tweaker 写 lwjgl3ify customOptions，1 次重启后生效 |
| 22+ | 独立启动器（手动装机）、无 lwjgl3ify config | Tweaker 阶段弹模态框 + fork 子 JVM，1 次重启后生效 |
| 22+ | 服务端 | preInit 生成并行启动脚本 `<原名>-with-jdk-jar-21.<ext>`，原脚本不动 |
