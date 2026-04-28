# 开发日志

## 2026-04-28: 去掉 classpath 过度收集策略，修复子进程 mod 重复加载（v0.5.12）

### 用户反馈（v0.5.11 终于跑到 mod loading 阶段了）
v0.5.11 装上后，子进程**第一次跑到 mod loading 阶段**——但量裂出一个老 bug。每个 mod 报错被发现 3 次，且**3 次都是同一个 jar 路径**：

```text
[Client thread/ERROR]: Found a duplicate mod gtnhlib at [
  F:\Minecraft\1.12.2mod服务器\.minecraft\versions\GT New Horizons 2.8.4\mods\gtnhlib-0.7.10.jar,
  F:\Minecraft\1.12.2mod服务器\.minecraft\versions\GT New Horizons 2.8.4\mods\gtnhlib-0.7.10.jar,
  F:\Minecraft\1.12.2mod服务器\.minecraft\versions\GT New Horizons 2.8.4\mods\gtnhlib-0.7.10.jar
]
```

156 个 mod 全都报。但**这其实是好消息**：v0.5.11 的 LaunchArgsCapture 真的修好了 main args，子进程第一次跑到 mod loading 这么远——之前 v0.5.7~v0.5.10 都崩在 Angelica NPE 之前，所以这个老 bug 一直没暴露。

### 根因
v0.5.6 加的「策略 C」`harvestUrlClassLoaderChain` 扫描 context loader / system loader / `JvmRelauncher.class` loader 及它们 parent 链上**所有 URLClassLoader 的 URLs**。但 GTNH 的 `LaunchClassLoader extends URLClassLoader`，其中**有所有 156 个 mod jar**——我们 prepend 全部到子进程 -cp，加上 `java.class.path` 里**原本就有的**这 156 个 mod jar：

- `mergeClasspath` 用 `LinkedHashSet` 去重——但**只能去除完全相同的 String**
- 任何路径格式差异（trailing slash、case、`/` vs `\`）都会让 dedupe 漏过
- 子进程 -cp 里 mod jar 出现 2 次

子 JVM 启动后 FML：
- 扫 `mods/` 目录 1 次
- 扫 -cp 中 mod jar 2 次
- = 每 mod 被发现 3 次 → `Found a duplicate mod` → 启动中止

### 已完成

**1. 删掉策略 C**

`@d:\Code\JdkJarVersion21Enforcer\src\main\java\com\andgatech\jdkjarversion21enforcer\relaunch\JvmRelauncher.java:603-664` 的 `discoverExtraClasspathEntries` 现在只跑：
- 策略 A：`Class.forName(FQN).getProtectionDomain().getCodeSource().getLocation()`
- 策略 B：`ClassLoader.getResource(FQN.class)` + `parseJarPathFromResourceUrl`

两个策略**只**返回系统类加载器 FQN 所在的 jar——**与 `java.class.path` 不交重**。

`harvestUrlClassLoaderChain` 整个方法被删掉，留一段注释解释为啥（防止后人重新加回来）。

**2. 测试 + 构建**
- 现有 98 个单测全过（`harvestUrlClassLoaderChain` 没有专门测试，去掉后没影响）
- `gradlew.bat spotlessApply build reobfJar --rerun-tasks` 通过
- `build/libs/jdkjarversion21enforcer-0.5.12.jar` ≈ 60 KB（比 v0.5.11 略小，没了 harvest 那段代码）

**3. 版本与文档**
- `modVersion` 升至 `0.5.12`
- README / context.md / log.md / ToDOLIST.md 同步

### 决策
- **完全删除而不是 disable**：没有 feature flag，没有保留接口。策略 C 是过度设计，留着只会让人想再用。
- **不去改 `mergeClasspath` 做更聪明的 dedupe**：根本问题是不该 prepend 重复的 entries，更聪明的 dedupe 只是治标。从源头杜绝。
- **保留 `URLClassLoader` import**：JavaDoc 里 `{@link URLClassLoader}` 还在引用（解释为啥**不**用它）。

### 遗留 / 后续
- v0.5.12 e2e：用户重测点 Apply & Restart Now，期望：
  - 父进程 fml-client-latest.log 出现 `[relaunch-cp] discovered N extra entries to prepend onto child -cp:` 其中 **N 是个位数**（之前是 156+）
  - `[main-args] using LaunchArgsCapture (captured at Tweaker stage) for main args (NN tokens, all quote boundaries preserved)`
  - 子进程 fml-client-latest.log **不再**出现 `Found a duplicate mod`
  - 子进程跑到主菜单（不再 fast-fail）
  - 子进程 fml-client-latest.log 出现 `Verified ... runtimeVersion().feature() = 21; ... is effective.`

---

## 2026-04-28: 从 LaunchWrapper Tweaker 阶段捕获 main args（v0.5.11）

### 用户反馈
v0.5.10 部署后用户提供 `[main-args]` 诊断：

```text
[16:22:24] [main/WARN]: [main-args] ProcessHandle.info().arguments() is empty
  (typical on Windows when the OS denies the query); will try commandLine() instead
[16:22:24] [main/WARN]: [main-args] ProcessHandle.info().commandLine() is empty;
  falling back to sun.java.command split
[16:22:24] [main/WARN]: [main-args] FALLBACK: split sun.java.command tail by spaces
  into 26 tokens; ...
```

v0.5.10 反射本身**通了**（不再 IllegalAccessException），但 Windows OS 拒绝查询当前进程的 commandLine——`arguments()` 和 `commandLine()` 都返回 `Optional.empty()`。这是 Windows 上**已知的难题**：某些 launcher 启动方式 / 安全策略会让 `NtQueryInformationProcess` 失败。继续 fallback 到 split → 仍然切碎 `--version "GT New Horizons 2.8.4"` → 子进程跑错 gameDir → fast-fail → 父进程兜底（jar 压制没生效）。

### 关键洞察
**我们在 LaunchWrapper Tweaker 阶段执行**，LaunchWrapper 本身会调用我们 `Tweaker.acceptOptions(List<String> args, File gameDir, File assetsDir, String profile)`。这里：
- `args` 是 LaunchWrapper OptionParser 处理后的 nonOption args（`List<String>` 元素**边界保留**了原始 `String[] args`，因为是从 `Arrays.asList(args).subList(...)` 派生的）
- `gameDir`、`assetsDir`、`profile` 是 OptionParser 消费的 known options（`--gameDir`、`--assetsDir`、`--version`）

加起来足够**精确**重建 main args，**完全不依赖** OS / java.exe 启动方式 / ProcessHandle / sun.java.command。这是最可靠的源。

### 已完成

**1. 新增 `LaunchArgsCapture`**（`src/main/java/com/andgatech/jdkjarversion21enforcer/relaunch/LaunchArgsCapture.java`）

- `capture(args, gameDir, assetsDir, profile, logger)`：保存 4 个值到 static 字段；同时 `readTweakClassNames` 反射读 `Launch.tweakClassNames` / `Launch.tweakClasses` / `Launch.tweaks` 任一存在的字段（fallback 到 `cpw.mods.fml.common.launcher.FMLTweaker`）
- `rebuildMainArgs()`：拼接 `--version <profile> --gameDir <p> --assetsDir <p> --tweakClass <cls> + nonOption args` 返回完整 main args；引号边界完整保留
- `[launch-args]` 诊断日志：每个 nonOption token 都打到 fml-client-latest.log

**2. `JdkJarVersion21Tweaker.acceptOptions` 调 `LaunchArgsCapture.capture(...)`**

之前只用 gameDir 一个值，现在 4 个全转发到 LaunchArgsCapture。capture 失败仅打 WARN 不抛（best-effort）。

**3. `JvmRelauncher.appendMainClassAndArgs` 加 PRIORITY 1 路径**

```java
List<String> capturedArgs = LaunchArgsCapture.rebuildMainArgs();
if (capturedArgs != null && !capturedArgs.isEmpty()) {
    cmd.addAll(capturedArgs);
    logger.info("[main-args] using LaunchArgsCapture (captured at Tweaker stage) for main args ({} tokens, all quote boundaries preserved)", capturedArgs.size());
    return;
}
// PRIORITY 2: ProcessHandle.info() ...
// PRIORITY 3: split ...
```

**4. 测试**
- 新增 `LaunchArgsCaptureTest`，5 个测试覆盖 capture / rebuild / null inputs / FMLTweaker fallback
- 现有 `JvmRelauncherTest` 加 `@BeforeEach resetCapturedLaunchArgs()` 保证状态隔离
- 总测试数 93 → **98**，全部通过

**5. 版本与产物**
- `modVersion` 升至 `0.5.11`
- `gradlew.bat spotlessApply build reobfJar --rerun-tasks` 通过
- `build/libs/jdkjarversion21enforcer-0.5.11.jar` ≈ 60 KB

### 决策
- **优先 Tweaker 而非 ProcessHandle**：Tweaker 阶段 LaunchWrapper 主动给我们已解析的 args，这是**完全可控**的源；ProcessHandle 受 OS 安全策略影响，**不可控**。
- **保留 ProcessHandle / split fallback**：万一我们的 Tweaker 没跑（极端边界场景，如 RFB 修改 Tweaker 加载顺序），仍然有 fallback 路径。
- **`--tweakClass` 反射读 + FMLTweaker fallback**：每个 Forge / GTNH 环境都用 FMLTweaker，硬编码 fallback 是安全选择。

### 遗留 / 后续
- v0.5.11 e2e：用户重测点 Apply & Restart Now，期望：
  - log 出现 `[launch-args] captured at Tweaker stage: profile=GT New Horizons 2.8.4, gameDir=..., assetsDir=..., tweakers=[FMLTweaker, ...]`
  - log 出现 `[main-args] using LaunchArgsCapture (captured at Tweaker stage) for main args (NN tokens, all quote boundaries preserved)`
  - 子进程 fml-client-latest.log 在**正确**的 `versions\GT New Horizons 2.8.4\` 目录下（不再是 `versions\GT\`）
  - 子进程 exit 0（不再 fast-fail）
  - 子进程 fml-client-latest.log 出现 `Verified ... runtimeVersion().feature() = 21; ... is effective.`

---

## 2026-04-28: Java 9+ 模块访问问题（反射 ProcessHandle.Info 走接口）（v0.5.10）

### 用户反馈（终于看到诊断日志）
v0.5.9 装上后用户搜 fml-client-latest.log 里 `[main-args]` 终于贴回来：

```text
[13:47:01] [main/WARN]: [main-args] ProcessHandle.arguments() reflection failed:
  IllegalAccessException: class com.andgatech.jdkjarversion21enforcer.relaunch.JvmRelauncher
  cannot access a member of class java.lang.ProcessHandleImpl$Info (in module java.base)
  with modifiers "public"
[13:47:01] [main/WARN]: [main-args] ProcessHandle.commandLine() reflection failed:
  IllegalAccessException: <same as above>
[13:47:01] [main/WARN]: [main-args] FALLBACK: split sun.java.command tail by spaces into 26 tokens; ...
```

两个反射都因 `IllegalAccessException` 失败，回退到 split 切碎了 `--version "GT New Horizons 2.8.4"` → 子进程跑错 gameDir → Angelica NPE → 4353 ms 内 exit -1（fast-fail < 5000 ms）→ 父进程兜底跑游戏（jar 压制没生效）。

### 根因
我之前用：

```java
Object info = phCls.getMethod("info").invoke(current);
Object argsOpt = info.getClass()  // <-- 这里！
    .getMethod("arguments")
    .invoke(info);
```

`info.getClass()` 返回**实现类** `java.lang.ProcessHandleImpl$Info`（package-private）。Java 9+ 模块系统**禁止跨模块反射访问实现类的成员**——即使方法本身是 public，从外部模块（应用代码）访问 `java.base` 模块的实现类也会被拦截。

正确做法：在**公开接口** `java.lang.ProcessHandle.Info`（外部类 `$` 内部接口写法 = `java.lang.ProcessHandle$Info`）上 lookup method。接口本身 public，模块访问检查通过。

### 已完成

**1. 两个反射点都改用接口 lookup**

```java
Object info = phCls.getMethod("info").invoke(current);
Class<?> infoCls = Class.forName("java.lang.ProcessHandle$Info");  // 接口！
Object argsOpt = infoCls.getMethod("arguments").invoke(info);
```

`commandLine()` 反射做同样修改。两处都加注释解释为啥不能用 `info.getClass()`。

**2. 测试 + 构建**
- 现有 93 个单测全过（locateMainArgsStart / tokenizeCommandLine 不依赖 ProcessHandle 实例，没受影响）
- `gradlew.bat spotlessApply build` + `reobfJar --rerun-tasks` 通过
- `build/libs/jdkjarversion21enforcer-0.5.10.jar` ≈ 58 KB

**3. 版本与文档**
- `modVersion` 升至 `0.5.10`
- README / context.md / log.md / ToDOLIST.md 同步

### 决策
- **不用 setAccessible(true) 强突破模块限制**：在 Java 17+ 默认禁止用 setAccessible 突破，需要 `--add-opens java.base/java.lang=ALL-UNNAMED`，无法在程序内静默处理。改用接口 lookup 是更正确的解决方案。
- **改动最小化**：只修两个 `info.getClass()` 调用。三个 strategy / commandLine 解析 / tokenize 都不动。

### 遗留 / 后续
- v0.5.10 e2e：用户重测点 Apply & Restart Now，期望 fml-client-latest.log 出现：
  - `[main-args] ProcessHandle.info().arguments() returned NN OS-level tokens:`（不再是 IllegalAccessException）
  - 后跟每个 args[N] 一行 OS-level token（保留 token 边界）
  - `[main-args] strategy 1/2/3 hit: ...`
  - `[main-args] using ProcessHandle.info().arguments() for main args (XX tokens, spaces preserved)`
  - 子进程 exit code 0（不再是 -1）
  - 子进程 fml-client-latest.log（在**正确的** `versions\GT New Horizons 2.8.4\` 下）出现 `Verified ... runtimeVersion().feature() = 21; ... is effective.`

---

## 2026-04-28: -jar 模式 + commandLine fallback（v0.5.8 在 HMCL 上失败的修复）（v0.5.9）

### 用户反馈
v0.5.8 装上后用户重新点 Apply & Restart Now，子进程 stdout 仍报：

```text
[main/INFO]: [net.minecraft.client.main.Main:main:144]:
  Completely ignored arguments: [New, Horizons, 2.8.4, New, Horizons, 2.8.4]
Launched Version: GT
versions\GT\crash-reports\...
```

`--version` 仍然只拿到 `GT`——v0.5.8 的修复没起作用，但**子进程仍能跑到 LaunchWrapper / FML / Minecraft init 阶段**（崩在 Angelica NPE，跟 v0.5.7 同样的下游崩溃）。

### 关键诊断
查 stack trace 发现父进程 main 是 HMCL：

```text
at System//org.jackhuang.hmcl.HMCLMultiMCBootstrap.launch(HMCLMultiMCBootstrap.java:80)
at System//org.jackhuang.hmcl.HMCLMultiMCBootstrap.launchV1(HMCLMultiMCBootstrap.java:54)
at System//org.jackhuang.hmcl.HMCLMultiMCBootstrap.main(HMCLMultiMCBootstrap.java:46)
```

而 stderr 里：

```text
file:/F:/Minecraft/1.12.2mod服务器/.minecraft/libraries/org/jackhuang/hmcl/mmc-bootstrap/1.0/mmc-bootstrap-1.0.jar
```

——HMCL 用 `-jar <full-path-to-mmc-bootstrap-1.0.jar>` 启动 java.exe。Oracle 文档：`sun.java.command` 在 `-jar` 模式下记录的是 **jar basename**（`mmc-bootstrap-1.0.jar`），不是 main class FQN。但 ProcessHandle.info().arguments() 里是 `-jar <full-path>`——v0.5.8 用 `mainClass.equals(args[i])` 精确匹配 `mmc-bootstrap-1.0.jar` 在含全路径的 args 数组里**找不到**，return null，fallback 到 split → 切碎。

### 已完成

**1. `locateMainArgsStart` 三个并联策略**

抽出独立方法，三个策略：
- **策略 1：`-jar <path>` 模式**：扫描 args 找 `-jar`，main args 从 `-jar` 后第 2 个 token 开始。HMCL / Forge / `java -jar` 等等用这种模式。
- **策略 2：精确 mainClass 匹配**：当 OS argv 直接含 mainClass FQN（`-cp <classpath> <main-class>` 模式），向后取。
- **策略 3：路径后缀模糊匹配**：mainClass 看起来是 jar basename（如 `mmc-bootstrap-1.0.jar`），扫 args 找 `endsWith("/" + mainClass)` 或 `endsWith("\\" + mainClass)` 的 token。

每个策略 hit 时都打 `[main-args] strategy N hit: ...` 让用户能看到哪条路径。

**2. `commandLine()` fallback**

如果 `arguments()` 返回 Optional.empty()（Windows 某些 OS 拒绝查询），fallback 到 `info().commandLine()` 拿完整命令行字符串，然后调新加的 `tokenizeCommandLine` 自己解析：
- 支持 `"` 双引号 / `'` 单引号包围（保留内部 whitespace）
- 支持 `\\` 反斜杠转义（在 quote 内部）
- 支持 `\"` 引号转义（在 quote 内部）

**3. 大量诊断日志**

每个 `args[i]` 都打到 fml-client-latest.log；commandLine() 字符串也打；tokenize 后的每个 token 也打。下次复现时所有信息都在 log 里。

**4. 单元测试**
- `locateMainArgsStartFindsJarMode` —— 验证策略 1
- `locateMainArgsStartFindsExactMainClass` —— 验证策略 2
- `locateMainArgsStartFindsPathSuffixForJarBasename` —— 验证策略 3
- `locateMainArgsStartReturnsNegativeWhenNotFound`
- `tokenizeCommandLineHandlesQuotedArgsWithSpaces` —— Windows GetCommandLineW 的典型输出
- `tokenizeCommandLineHandlesEscapedQuotesAndBackslashes`
- `tokenizeCommandLineEmptyOrNull`

总测试数 86 → **93**，全部通过。

**5. 版本与产物**
- `modVersion` 升至 `0.5.9`
- `gradlew.bat spotlessApply build` 通过
- `build/libs/jdkjarversion21enforcer-0.5.9.jar` ≈ 58 KB
- README / context.md / log.md / ToDOLIST.md 同步

### 决策
- **三策略并联但有先后**：`-jar` 模式必须**优先**——因为 sun.java.command 在 `-jar` 模式下报 jar basename，如果策略 2 / 3 用 jar basename 反而能匹配到 -jar 的 path，但 main args 边界算错（main args 应在 jar path 之后，不在 mainClass 之后）。`-jar` 总是 args 里**带 -jar 字面量**的特征，最可靠优先。
- **commandLine 单独 fallback 而非合并**：`arguments()` 和 `commandLine()` 行为不同（前者 token，后者 string），代码路径完全不同；分两个方法清晰。
- **`tokenizeCommandLine` 简化版**：不完整模仿 cmd.exe / bash 的 quoting（那有非常多 corner case），只支持最常见的双引号/单引号/反斜杠转义。够用。

### 遗留 / 后续
- v0.5.9 e2e：用户重测点 Apply & Restart Now，期望 fml-client-latest.log 出现：
  - `[main-args] ProcessHandle.info().arguments() returned NN OS-level tokens:` 后跟每个 args 一行
  - `[main-args] strategy 1 hit: -jar at index N` 或 strategy 2/3 之一
  - `[main-args] using ProcessHandle.info().arguments() for main args (XX tokens, spaces preserved)`
  - 子进程 fml-client-latest.log 在**正确的** `versions\GT New Horizons 2.8.4\` 路径下（不再是 `versions\GT\`）
  - 子进程 fml-client-latest.log 出现 `Verified ... runtimeVersion().feature() = 21; ... is effective.`
  - 游戏正常进入主菜单
- 如果 v0.5.9 还失败：用户发回 fml-client-latest.log 里 `[main-args]` 标签所有行——里面有 OS argv 全部 token、commandLine 字符串、tokenize 结果，足够定位真正问题。

---

## 2026-04-28: 含空格的 main args 保留（v0.5.8）

### v0.5.7 验证结果（重大进展）
v0.5.7 jar 替换后，子 JVM **真的启动了**：

```text
[main/INFO]: Successfully scanned 156 paths for RFB plugins.
[main/INFO]: Constructed RFB plugin gtnhlib@0.7.10 (GTNHLib): ...
[main/INFO]: Constructed RFB plugin lwjgl3ify@2.1.16 (LWJGL3ify): ...
[main/INFO]: Forge Mod Loader version 7.99.40.1614 for Minecraft 1.7.10 loading
```

子进程跑过了 phase3、加载了 RFB plugin、走完了 LaunchWrapper、初始化了 Forge——RfbSystemClassLoader 问题彻底解决，v0.5.5/0.5.6/0.5.7 三步定位真正胜利。

### 但发现新崩溃
子进程在 Minecraft init 阶段崩于 Angelica：

```text
java.lang.NullPointerException: Cannot invoke
  "com.gtnewhorizons.angelica.glsm.dsa.DSAAccess.bindTextureToUnit(int, int)"
  because "com.gtnewhorizons.angelica.glsm.RenderSystem.dsaState" is null
```

### 根因（在子进程 stdout log 里）
两条关键证据：

```text
[main/INFO]: [net.minecraft.client.main.Main:main:144]:
  Completely ignored arguments: [New, Horizons, 2.8.4, New, Horizons, 2.8.4]
```

```text
java.io.FileNotFoundException:
  F:\Minecraft\1.12.2mod服务器\.minecraft\versions\GT\config\hodgepodgeEarly.properties
```

注意路径 `versions\GT\` 而不是 `versions\GT New Horizons 2.8.4\`！父进程的命令行真实情况是 `--version "GT New Horizons 2.8.4"`（含空格的引号包围 arg），但 `sun.java.command` 是单字符串，丢失了引号。我们用 `mainCommand.split(" ")` 简单分词后变成 `["--version", "GT", "New", "Horizons", "2.8.4", "--gameDir", ...]`，`--version` 只拿到 `GT`，`--gameDir` 也被切错。子进程跑成 `versions/GT/`，hodgepodgeEarly.properties 找不到 → 后续 mod 初始化竞态 → Angelica `RenderSystem.dsaState` 是 null。

### 已完成

**1. `JvmRelauncher.tryGetMainArgsFromProcessHandle(mainClass, logger)`**（新增）

反射调 `ProcessHandle.current().info().arguments()`（Java 9+ API），拿到 **OS 级别原始 argv**（保留 token 边界）。在 argv 中找到 main class 后取完整含空格的 main args。

```java
Class<?> phCls = Class.forName("java.lang.ProcessHandle");
Object current = phCls.getMethod("current").invoke(null);
Object info = phCls.getMethod("info").invoke(current);
Object argsOpt = info.getClass().getMethod("arguments").invoke(info);
// argsOpt 是 Optional<String[]>，反射调 isPresent / get
```

ProcessHandle 是 Java 9+，但项目 source level 1.8 用 Jabel 编译——必须反射调，运行时 Java 22+ 一定可用。

**2. `JvmRelauncher.appendMainClassAndArgs(cmd, mainCommand, logger)`**（新增）

主入口：
1. 从 `sun.java.command` 取第一个空格前的 main class（main class 名不含空格，安全）
2. 调 `tryGetMainArgsFromProcessHandle` 拿 raw main args
3. 失败时 fallback 到 `tail.split(" ")` 但打 WARN 提示用户「主对话框打开后游戏可能跑错 gameDir」

**3. `[main-args]` 诊断日志**

每一步都打：
- `[main-args] ProcessHandle.info().arguments() returned NN OS-level tokens`
- `[main-args] using ProcessHandle.info().arguments() for main args (XX tokens, spaces preserved)` — 成功
- `[main-args] FALLBACK: split sun.java.command tail by spaces into N tokens; args containing spaces will be broken (e.g. \`--version "GT New Horizons"\` becomes 4 separate tokens)` — 失败

**4. 测试**

`buildChildCommand` 已有的测试用 `mainCommand = "net.minecraft.launchwrapper.Launch --version 1.7.10"`——测试 JVM（JUnit）自身的 ProcessHandle 找不到这个 main class（会找到 `worker.org.gradle.process.internal.worker.GradleWorkerMain` 之类的），返回 null，触发 fallback split → 测试断言仍然过。**86 个测试全部通过**。

**5. 版本与产物**
- `modVersion` 升至 `0.5.8`
- `gradlew.bat spotlessApply build` 通过
- `build/libs/jdkjarversion21enforcer-0.5.8.jar` ≈ 57 KB

### 决策
- **优先 ProcessHandle.info().arguments() 而非 commandLine()**：`commandLine()` 返回单字符串，需要自己解析 quote / escape，容易出错；`arguments()` 已经是 String[]，token 边界由 OS 决定，最可靠。
- **不修改原 `mainCommand.split(" ")` fallback**：保留为兜底；只是加 WARN 让用户知道这条路径会出问题。如果用户的 ProcessHandle 失败，至少打了 WARN 给我们留诊断线索。
- **Java 8 source level + 反射调 Java 9+ API**：跟之前 `Process#pid()` 一样的处理。运行时 Java 22+ 一定有这些 API。

### 遗留 / 后续
- v0.5.8 e2e：用户重新点 Apply & Restart Now，期望：
  - log 里出现 `[main-args] using ProcessHandle.info().arguments() for main args (XX tokens, spaces preserved)`
  - 子进程的 `versions\GT New Horizons 2.8.4\` 路径下生成正确的 fml-client-latest.log（不再是 `versions\GT\`）
  - 子进程进入主菜单（Angelica NPE 不再发生，因为 hodgepodge 等正确加载了）
  - 子进程 fml-client-latest.log 出现 `Verified ... runtimeVersion().feature() = 21; ... is effective.`
- 如果 ProcessHandle 失败（`[main-args] ... empty`），需要进一步 fallback：解析 `info.commandLine()` 字符串自己 tokenize，处理引号转义。这是更多工作。

---

## 2026-04-28: 中文路径下 @argfile 编码问题修复（v0.5.7）

### 用户反馈
v0.5.6 装上后用户发回完整 `[relaunch-cp]` 诊断日志：

- **三个策略全部成功**——156 个 classpath entries 被发现，包括 RFB jar 在 `[0]` 位
- 但子 JVM **仍然**报 `RfbSystemClassLoader ClassNotFoundException`

诊断日志里关键信息：用户的安装路径含**中文**：

```text
F:\Minecraft\1.12.2mod服务器\.minecraft\versions\GT New Horizons 2.8.4\libraries\lwjgl3ify-2.1.16-forgePatches.jar
```

### 真正根因
我们用 `@argfile` 传 args，argfile 内容用 `StandardCharsets.UTF_8` 写。但 java.exe 是 **native C 程序**，读 argfile 时**使用系统 ANSI codepage**（中文 Windows = GBK / CP936），不是 UTF-8。

中文「服务器」UTF-8 编码字节序列：`E6 9C 8D E5 8A A1 E5 99 A8`
被 GBK 错误解码为：乱码（GBK 把这些字节解释成另一组完全不同的字符）

→ argfile 里所有含中文的 classpath 路径都被解读为乱码 → 子 JVM `-cp` 包含**不存在的路径** → 找不到 RFB jar → ClassNotFoundException → JVM 启动失败。

这是 Windows + Java 在 `@argfile` 上的**已知陷阱**——即使 Java 18+ JEP 400 把 `Charset.defaultCharset()` 改成 UTF-8，但 java.exe **C 启动器**仍用 ANSI codepage 读 argfile（这是 native code 路径，不走 JEP 400）。

### 已完成

**1. 完全放弃 `@argfile`**

之前：
```java
ProcessBuilder pb = new ProcessBuilder(command.get(0), "@" + argfile.toAbsolutePath());
```

v0.5.7：
```java
ProcessBuilder pb = new ProcessBuilder(command);
```

直接把 List<String> 传给 ProcessBuilder。Windows 上 `start()` 内部走 `CreateProcessW`（Win32 Wide-char API），整个命令行用 UTF-16，**完美支持任何 Unicode 路径**（中文 / 日文 / 韩文 / emoji）。

**2. 命令行长度监控**

156 entries × 平均 ~150 字符（含中文路径）≈ 23000 字符，仍在 Windows `CreateProcessW` 的 32767 字符限制内。打 INFO 显示总长度 + 接近上限时 WARN：

```text
Relaunch: child argv has 158 tokens, ~23xxx chars total (Windows CreateProcessW limit: 32767)
```

**3. 移除 argfile 写入路径**

`writeArgfile` / `escapeArgfileToken` 方法保留（可能未来用），但不再被 `relaunchAndExit` 调用。临时文件不再产生。

**4. 版本与产物**
- `modVersion` 升至 `0.5.7`
- `gradlew.bat spotlessApply build` 通过
- `build/libs/jdkjarversion21enforcer-0.5.7.jar` ≈ 55 KB
- **86 个测试全部通过**（无新增测试——argfile 测试保留，但实际产品代码不再走 argfile 路径）
- README / context.md / log.md / ToDOLIST.md 同步

### 决策
- **不写命令行长度 fallback 到 argfile**：把代码简化作为优先；如果未来真有 mods 数量超过 200 个超长路径的 case 触发 32767 限制，再加 fallback 也不晚（用 `Charset.defaultCharset()` 而不是 UTF-8 写）。
- **保留 `writeArgfile` / `escapeArgfileToken` 方法**：测试还在引用它们，且这些 helper 本身没问题，未来可能还有用。
- **不深究 java.exe argfile 编码细节**：根本无解（JDK 启动器是 native code），最好的方案就是绕开它。

### 遗留 / 后续
- v0.5.7 在中文路径整合包里 e2e 验证：替换 jar → Apply & Restart Now → 期望 `Relaunch: child argv has N tokens, ~M chars total` + `Relaunch: child JVM forked (pid hint: ...)` + 子进程 `fml-client-latest.log` 出现 `Verified ... runtimeVersion().feature() = 21; ... is effective.`。
- 如果还有新错误，可能在 `sun.java.command` 解析（用 `split(" ")` 简单分词，对含空格 main args 不友好）—— 但前 156 个 entries 都正确传递的话，main args 错乱通常只导致游戏启动到一半 crash 而不是 phase3 ClassNotFoundException。

---

## 2026-04-28: v0.5.5 修复未生效——加诊断日志 + 多策略 jar 定位（v0.5.6）

### 用户反馈
v0.5.5 装上后再点 "Apply & Restart Now"，子进程 stderr **依然**报：

```text
Error occurred during initialization of VM
java.lang.Error: com.gtnewhorizons.retrofuturabootstrap.RfbSystemClassLoader
Caused by: java.lang.ClassNotFoundException: com.gtnewhorizons.retrofuturabootstrap.RfbSystemClassLoader
```

确认用户 mods/ 里只有 0.5.5（没旧版本残留），所以 v0.5.5 的 `discoverExtraClasspathEntries` 没找到 RFB jar 并 prepend 到子 -cp。

### 推测根因
v0.5.5 只用一个策略：`Class.forName(FQN).getProtectionDomain().getCodeSource().getLocation()`。RFB 的自定义 `RfbSystemClassLoader` 可能：
1. 没有 ProtectionDomain（`pd == null`）
2. 有 ProtectionDomain 但 CodeSource 是 null
3. CodeSource 有但 Location 是 null

任何一种情况 helper 都静默返回空 list（catch Throwable）→ mergeCp 还是只有原 java.class.path → 子 JVM 缺 RFB jar → ClassNotFoundException。

### 已完成

**1. 多策略 jar 定位**（`JvmRelauncher.discoverExtraClasspathEntries`）

- **策略 A**（保留）：`ProtectionDomain.getCodeSource().getLocation()`
- **策略 B**（新增）：`ClassLoader.getResource("com/foo/Bar.class")` 拿到形如 `jar:file:/.../foo.jar!/com/foo/Bar.class` 的 URL，用新加的 `parseJarPathFromResourceUrl(url, name)` 解析出 jar 路径。**不依赖 ProtectionDomain**，对自定义 ClassLoader 更鲁棒。
- **策略 C**（新增）：`harvestUrlClassLoaderChain` 扫描 context loader / system loader / `JvmRelauncher.class` 的 loader 以及它们 parent 链上所有 `URLClassLoader` 的 URLs，作 bootstrap jar 探测兜底。

A/B/C 同时运行，每个策略找到的 jar 都加到 LinkedHashSet 去重。

**2. 详细 `[relaunch-cp]` INFO 日志**

`addClassLocation` 和 `harvestUrlClassLoaderChain` 现在每一步都打日志：
- `Class.forName('FQN') OK; loader = ...`
- `CodeSource for 'FQN' -> /path/to/jar`（成功）/ `WARN '...' has null ProtectionDomain`（失败）
- `getResource('com/foo/Bar.class') -> /path/to/jar (new entry)` / `WARN unparseable URL`
- `harvested from URLClassLoader -> /path/to/jar`
- 最终 `discovered N extra entries to prepend onto child -cp:` 后跟一行行 `[i] xxx`

这样下次出问题用户直接发这段 log，能立刻定位是哪一步失败的。

**3. logger 透传**

`buildChildCommand` 加 logger 参数；`relaunchAndExit(extras, gameDir, logger)` 把 logger 传给 buildChildCommand → discoverExtraClasspathEntries → addClassLocation。原 `buildChildCommand` 五参数版本保留为不打日志的兼容入口（测试用）。

**4. 测试**
- 新增 `parseJarPathFromResourceUrlHandlesJarUrlAndLooseFileUrl`（4 个 case：jar url / file url / http / null）
- 总测试数 85 → **86**，全部通过

**5. 版本与产物**
- `modVersion` 升至 `0.5.6`
- `gradlew.bat spotlessApply build` 通过
- `build/libs/jdkjarversion21enforcer-0.5.6.jar` ≈ 55 KB
- README / context.md / log.md / ToDOLIST.md 同步

### 决策
- **三策略并联而非串联**：避免某个策略找到一个不完整路径就停了。LinkedHashSet 去重保证多次发现同一 jar 不重复加。
- **`parseJarPathFromResourceUrl` 暴露成 package-private static 方便单测**：URL 解析逻辑容易在 Windows path 上踩坑（`file:/D:/path` vs `file:///D:/path`），单独测试好。
- **不加预检失败 short-circuit**：本来想加"如果 helper 返回空且检测到 RFB 启动则拒绝 fork"，但这样用户根本看不到 Apply 按钮起作用，体验更差。仍然 fork，依赖 v0.5.4 的 fast-fail 机制兜底。
- **保留 5 参数 buildChildCommand 重载**：测试代码不需要 logger，避免被迫改一堆 mock。

### 遗留 / 后续
- 用户用 v0.5.6 复现时，**必看的诊断日志关键行**：
  - `[relaunch-cp] live java.system.class.loader = '...'` —— 确认我们读到了 RFB FQN
  - `[relaunch-cp] Class.forName('com....RfbSystemClassLoader') OK; loader = ...` —— 确认我们能加载这个类
  - `[relaunch-cp] CodeSource for '...' -> ...` 或 `WARN null ProtectionDomain` —— 确认策略 A 是否成功
  - `[relaunch-cp] getResource('com/.../X.class') -> /path/to/jar` —— 确认策略 B 是否成功（**核心**）
  - `[relaunch-cp] harvested from ... -> ...` —— 确认策略 C 找到了什么
  - `[relaunch-cp] discovered N extra entries:` —— **如果 N=0，说明三个策略都失败**，需要根本性重构（lwjgl3ify Relauncher 风格的硬编码 RFB main + 自己拼 classpath）
- 如果还失败，下一步可能要走完全重建命令行的方案。

---

## 2026-04-28: 修复 GTNH RFB 整合包下 fork 子 JVM 报 RfbSystemClassLoader ClassNotFoundException（v0.5.5）

### 用户反馈
v0.5.4 的诊断日志拿到子进程 stderr：

```text
[0.012s][warning][cds] Archived non-system classes are disabled because the java.system.class.loader property is specified (value = "com.gtnewhorizons.retrofuturabootstrap.RfbSystemClassLoader"). To use archived non-system classes, this property must not be set
Error occurred during initialization of VM
java.lang.Error: com.gtnewhorizons.retrofuturabootstrap.RfbSystemClassLoader
    at java.lang.ClassLoader.initSystemClassLoader(java.base@25.0.2/ClassLoader.java:1901)
    at java.lang.System.initPhase3(java.base@25.0.2/System.java:1986)
Caused by: java.lang.ClassNotFoundException: com.gtnewhorizons.retrofuturabootstrap.RfbSystemClassLoader
    at jdk.internal.loader.BuiltinClassLoader.loadClass(java.base@25.0.2/BuiltinClassLoader.java:580)
    ...
```

子 JVM 启动 phase3 时找不到 `RfbSystemClassLoader` 类，启动直接失败。

### 根因
GTNH RFB 整合包父 JVM 启动用 `-Djava.system.class.loader=com.gtnewhorizons.retrofuturabootstrap.RfbSystemClassLoader`。这个类只能从 `RuntimeMXBean.getInputArguments()` + `System.getProperty("java.class.path")` 重建命令行时**找到**——但 RFB 启动后**清理了** `java.class.path` 系统属性，把自己的 jar 移除了（可能是为了避免 mod 误读 RFB 内部）。我们用清理后的 classpath 重建子进程命令行，丢了关键入口。子 JVM 把 `-Djava.system.class.loader=...` 当真要去加载这个类，找不到 → JVM 启动失败。

### 已完成

**1. `JvmRelauncher.discoverExtraClasspathEntries(existingJvmArgs)`**（新增）
- 读 `System.getProperty("java.system.class.loader")` 拿到 FQN
- 同时扫 `existingJvmArgs` 里的 `-Djava.system.class.loader=<FQN>`（冗余源）
- 对每个 FQN 调 `Class.forName(...)` → `ProtectionDomain` → `CodeSource` → `Location` → `Path` → 拿到这个类**实际所在 jar 的本地路径**
- 任何异常（类不存在、CodeSource 为空、Location opaque 等）静默跳过返回 empty

**2. `JvmRelauncher.mergeClasspath(extras, existing)`**（新增）
- 用 `LinkedHashSet` 去重，按 `File.pathSeparator` join
- 新发现的 entries 优先放在前面（高优先级）

**3. `JvmRelauncher.buildChildCommand` 改造**
- Step 3（classpath）现在调 `discoverExtraClasspathEntries` + `mergeClasspath`，不再直接用 `java.class.path` 原值

**4. 测试**
- 新增 3 个：`mergeClasspathPrependsExtrasAndDeduplicates` / `discoverExtraClasspathEntriesIncludesScannedJavaSystemClassLoaderArg` / `discoverExtraClasspathEntriesIsSafeWhenClassMissing`
- 测试用 `JvmRelauncherTest.class.getName()` 当 dummy FQN 验证 CodeSource 提取，避免依赖任何外部 RFB jar
- 总测试数 82 → **85**，全部通过

**5. 版本与产物**
- `modVersion` 升至 `0.5.5`
- `gradlew.bat spotlessApply build` 通过
- `build/libs/jdkjarversion21enforcer-0.5.5.jar` ≈ 53 KB
- README / context.md / log.md / ToDOLIST.md 同步

### 决策
- **反射 CodeSource 而非硬编码 RFB FQN**：未来如果其他启动器（PCL2 / FabricMC ...）也用类似的 system class loader trick，同一份代码自动适配。
- **只补 `java.system.class.loader` 一个 FQN**：理论上还有其他被 RFB 隐藏的 jar（`forgePatches.jar`、`lwjgl3ify-relauncher.jar` 等），但子 JVM 一旦能加载 RfbSystemClassLoader，进入 RFB 的 `Main` 后会自己处理后续依赖。先做最小修复，等用户复现再扩展。
- **prepend 而非 append**：高优先级——子 JVM 先用我们补的 entry 找类，避免被父 JVM 残留的类路径污染。
- **`Class.forName` 在我们这个 mod 的运行时上下文里**应该**能找到 `RfbSystemClassLoader`（因为我们就在 RFB 加载的 mod 环境里）。如果找不到，说明用户用的不是 RFB，FQN 也不会是这个，整个 helper 自然返回空 list。

### 遗留 / 后续
- 用户用 v0.5.5 复现一次，看：
  - `fml-client-latest.log` 里 `Relaunch:     [N] -cp` 后那一行（第 N+1 行）应该出现一个 `retrofuturabootstrap*.jar` 路径（在 classpath 头部）
  - `jdkjarversion21enforcer-relaunch-stderr.log` 不再有 `RfbSystemClassLoader` 错误
  - 子进程 fml-client-latest.log 出现 `Verified ... runtimeVersion().feature() = 21; ... is effective.`
- 如果还崩，根据新的 stderr 报哪个类没找到，扩展 `discoverExtraClasspathEntries` 或者直接走 lwjgl3ify Relauncher 风格的"完全重建命令行"。

---

## 2026-04-28: fork 失败诊断 + fast-fail 回退（v0.5.4）

### 用户反馈
- v0.5.3 装好后在 GTNH 官方 RFB 整合包里点 "Apply & Restart Now"，**启动器报"游戏崩溃"**，没进入游戏。

### 诊断
对照 `@d:\Code\GTNH LIB\lwjgl3ify-master\src\main\java\me\eigenraven\lwjgl3ify\relauncher\Relauncher.java:169-289` 和我们的 `JvmRelauncher.relaunchAndExit`：

- lwjgl3ify Relauncher **完全重新构建**命令行（main = `com.gtnewhorizons.retrofuturabootstrap.MainStartOnFirstThread`，自己拼 classpath），且 lwjgl3ify Relauncher **本身就是启动器看见的"游戏进程"**——它 fork 子游戏后等待 + `runtimeExit`，启动器看到一个连续 session。
- 我们的 JvmRelauncher 重用 `RuntimeMXBean.getInputArguments()` + `sun.java.command`——理论上和原始命令一样，但**我们是 LaunchWrapper Tweaker 阶段**，启动器把 LaunchWrapper 进程当作游戏。我们 `Runtime.halt` 后启动器以为游戏崩了，立即收尾。
- 用户问诊确认：选 "启动器双游戏退出提示 / crash 对话框"——确实是父进程 halt → 启动器报崩溃。
- 子进程崩溃（如果有的话）的 stderr 被 `inheritIO` 接到父进程标准流，但启动器不一定捕获/显示，**用户看不到具体崩溃原因**。

### 已完成

**1. `JvmRelauncher.relaunchAndExit` 签名重构**
- 旧签名：`relaunchAndExit(List<String> extraJvmArgs)` 返回 void
- 新签名：`relaunchAndExit(List<String> extraJvmArgs, Path gameDir, Logger logger)` 返回 `OptionalInt`
  - `OptionalInt.empty()` = 成功（实际不返回，因为 `System.exit` 已经终止）
  - `OptionalInt.of(exitCode)` = fast-fail（子进程 5 秒内非零退出），调用方应保留父进程

**2. 诊断日志**
- fork 前打印完整命令行：`Relaunch: forking child JVM. Full command:` 后跟 `[i] xxx` 一行一个 token
- fork 后打印子进程 PID 和重定向路径：`Relaunch: child JVM forked (pid hint: ...). stdout -> ..., stderr -> ...`
- 子进程退出后打印 exit code 和耗时

**3. stdout/stderr 重定向到独立日志**
- 之前：`pb.inheritIO()`，子进程崩溃信息掉到不可见的地方
- 现在：`pb.redirectOutput(<gameDir>/logs/jdkjarversion21enforcer-relaunch-stdout.log)` + `pb.redirectError(<gameDir>/logs/jdkjarversion21enforcer-relaunch-stderr.log)`，**永久保留**便于复盘

**4. fast-fail 检测**
- 子进程 < 5 秒非零退出 → 视为 fork 失败 → 返回 `OptionalInt.of(exitCode)` 给调用方
- `RelaunchService.attemptJvmRelaunch` 收到 fast-fail → ERROR log + 调起 `ManualLauncherInstructionsPopup` + 返回 `false`
- 调用方（`RelaunchService.runClientFlow`）收到 `false` → 不退出父进程 → LaunchWrapper 继续启动游戏 → **用户至少能进游戏**（jar 压制虽然没生效，但比启动器报崩溃强）

**5. `Runtime.halt` → `System.exit`**
- 让 shutdown hooks 跑完，少一类竞态

**6. Java 8 source level 兼容**
- `Path.of(String)` → `Paths.get(String)`（Java 11 vs 8）
- `Process#pid()` 用反射调（Java 9+，运行时 Java 22+ 一定有），失败 fallback "?"
- 项目用 Jabel 让 source level 1.8 但 byte code Java 22+，所以 source 必须用 Java 8 兼容 API

**7. 版本与产物**
- `modVersion` 升至 `0.5.4`
- `gradlew.bat spotlessApply build` 通过
- `build/libs/jdkjarversion21enforcer-0.5.4.jar` ≈ 52 KB
- **82 个测试全部通过**（无新增/无失效）
- README / context.md / log.md 同步

### 决策
- **不重构成 lwjgl3ify 风格的命令行重建**（自己拼 RFB main + classpath）：风险高、改动大、不一定能解决根本问题。先做诊断 + fast-fail 回退收集数据，等用户提供 stderr 后再决定是否重构。
- **fast-fail 阈值 5 秒**：典型子 JVM 启动到 LaunchWrapper inject 阶段 ≥ 1 秒；启动失败的情况通常 < 1 秒就退出。5 秒留足余量。
- **`redirectOutput/Error` 而非 `inheritIO`**：放弃了"子进程输出实时显示在启动器 console"的能力，但换来了**永久日志**——对诊断更重要。后续如果用户需要实时输出，可以加配置开关。
- **Java 8 source level 限制**：踩了两次坑（`Path.of` / `Process#pid`）；以后写新代码先确认 Java 8 兼容性，必要时用反射。

### 遗留 / 后续
- 等用户用 v0.5.4 复现一次，发回 fml-client-latest.log + jdkjarversion21enforcer-relaunch-{stdout,stderr}.log，根据真实数据决定下一步。可能的根因：
  1. `sun.java.command` 在 RFB 模式下不是合法 main（重跑会立即崩）
  2. classpath 包含 LaunchWrapper 已经修改的内容，子进程读不到原版
  3. RFB 启动用了某种 file lock 防多开，子进程被拒
  4. JVM args 含某些只能传一次的参数
- 如果根因属于上面，可能需要走 lwjgl3ify Relauncher 风格的"完全重建命令行"。
- 长期：考虑把 `relaunchAndExit` 改成"父进程不 fork 而是阻塞主线程 + 重新初始化"，但这非常 hacky。

---

## 2026-04-28: GUI 主对话框文案重写 + 自动创建 lwjgl3ify config 骨架 + 兜底提示弹窗拆分（v0.5.3）

### 用户反馈
- 「我让你和 lwjgl3ify 模组一样，在 GUI 中就可以设置 JVM 参数，而不是让你提醒用户重启在启动器设置啊，但是这个提示窗口也要保留，在未设置成功提示这个弹窗。」
- 关键诊断：v0.5.2 主对话框文案 "Forge is too late to set this from inside Minecraft, and lwjgl3ify was not detected, so this dialog is your one-shot choice" 让用户以为 mod 处理不了、要他自己去启动器手动加 `-D`。其实 "Restart Now" 按钮**已经**做了 fork 子 JVM 的事，但用户没看出来——这是文案问题，不是逻辑问题。

### 已完成

**1. `Lwjgl3ifyConfigPatcher` 增强**
- 新增 `Result.CREATED` 枚举值。
- 新增 `patchOrCreate(Path gameDir)` / `patchOrCreateFile(Path)`：当 lwjgl3ify-relauncher.json 不存在时，主动创建一份最小骨架 `{"customOptions":["-Djdk.util.jar.version=21"]}`。这样：
  - 装了 lwjgl3ify 的非 RFB 用户：下次启动 lwjgl3ify Relauncher 直接读到 → 自动生效。
  - RFB 整合包用户：文件不被读，但创建无副作用；如果用户后来切到非 RFB 启动也能受益。
- 旧 `patchIfNeeded` 保留（向后兼容）。

**2. `RelaunchPromptDialog` 重写文案**
- 标题：从 "Restart required" 改为 "Configure JVM startup arguments"
- 图标：从 WARNING 改为 QUESTION
- 按钮：从 "Restart Now (recommended)" 改为 **"Apply & Restart Now (recommended)"**
- 文案从"出问题了请你修"变为"我帮你做了 / 会做，请确认"。新增 `PatcherStatus` 参数（CREATED / APPLIED / ALREADY_PRESENT / NO_CONFIG_OR_ERROR），让对话框上半部分根据 patcher 实际发生了什么定制说明。

**3. 新增 `ManualLauncherInstructionsPopup`**（兜底提示弹窗）
- 非阻塞 INFO 弹窗（`SwingUtilities.invokeLater`）。
- 内容：告诉用户怎么手动加 `-Djdk.util.jar.version=21` 或 `-javaagent:mods/<jar>` 到 PrismLauncher / MultiMC / HMCL / PCL2 / BakaXL / vanilla launcher。
- 仅在【**未设置成功**】路径上调用：
  - 用户选 "Skip this time"
  - 用户选 "Don't ask again"
  - `prelaunch_relaunch_prompt=false` 关掉了主对话框
  - `prelaunch_relaunch_suppressed=true` 已经永久抑制
  - fork 子 JVM 意外返回（`Runtime.halt` 失败）
- headless 环境下 no-op。

**4. `RelaunchService.runClientFlow` 流程改造**
- patcher 用新的 `patchOrCreate`，**不再**因为 APPLIED / ALREADY_PRESENT / NO_CONFIG / ERROR 提前 return。
- 把 patcher 结果转换为 `PatcherStatus`，继续走对话框分支。这样 lwjgl3ify config 已经写好/已经存在 时，用户**仍然**能看到主对话框 + Apply & Restart Now 让本次也生效。
- 在 Skip / SUPPRESS_FOREVER / DIALOG_DISABLED / DIALOG_SUPPRESSED / DIALOG_RESTART_RETURNED 路径上调用 `ManualLauncherInstructionsPopup.show(...)` 作为兜底。

**5. 测试更新**
- `RelaunchServiceTest`：旧的 `lwjgl3ifyConfigGetsPatched` / `lwjgl3ifyConfigAlreadyPresent` / `noLwjgl3ifyAndHeadlessYieldsHeadlessOutcome` 改名 + 调整断言为 `DIALOG_HEADLESS`（headless 测试 JVM 下流程到对话框分支 short-circuit 的预期 outcome），同时断言文件**确实被写**。
- 新增 `missingLwjgl3ifyConfigGetsCreated`：验证文件不存在时 patcher 创建骨架 + 流程到对话框分支。
- `RelaunchPromptDialogTest`：适配新签名（多了 `PatcherStatus`），新增 `buildMessageReflectsPatcherStatusVariants` 验证四种 PatcherStatus 都有相应文案。
- 新增 `ManualLauncherInstructionsPopupTest`：3 个测试覆盖文案 + headless 行为 + 含/不含 jar 名时的不同输出。
- `Lwjgl3ifyConfigPatcherTest`：新增两个测试覆盖 `patchOrCreate` 的两个分支（创建骨架 / 走 patch 已有文件）。
- 总测试数 76 → **82**，全部通过。

**6. 版本与产物**
- `modVersion` 升至 `0.5.3`。
- `gradlew.bat spotlessApply build` 通过。
- `build/libs/jdkjarversion21enforcer-0.5.3.jar` ≈ 50 KB。
- 文档同步：README、context.md、log.md、ToDOLIST.md。

### 决策
- **patcher 不再提前 return** 是关键设计变化：之前的设计假设"写好 config 文件 = 用户问题已解决"，但实际**这次启动**仍然没有 `-D`，必须 fork 才能让本次也生效。新设计统一这两件事：写文件（持久化下次）+ 弹对话框 fork（本次立即生效）。
- **保留 `RestartPopup` / `noLwjgl3ifyAndHeadlessYieldsHeadlessOutcome`-类测试** 而不是删除：测试体系完整覆盖各路径，`RestartPopup` 旧路径仍然存在（被服务端脚本 patcher 等用），不删。
- **创建骨架 vs 不创建**：选择主动创建——下次非 RFB 启动可以自动受益，且无副作用。
- **兜底弹窗用非阻塞 `invokeLater`** 而不是模态：用户已经选了 Skip 或被 suppress，他想的就是"让游戏继续启动"，再阻塞他不友好。INFO 弹窗给他 takeaway，他自己关。

### 遗留 / 后续
- 真实 GTNH 官方 RFB 整合包 e2e：第一次启动应该看到主对话框（标题 "Configure JVM startup arguments"），点 "Apply & Restart Now" 后子进程 `JarFile.runtimeVersion().feature() == 21` 进入游戏。
- 验证兜底弹窗的视觉：装好 jar 后选 Skip，应该看到 "Manual configuration reminder" 弹窗内容里有 PrismLauncher / HMCL 等说明。
- 下个版本可能加：把 JVM args 文本框（让用户**编辑** args 列表，类似 lwjgl3ify SettingsDialog）真的做出来，不只是显示一个固定的 `-D`。当前固定值已经覆盖 100% 的诉求，文本框是 nice-to-have。

---

## 2026-04-28: 反转 v0.5.1 RFB 静默策略——GTNH 官方整合包实测不传 -D，必须介入（v0.5.2）

### 用户反馈
- v0.5.1 在 GTNH 官方 Java 17–25 整合包里**功能没有实现**，游戏直接启动，但 `JarFile.runtimeVersion().feature()` 仍然不是 21。
- 用户明确要求：「我们这个模组不要跟 lwjgl3ify 的静默行为走，一定要弹出 GUI 并配置好后再启动游戏」。

### 真相回顾（v0.5.1 的错误假设）
v0.5.1 假定：「GTNH 官方 RFB 整合包既然给 lwjgl3ify Tweaker 设了 `lwjgl3ify:rfb-booted=true`，那 RFB 应该已经接管了所有 JVM args，包括 `-Djdk.util.jar.version=21`」。

**实测打脸**：RFB 整合包**不会**自动传 `-Djdk.util.jar.version=21`。`lwjgl3ify:rfb-booted=true` 只是告诉 lwjgl3ify Tweaker「不要再 fork 子进程了」，跟 jar 多版本压制毫无关系。所以"无感启动"对的是 lwjgl3ify GUI，不是我们关心的 jar 压制。

### 已完成
- **删掉 `Outcome.SKIPPED_RFB_BOOTED` 枚举值**和 `RelaunchService.runClientFlow` 里的 RFB 短路：
  - 现在 `runClientFlow` 头部只剩三个短路：Java ≤ 21、`isRelaunchedChild()`、`SKIPPED_PROPERTY_ALREADY_SET`（这个是 100% 正确的强信号，保留）。
  - RFB 模式下检测到 `lwjgl3ify:rfb-booted=true` 时**只打一行 INFO 注解**，告诉用户当前是 RFB 环境，但**继续走完整流程**——必要时弹 GUI、fork 子 JVM。
- **保留 `RelaunchService.isRfbBooted()` 工具方法**：v0.5.2 仅作为 INFO 日志注解用（让用户在排查时知道环境是 RFB），不再决定流程。未来可能在其他诊断/日志路径继续有用。
- **测试调整**：把 `rfbBootedShortCircuits` 改名为 `rfbBootedDoesNotShortCircuitInV052`，断言 RFB 启动 + 没 lwjgl3ify config + headless 测试环境下 outcome 是 `DIALOG_HEADLESS`（说明流程跑到了对话框分支，没被 RFB 短路截断）。`propertyAlreadyOnCommandLineShortCircuits` 保持不变。
- `modVersion` 升至 `0.5.2`，`gradlew.bat spotlessApply build` 通过，**76 个测试全过**。
- 文档同步：README、context.md、log.md、ToDOLIST.md 全部反转 v0.5.1 关于 RFB 的描述。

### 决策
- **完全删除 `SKIPPED_RFB_BOOTED` 枚举值**而不是仅保留枚举但不再触发：mod 还在早期，没有外部消费者，删干净避免误用。
- **保留 `isRfbBooted()` 工具方法**：未来可能用在 INFO 日志、诊断、或者别的路径上。代码不到 20 行，留着无害。
- **不再用任何"启动器层契约"判断"是否要管"**：v0.5.1 的教训是 lwjgl3ify Tweaker 的契约（rfb-booted）只对 lwjgl3ify 自己有效，套到我们身上就错。我们只信硬证据：`System.getProperty("jdk.util.jar.version") == "21"` 是唯一可信的"已经搞定了"的信号。
- **接受多余 fork 的代价**：RFB 整合包用户每次启动都会被弹一次对话框、点 Restart Now 后 fork 一个子 JVM——多一次启动开销。但这是用户明确要求的"配置好后再启动"，正确性优于无感。用户首次选「Don't ask again」后会写入 `prelaunch_relaunch_suppressed=true`，不再弹。

### 遗留 / 后续
- 真实 GTNH 官方 Java 17–25 整合包 e2e 验证：放 jar → 第一次启动应该看到 `[tweaker] Detected RFB-booted environment ... Continuing with the full enforcement flow ...` + 模态对话框 → 选 Restart Now → 子进程 `JarFile.runtimeVersion().feature() == 21`。
- 考虑给对话框加个"复制 JVM 参数到剪贴板"按钮，让 RFB 整合包用户可以选择把 `-D` 加到启动器层而不是每次都 fork（如果 RFB 让加的话）。

---

## 2026-04-28: 修正 v0.5.0 在 GTNH 官方 RFB 整合包里的烦人弹窗（v0.5.1）

### 用户问题
- 「在 GTNH 官方 Java17-25 整合包里 lwjgl3ify 是无感启动的，根本不弹 GUI。你之前的判断错了——不是用户没装 lwjgl3ify，而是整合包就是无感的。」 —— 完全正确。我 v0.5.0 的设计基于错误前提：把"看不到 lwjgl3ify GUI"等同于"没装 lwjgl3ify"，会在 RFB 整合包里冒出一个不该出现的对话框。

### 真相回顾
看 `Lwjgl3ifyRelauncherTweaker.acceptOptions`：

```java
if (Launch.blackboard.get("lwjgl3ify:rfb-booted") != Boolean.TRUE) {
    new Relauncher(...).run();  // 只有非 RFB 模式才弹 GUI
}
```

GTNH 官方 Java 17–25 整合包用 **RFB（Reborn Forge Bootstrap）** 启动器，启动时在 LaunchWrapper 黑板上设了 `lwjgl3ify:rfb-booted=true`。RFB 接管所有 JVM args（包括 `-Djdk.util.jar.version=21`），lwjgl3ify Tweaker 看到这个标志直接 no-op——这就是用户感受到的"无感"。我们应该遵守同样的契约。

### 已完成
- `RelaunchService` 加两个静态短路（在 `runClientFlow` 头部，`SKIPPED_RELAUNCHED_CHILD` 之后）：
  1. **`SKIPPED_PROPERTY_ALREADY_SET`**：`System.getProperty("jdk.util.jar.version") == "21"` 时直接 INFO + return。说明启动器命令行就传了 -D，结果保证生效。
  2. **`SKIPPED_RFB_BOOTED`**：`isRfbBooted()` 反射读 `Launch.blackboard["lwjgl3ify:rfb-booted"]`，为 `Boolean.TRUE` 时直接 INFO + return。
- `RelaunchService.isRfbBooted()`：反射调用，避免硬依赖 `net.minecraft.launchwrapper.Launch`；任何异常映射为 `false`，让单测、独立 Java Agent 用法等没有 LaunchWrapper 的环境继续走原路径。
- 新增单元测试：`propertyAlreadyOnCommandLineShortCircuits`、`rfbBootedShortCircuits`（后者通过反射往 `Launch.blackboard` 注入 `lwjgl3ify:rfb-booted=true` 模拟 RFB；测试 classpath 没有 LaunchWrapper 时降级为 "确保不抛"）。完整 `gradlew.bat spotlessApply build` 通过，**76 个测试全部通过**（v0.5.0 是 74，新增 2）。
- `modVersion` 升级到 `0.5.1`。
- 文档同步：`README.md`（"未装 lwjgl3ify"→"独立启动器 + 无 lwjgl3ify config"，新增 RFB 检测说明、安装小节加 GTNH 官方包路径）、`context.md`、`log.md`、`ToDOLIST.md`。

### 决策
- **打 INFO 不打 WARN**：用户在 GTNH 官方包里看到 WARN 会以为 mod 出错了。INFO 既留排查痕迹，又不让用户焦虑。
- **不试图把 GTNH 官方包"也修一下"**：哪怕 RFB 没传 `-D`，弹我们自己的对话框去打扰一个标榜"无感"的整合包都是 regression。如果 GTNH 官方包真有 bug，应该上游报告。这个抑制是有意的。
- **`isRfbBooted()` 用反射而不是直接 import `Launch`**：Tweaker 路径 import `Launch` 没问题（`JdkJarVersion21Tweaker` 已经 import 了），但 `RelaunchService` 设计成"任何环境都能跑"的纯模块（也被 preInit 调用、单测、未来可能的 Java Agent 探测路径调用），用反射避免给它绑死 LaunchWrapper 依赖。
- **检测顺序**：`SKIPPED_PROPERTY_ALREADY_SET` 在 `SKIPPED_RFB_BOOTED` 之前。即使 RFB 没传 `-D`（理论上不应该发生），`PROPERTY_ALREADY_SET` 也会优先短路。这俩谁先谁后实际不影响行为，但 PROPERTY 是更强的信号（命令行传 -D = 100% 生效），先查。

### 遗留 / 后续
- 真实 GTNH 官方 Java 17–25 整合包 e2e 验证：放 jar → 启动 → log 应该出现 `[tweaker] -Djdk.util.jar.version=21 was already supplied on the JVM command line; skipping all client-side fallback.` 或 `[tweaker] Detected RFB-booted environment ...; skipping all client-side fallback.` 任意一条 → 没有任何对话框 → 游戏正常进入。
- 验证 RFB 模式 + RFB 没传 `-D` 的边角情况：当前选 INFO 静默；若用户反馈这种边角下应该 WARN，需要后续调整。

---

## 2026-04-28: 升级为 LaunchWrapper Tweaker，弹窗 / fork 提前到 Forge mod 加载之前（v0.5.0）

### 用户问题
- 「为什么 lwjgl3ify 放到 mods 文件夹中也能加载 GUI？」 —— 因为它的 jar manifest 里写了 `TweakClass: ...`，FML CoreModManager 扫 `mods/*.jar` 时会把它注册为 LaunchWrapper Tweaker，inject 阶段早于 Forge `preInit`。
- 「那为什么我们不照做？反正 v0.4.0 那些 fancy 优势对最终用户没用」—— 用户决定要做。本次完成。

### 已完成
- 新增 `com.andgatech.jdkjarversion21enforcer.tweaker.JdkJarVersion21Tweaker implements ITweaker`：
  - `acceptOptions(args, gameDir, assetsDir, profile)`：保存 `gameDir` 给后续使用。
  - `injectIntoClassLoader(LaunchClassLoader)`：调 `RelaunchService.runClientFlow(gameDirPath, configDirPath, Phase.TWEAKER, LOG)`。任何 `Throwable` 被吞掉，保证 LaunchWrapper 不被搞炸。`finally` 调 `RelaunchService.markHandled()`，让稍后的 `preInit` 不重复弹窗 / 写文件。
  - `getLaunchTarget()=null` / `getLaunchArguments()=[]`：声明我们不是主 Tweaker，不接管启动目标。
  - 服务端检测：`FMLLaunchHandler.side().isServer()` 为真时直接 return，所有动作下放到 preInit（服务端没 GUI、需要 `gameDir/config/` 写入并行启动脚本，preInit 路径更合适）。
- 新增 `com.andgatech.jdkjarversion21enforcer.relaunch.RelaunchService`：
  - 把原 `CommonProxy.runLwjgl3ifyPatcher` + `handleNoLwjgl3ifyFallback` + `attemptJvmRelaunch` 三个 private 方法合并、抽象成一个无状态静态方法 `runClientFlow(Path gameDir, Path configDir, Phase phase, Logger log)`，返回 `Outcome` 枚举（10 种），便于单测。
  - 入口 system property guard：`jdkjarversion21enforcer.client.handled`。`markHandled()` 设 `true`；`isAlreadyHandled()` 读取。Tweaker 处理完一次后，preInit 检测此标志直接跳过自己的客户端 fallback 路径。
  - Tweaker 与 preInit 走完全相同的逻辑流程，差别仅在 log 前缀（`[tweaker]` / `[pre_init]`）和触发时机。
- `build.gradle`：jar manifest 加 `TweakClass: com.andgatech.jdkjarversion21enforcer.tweaker.JdkJarVersion21Tweaker`，与已有的 `FMLCorePlugin` / `FMLCorePluginContainsFMLMod` / `Premain-Class` / `Agent-Class` 并列。**同一份 jar 现在同时是 mod / CoreMod / Java Agent / LaunchWrapper Tweaker 四合一**。
- `Config.save` 从 package-private 改为 public（被新包 `relaunch` 调用）。其他 API 不变。
- `CommonProxy.preInit`：移除 `runLwjgl3ifyPatcher` / `handleNoLwjgl3ifyFallback` / `attemptJvmRelaunch` / `Lwjgl3ifyConfigPatcher` / `JvmRelauncher` / `RelaunchPromptDialog` / `RestartPopup` 的直接 import 和方法调用，改成 `RelaunchService.runClientFlow(...)` + `RelaunchService.markHandled()`。`RelaunchService.isAlreadyHandled()` 检测在前，已处理则 INFO 跳过。服务端走 `runServerScriptPatcher` 不变。
- 新增单元测试：
  - `RelaunchServiceTest`（8）：`isAlreadyHandled` / `markHandled`、Java 21 短路、`isRelaunchedChild` 短路、lwjgl3ify config 实际 patch、已 patch 跳过、headless 走 `DIALOG_HEADLESS`、`prelaunch_relaunch_prompt=false` 走 `DIALOG_DISABLED`、`prelaunch_relaunch_suppressed=true` 走 `DIALOG_SUPPRESSED`。每个测试 `@BeforeEach` / `@AfterEach` 快照并恢复 `java.specification.version` / `RELAUNCH_GUARD_PROPERTY` / `HANDLED_PROPERTY`，互不干扰。
  - `JdkJarVersion21TweakerTest`（5）：构造、`getLaunchTarget` 返回 null、`getLaunchArguments` 空、`acceptOptions` 不抛、`injectIntoClassLoader(null)` 不抛 + 必设 handled 标志。
- 完整 `gradlew.bat spotlessApply build` 通过，**74 个测试全部通过**（v0.4.0 是 61 个，新增 13 个）。
- `modVersion` 升级到 `0.5.0`。
- 文档同步：`README.md` / `context.md` / `log.md` / `ToDOLIST.md`。

### 决策
- **完全复用 v0.4.0 的 Swing 对话框 / `JvmRelauncher` 实现**：用户 v0.4.0 已经看过对话框文案，不必为提前到 Tweaker 阶段而重写。
- **Tweaker 与 preInit 共享同一服务（`RelaunchService`）而不是把 preInit 的代码 inline 复制到 Tweaker**：避免两份代码漂移；便于单测；同时让 preInit 仍能作为兜底（开发环境 / 老版本启动器 / TweakClass 被禁用等情况下）。
- **Tweaker `injectIntoClassLoader` 严禁调 `JarFile.runtimeVersion()`**：那会触发 `JarFile.<clinit>` 锁死 `RUNTIME_VERSION`。Tweaker 阶段我们直接干活（patch lwjgl3ify config / 弹对话框 / fork JVM），不做自检。自检留给 preInit（那时 `JarFile` 反正已经被 FML 触发过，调它无副作用）。
- **`RelaunchService.markHandled()` 用 system property 而不是静态字段**：system property 在 fork 出来的子 JVM 不会被继承（除非显式 `-D` 传过去），保证子 JVM 进入 preInit 时也能干净判断"我是子进程，不再处理"——但因为 fork 时已加 `-D` 让 `JarFile` 直接生效，preInit 自检 EFFECTIVE → 直接 return。两条路径都走得通。
- **服务端 Tweaker no-op**：服务端启动通常没有 GUI，且 `ServerStartScriptPatcher` 需要 `event.getModConfigurationDirectory()` 这种 preInit 才有的入口。把服务端动作留在 preInit，Tweaker 只管客户端。
- **manifest 多入口共存**：`Premain-Class` / `Agent-Class` / `FMLCorePlugin` / `TweakClass` 全留着。`-javaagent:` 与 Tweaker 不冲突（`-javaagent:` 走 premain，`TweakClass` 走 LaunchWrapper inject 链）；CoreMod 和 Tweaker 同存时 FML 会双重处理，但我们的 CoreMod 类没注册任何 transformer，`enforce()` 是幂等的，重复调用零代价。

### 触发链路对比（v0.4.0 → v0.5.0）
| 场景 | v0.4.0 触发阶段 | v0.5.0 触发阶段 | 用户感受到的 "重启次数" |
|------|---------------|---------------|----------------------|
| 装 lwjgl3ify | preInit 写 customOptions | **Tweaker** 写 customOptions（早 ~1s） | 1 次（不变） |
| 未装 lwjgl3ify | preInit 弹对话框 + fork | **Tweaker** 弹对话框 + fork（早 ~1s） | 1 次（不变，但中断时机更早，丢的状态更少） |
| `-javaagent:` | premain 直接生效 | premain 直接生效 | 0 次（不变） |
| 服务端 | preInit 写脚本 | preInit 写脚本（不变） | 0 次（不变） |

---

## 2026-04-28: 客户端无 lwjgl3ify 时的预启动 GUI + JVM 自重启（v0.4.0）

### 用户问题
- 「客户端没装 lwjgl3ify 怎么办？lwjgl3ify 自己有个预启动 GUI，能不能照着做一个？」 —— 已实现：在 `preInit` 检测 `Lwjgl3ifyConfigPatcher.Result.NO_CONFIG` 时弹模态 Swing 对话框，让用户选「立即重启 / 跳过 / 不再询问」。
- 「怎么知道 `-Djdk.util.jar.version=21` 真的生效了？」 —— 答：`CommonProxy.preInit` 已经反射调 `JarFile.runtimeVersion().feature()`，把结果按 `EFFECTIVE` / `INEFFECTIVE` / `UNKNOWN` 三类输出 INFO 或 WARN；用户也可以手动跑探针程序验证。

### 已完成
- 新增 `com.andgatech.jdkjarversion21enforcer.relaunch.JvmRelauncher`：
  - `relaunchAndExit(extraJvmArgs)`：从 `RuntimeMXBean.getInputArguments()` + `sun.java.command` 重建当前 JVM 命令行，去重同名 `-Dxxx`，追加 `extraJvmArgs`（含 `-Djdkjarversion21enforcer.relaunched=true`）；用 `@argfile` 把所有 JVM args + 主类/jar + 程序参数写到临时文件，规避 Windows 8KB 命令行长度上限；`ProcessBuilder.inheritIO()` 让子进程接管 stdin/stdout/stderr；`process.waitFor()` 后 `Runtime.getRuntime().halt(exitCode)` 强制结束父进程，避免 Forge shutdown hook 与子进程文件锁互相干扰。
  - `isRelaunchedChild()`：检查 `-Djdkjarversion21enforcer.relaunched=true`，判定当前 JVM 是 fork 出来的子进程；用于防止"父进程 fork 子进程，子进程发现还是没生效，又 fork 一次"的死循环。
  - 工具方法 `relaunchGuardArg()`、`buildJavaExecutablePath()`、`writeArgFile()`、`escapeForArgFile()`、`shouldKeepInputArg()` 全部 package-private，方便单测覆盖。
- 新增 `com.andgatech.jdkjarversion21enforcer.ui.RelaunchPromptDialog`：
  - `askUserModally(...)`：headless 直接返回 `Outcome.SKIP_ONCE`；否则 EDT 上调 `JOptionPane.showOptionDialog` 弹三按钮模态框（"Restart Now" / "Skip this time" / "Don't ask again"），默认按钮是 Restart Now。文案展示 Java 版本、目标 `-D` 参数、配置文件名和键名。
  - 任何 AWT 异常都被 `try/catch Throwable` 吞掉，回退为 `SKIP_ONCE`，保证主流程不被弹窗炸掉。
- `Config` 增加两个键：`prelaunch_relaunch_prompt`（默认 `true`）和 `prelaunch_relaunch_suppressed`（默认 `false`），`save()` 模板更新带详细注释。
- `CommonProxy.runLwjgl3ifyPatcher` 在 `Result.NO_CONFIG` 分支调用 `handleNoLwjgl3ifyFallback(configDir, cfg)`：
  - `JvmRelauncher.isRelaunchedChild()` → WARN 后放弃，避免无限 relaunch。
  - `prelaunchRelaunchSuppressed` / `!prelaunchRelaunchPrompt` → INFO 跳过对话框。
  - `!RestartPopup.canShow()`（headless）→ INFO 跳过。
  - 否则弹 `RelaunchPromptDialog`：`RESTART_NOW` 走 `attemptJvmRelaunch`；`SUPPRESS_FOREVER` 把 `prelaunch_relaunch_suppressed=true` 写回 `config/jdkjarversion21enforcer.cfg`；`SKIP_ONCE` WARN 后继续启动。
- 新增单元测试：`JvmRelauncherTest`（命令行重建、`@argfile` 转义、relaunch guard 检测）、`RelaunchPromptDialogTest`（headless 下确定 `SKIP_ONCE`、null 输入容忍）、`ConfigTest` 加新两个键的默认与覆写断言。完整 `gradlew.bat spotlessApply build` 通过，**61 个测试全部通过**。
- `modVersion` 升级到 `0.4.0`，README 增加「客户端没装 lwjgl3ify」一节、把「触发条件汇总」表加上"无 lwjgl3ify"行、`context.md` / `ToDOLIST.md` 同步。

### 决策
- **fork 一个全新 JVM 而不是动态注入 agent 到当前 JVM**：`Instrumentation` 的运行时附着 (`VirtualMachine.attach`) 在 LaunchWrapper 已经初始化过 `JarFile` 之后才能调用，依然来不及。fork 才是唯一干净的解决方案，且和 lwjgl3ify 的做法一致。
- **`Runtime.halt` 而不是 `System.exit`**：`System.exit` 会跑 Forge / LaunchWrapper 注册的 shutdown hooks，可能尝试写入资源、刷新日志，与子进程争抢同一份 launcher 工作目录；`halt` 直接走人。
- **用 `@argfile` 而不是 `cmd /c "very long string"`**：Windows `CreateProcess` 的命令行 8KB 上限会被 GTNH 完整 classpath 直接撑爆。`@argfile` 是 JDK 官方支持的写法（Java 9+），Linux/macOS 也兼容。
- **三按钮命名 / 默认按钮 = Restart Now**：和 lwjgl3ify Settings 对话框对齐，但增加「Don't ask again」满足"不喜欢被打扰"的 power user。`SUPPRESS_FOREVER` 把开关持久化到模组自己的配置，不污染 lwjgl3ify config（毕竟用户根本没装它）。
- **`isRelaunchedChild()` 检查放在最前**：先于配置开关、headless 等判断；只要是子进程就直接放弃 relaunch，否则一旦 fork 后子进程依然无法生效（例如用户的 launcher 把 `-D` 参数过滤掉），就会无限 fork。这是给"奇葩 launcher"留的安全网。
- **`SKIP_ONCE` 不持久化**：用户这次想跳过、下次再决定是常态；只有显式选「不再询问」才永久关闭。

### 验证 `-Djdk.util.jar.version=21` 是否真正生效
1. 看 `preInit` 日志：
  - `Verified java.util.jar.JarFile.runtimeVersion().feature() = 21; jdk.util.jar.version=21 is effective.` → 成功。
  - `java.util.jar.JarFile.runtimeVersion().feature() is 22 but 21 was requested. The system property was set too late...` → 失败，按指引加 `-javaagent` 或 `-D`。
2. 手动验证：用同一个 JDK 跑一个最小探针程序 `System.out.println(java.util.jar.JarFile.runtimeVersion().feature());`，加上 `-javaagent:.../jdkjarversion21enforcer-0.4.0.jar`，看输出是否为 `21`。
3. lwjgl3ify 用户可以在 lwjgl3ify GUI 的 "Custom JVM args" 列表里直接看到 `-Djdk.util.jar.version=21`（patcher 已自动追加）。

---

## 2026-04-28: 补丁完成后弹「请重启」Swing 对话框（v0.3.0 续）

### 用户问题
- 「Java 版本检查后执行这些功能还在吗？」—— 答：Java ≤ 21 时 `preInit` 第一行就 `return`，所有 patcher / 弹窗都跳过；Java 22+ 且自检 `EFFECTIVE` 时也跳过；只在 Java 22+ 且未生效时才跑。这个 gating 是有意设计：Java 21 上写 lwjgl3ify config 没意义，已生效的环境再 patch 只会让 config 抖动。
- 「还要加上第一次启动注入完成后提示弹窗，提醒用户重启」—— 已实现。

### 已完成
- 新增 `com.andgatech.jdkjarversion21enforcer.ui.RestartPopup`：通过 `GraphicsEnvironment.isHeadless()` + `Toolkit.getDefaultToolkit()` 双重判断是否能弹窗；用 `SwingUtilities.invokeLater` 异步触发，不阻塞 `preInit`；任何 AWT 异常（包括 EDT 启动失败、L&F 设置失败、DialogClass 找不到等）都用 `try-catch Throwable` 吞掉。
- `Config` 增加 `show_restart_popup_after_patch` 开关（默认 `true`），与已有两个开关一起持久化、注释模板更新。
- `CommonProxy.runLwjgl3ifyPatcher` 在 `Result.APPLIED` 分支调用 `RestartPopup.showRestartReminder(...)`，提示用户「lwjgl3ify customOptions 已更新，重启游戏后下次启动生效」。
- `CommonProxy.runServerScriptPatcher` 在 `Result.PATCHED` 分支同样调用，提示「停服并用并行脚本启动」。dedicated server 通常 headless，弹窗自动 no-op；带桌面的服务器仍会弹。
- `build.gradle` 的 test 任务追加 `systemProperty 'java.awt.headless', 'true'`，保证 test JVM 一开始就被锁成 headless（`GraphicsEnvironment` 一旦初始化 headless 状态就被缓存，必须在 JVM 启动时设属性）。
- 新增 `RestartPopupTest`（3 个测试）：headless 下 `canShow()` 返回 `false`、`showRestartReminder` 不抛、null/empty 参数容忍。`ConfigTest` 增加 `show_restart_popup_after_patch` 默认与覆写两个分支断言。
- 完整 `gradlew.bat spotlessApply build` 通过，**48 个测试全部通过**：JarVersionAgentTest 5、ConfigTest 3、CorePluginAndProxyEnforcementTest 11、Lwjgl3ifyConfigPatcherTest 6、ServerStartScriptPatcherTest 11、JarVersionPropertyEnforcerTest 7、JavaRuntimeVersionTest 2、RestartPopupTest 3。
- 文档 README 增补「重启提示弹窗」节、「触发条件汇总」表（Java 版本 × 自检结果 → 行为）；context.md / ToDOLIST.md 同步。

### 决策
- **弹窗用 `invokeLater` 而不是 `invokeAndWait`**：lwjgl3ify 的 relauncher 用 `invokeAndWait` 是因为它就是要阻塞父进程等用户点确认；我们这里跑在子进程的 `preInit`，阻塞 Forge 启动会带来不必要的卡顿（且 `preInit` 是 LaunchWrapper 主线程，阻塞容易引入死锁），所以用异步触发。
- **不在 `Result.ALREADY_PRESENT` / `Result.ALREADY_OK` 弹窗**：那种情况下没有"刚刚做了改动"，弹窗只会让用户烦躁。
- **dedicated server 也尝试弹窗**：让 `RestartPopup` 自己判断 headless，而不是用 side 判断。带桌面的 Windows / Linux 服务器（运维偶尔有）也能受益；headless 自动跳过零成本。
- **保留 LOG WARN 同时弹窗**：弹窗可能被用户关掉、关错、或在 minimize 状态没看见——LOG WARN 始终是兜底证据。两者并存。

---

## 2026-04-28: 添加 lwjgl3ify config 自动补丁与服务端启动脚本并行生成（v0.3.0）

### 背景
v0.2.0 已经把 jar 做成 mod + Java Agent 双重身份，但用户仍然需要手动加一次 `-javaagent:` 启动参数。本次任务的目标是把"加参数"这件事自动化，且**不直接修改用户的启动脚本/启动器配置**——保持低侵入。

### 已完成
- 阅读 `d:\Code\GTNH LIB\lwjgl3ify-master` 的 relauncher 源码（`Lwjgl3ifyRelauncherTweaker` / `Relauncher` / `RelauncherUserInterface` / `RelauncherConfig`），确认其工作机制：作为 LaunchWrapper Tweaker 弹 Swing GUI、收集 JVM 参数、用 `ProcessBuilder` 启动子进程、当前 JVM 退出。**关键洞察**：lwjgl3ify 已经维护了一个 `customOptions` 数组并把它转发到子进程命令行，所以最经济的客户端方案是**写它的 config 文件**，而不是自己造一个 GUI relauncher。
- 新增 `com.andgatech.jdkjarversion21enforcer.Config`：从 `<configDir>/jdkjarversion21enforcer.cfg` 读 properties 风格配置（带注释模板自动写入），开关 `auto_patch_lwjgl3ify_config` 和 `auto_patch_server_start_scripts` 默认 `true`。支持 `true/false/1/0/yes/no/on/off`。
- 新增 `integration/Lwjgl3ifyConfigPatcher`：检测 `<gameDir>/config/lwjgl3ify-relauncher.json` 是否存在，用 Gson 旧版 API（`new JsonParser().parse()`）解析 JSON，向 `customOptions` 追加 `-Djdk.util.jar.version=21`（已存在则跳过），用 `GsonBuilder().setPrettyPrinting().disableHtmlEscaping()` 写回。返回 `{APPLIED, ALREADY_PRESENT, NO_CONFIG, ERROR}`。
- 新增 `integration/ServerStartScriptPatcher`：扫工作目录下 12 种已知启动脚本名（`start.bat/cmd/sh`、`run.bat/cmd/sh`、`start-server.bat/sh`、`startserver.bat/sh`、`ServerStart.bat/sh`），用 `\bjava\w*(?:\.exe)?\b` 定位 java 命令并跳过注释行（`#`、`//`、`::`、`REM`），只补丁第一条形如 java 的行（含 `-jar` 或 `.jar`），生成并行 `<name>-with-jdk-jar-21.<ext>` 文件，**绝不修改用户原脚本**；跳过包含 `-Djdk.util.jar.version=21` / `jdkjarversion21enforcer` 的脚本，且并行文件已存在时跳过。返回 `{PATCHED, ALREADY_OK, NO_SCRIPTS_FOUND, ERROR}` 与 `patchedFiles`。
- 修改 `CommonProxy.preInit`：当属性自检 `EFFECTIVE` 时直接 INFO 返回；`INEFFECTIVE` / `UNKNOWN` 时调用 `runAutoPatchersIfPossible(event)`，根据 `FMLCommonHandler.getSide()` 分流：`Side.SERVER` 走脚本补丁，其余走 lwjgl3ify config 补丁。所有结果通过 LOG 友好地告诉用户"做了什么 / 下一步该怎么做"。
- 新增 17 个单元测试：`ConfigTest`（3）、`Lwjgl3ifyConfigPatcherTest`（6）、`ServerStartScriptPatcherTest`（10），覆盖正向、已存在、不存在、注释行跳过、引号路径、CRLF/LF 行尾保留、并行文件不覆盖、Gson 解析失败、Gson HTML escaping 等。与已有 25 个合计 **45 个测试全部通过**。
- `dependencies.gradle` 加 `testImplementation 'com.google.code.gson:gson:2.2.4'`，与 Mojang 自带的运行时 Gson 同版本。
- README 增补两节："自动补丁（v0.3.0 起）"详述客户端 lwjgl3ify 检测和服务端脚本生成行为；"安装"分客户端有/无 lwjgl3ify 与服务端三种场景。
- 升级 `modVersion` 到 `0.3.0`；`context.md` / `ToDOLIST.md` 同步更新。

### 遇到的问题
- **Gson 默认 HTML escaping 把 `=` 转成 `\u003d`**：第一次跑测试时 `Lwjgl3ifyConfigPatcher` 写入文件后，`assertTrue(after.contains("-Djdk.util.jar.version=21"))` 失败。原因是 `new GsonBuilder().setPrettyPrinting()` 默认开 HTML escaping。lwjgl3ify 自己也开（无所谓，因为它的 Gson 解析时反转义），但我们希望文件人类可读，所以改用 `disableHtmlEscaping()`。lwjgl3ify 之后读取仍然兼容（Gson 解析端不区分两种形式）。
- **`\bjava\w*\b` 在 `java.exe` 上只匹到 `java`**：因为 `.` 不是 `\w`。导致 Windows 引号绝对路径场景下 `-D` 被错位插入到 `java` 和 `.exe` 之间。修复：pattern 改为 `\bjava\w*(?:\.exe)?\b`。

### 决策
- **客户端选 A 方案（写 lwjgl3ify config）而不是 C 方案（自造 mini-relauncher）**：lwjgl3ify 已经替我们做了 90% 的工作（GUI / `customOptions` / `ProcessBuilder` 重启），重复造轮子的工程量是 1500+ 行 vs 200 行。GTNH 玩家几乎都装 lwjgl3ify。
- **服务端只生成并行脚本，不修改原脚本**：避免编码 / 续行符 / Shebang / PowerShell wrapper / 容器启动脚本等边角破坏用户脚本。WARN 日志清晰告诉管理员"用并行脚本启动"。
- **配置开关默认 `true`**：对应你的明确指示。希望"装好就生效"，不是"装好之后还要去配置文件里启用"。
- **`EFFECTIVE` 时直接 INFO 返回，跳过 patchers**：避免在已经生效的环境里反复改 lwjgl3ify config 或在工作目录散布并行脚本——患者不会得"过度治疗"。
- **`Lwjgl3ifyConfigPatcher` 用 Gson 旧版 API**：`new JsonParser().parse()` 在 2.2.4 起就有，2.8.6 才加 `JsonParser.parseString` 静态方法。Mojang 1.7.10 用的是 2.2.4，保持兼容。

---

## 2026-04-28: 添加 Java Agent 双重身份与运行时自检（v0.2.0）

### 已完成
- 新增 `com.andgatech.jdkjarversion21enforcer.agent.JarVersionAgent`，提供 `premain(String)`、`premain(String, Instrumentation)`、`agentmain(String)`、`agentmain(String, Instrumentation)` 全部 4 个签名重载，全部委派到 `JarVersionPropertyEnforcer.enforce()`。
- `build.gradle` 给 `jar` 任务追加 manifest 属性 `Premain-Class` / `Agent-Class` / `Can-Redefine-Classes=false` / `Can-Retransform-Classes=false`，使产物 jar 既是 GTNH CoreMod 也是合法 Java Agent。
- `JarVersionPropertyEnforcer` 新增 `REQUIRED_FEATURE_VERSION = 21` 常量与 `detectEffectiveJarRuntimeFeatureVersion()`：反射调用 `java.util.jar.JarFile.runtimeVersion().feature()`，返回 `OptionalInt`。
- `CommonProxy.preInit` 在写属性、打日志后调用上述自检：等于 21 时 INFO `is effective`，不等时 WARN 并指引用户加 `-javaagent:<jar>` 或 `-Djdk.util.jar.version=21`。
- 测试新增 `JarVersionAgentTest`（5 个）、`JarVersionPropertyEnforcerTest.detectorReturnsAReasonableFeatureVersionOnJava9Plus`、`CorePluginAndProxyEnforcementTest` 的 5 个 verification 分类/消息测试。完整 `gradlew.bat spotlessApply build` 通过，共 25 个测试全过。
- 验证 `build/libs/jdkjarversion21enforcer-0.2.0.jar` 的 manifest 包含 `Premain-Class: com.andgatech.jdkjarversion21enforcer.agent.JarVersionAgent` 和 `Agent-Class: ...`。
- 用 Zulu 21 跑 `-javaagent:<jar>` 加载本 jar 的探针程序，agent 加载链路无异常（`JarFile.runtimeVersion().feature()=21`，与 baseline 一致——Java 21 上没有压制空间，符合预期）。
- 升级 `modVersion` 到 `0.2.0`；README 全面重写，解释 `JarFile` 静态字段时序问题、Java Agent 推荐用法和各类启动器配置示例、自检日志含义；`context.md`、`ToDOLIST.md` 同步更新。

### 遇到的问题
- **仅作 mod 不可靠**：原本以为 CoreMod 的 `static{}` 足够早，实际上 FML 的 `ModDiscoverer` 会先用 `JarFile` 扫描 `mods/` 目录读取每个 jar 的 manifest（找 `FMLCorePlugin` 属性），等本 mod 被发现并加载时 `JarFile.<clinit>` 早已执行完，`RUNTIME_VERSION` 已固化，再 `setProperty` 不会被 JDK 读到。这是这次改造的根因。
- **自检方法的副作用**：调用 `JarFile.runtimeVersion()` 会触发 `JarFile.<clinit>`，因此**严禁在 agent `premain` 阶段调用**（会直接锁死 `RUNTIME_VERSION`）。已在 `detectEffectiveJarRuntimeFeatureVersion()` 的 Javadoc 显著标注，调用点限制为 `CommonProxy.preInit`（此时 FML 已经初始化过 `JarFile`，再调用无副作用）。
- **本机无 Java 22+ JDK**：本机只装了 Zulu 21，Java 21 上 baseline 与 agent 输出都是 `feature=21`（`min(21, 21) = 21`），没法直接看到压制效果。已在 `ToDOLIST.md` 留下 follow-up：在 Java 22+ 机器上跑端到端验证。

### 决策
- **采用 Java Agent 而不是写用户启动脚本**：
  - 不偷偷修改用户的 `start.bat` / `instance.cfg` 等配置文件，避免编码/续行/格式风险与"擅自改用户文件"的负面体验。
  - 用户只需在启动参数加一次 `-javaagent:<jar>`，效果可预期、可关闭、可观察。
- **保留 CoreMod 入口和 `preInit` 的 `enforce()`**：作为兜底，行为是幂等的；同时保留路径方便用户先以 mod 形式安装、看到 WARN 后再配 `-javaagent:`。
- **manifest 同时声明 `Agent-Class`**：覆盖运行时附着场景（极少见，但成本是几行字符串），保持灵活性。
- **不删除/弱化原 "forced" 日志**：它描述的是"我已尝试 setProperty"这一意图，本身不假；新增的 verification 日志才是"实际生效"的真相，二者并存让读日志的人能精确定位问题阶段。

---

## 2026-04-27: 准备 v0.1.0 GitHub 发布

### 已完成
- 将 `modVersion` 从 `0.1.0-dev` 改为 `0.1.0`。
- 添加 `README.md`，说明模组诞生原因、行为、安装方式和构建方式。
- 将 `QQ_1777283414511.png` 复制到 `docs/assets/` 并在 README 中引用。

### 遇到的问题
- 无。

### 决策
- README 使用用户提供的图片作为“诞生原因”说明，并保留中文说明以贴近项目使用场景。

---

## 2026-04-27: 打包发布 jar

### 已完成
- 按请求运行完整 `gradlew.bat build` 打包模组 jar。
- 产物路径为 `build/libs/jdkjarversion21enforcer-0.1.0.jar`。

### 遇到的问题
- 无。

### 决策
- 使用完整 `build` 而不是只运行 `jar`，确保测试、格式检查和 reobf 打包一起通过。

---

## 2026-04-27: 添加 Java 高版本生效日志

### 已完成
- 在 Java 版本高于 21 且 `jdk.util.jar.version=21` 生效后输出日志。
- 日志内容包含检测到的 Java 主版本和被强制写入的 JVM 属性。
- 添加测试覆盖 Java `21` 不生成日志，以及 Java `22/23/25/26` 生成对应日志。

### 遇到的问题
- 无。

### 决策
- 只在普通 Forge `preInit` 阶段输出日志：CoreMod 构造期保持尽可能早且轻量，避免在更早加载阶段引入额外日志副作用。

---

## 2026-04-27: 添加 Java 版本门控

### 已完成
- 添加 `JavaRuntimeVersion`，解析 `1.8`、`17`、`21`、`22`、`23`、`25`、`26` 等 `java.specification.version` 格式。
- 将 `JarVersionPropertyEnforcer.enforce()` 改为仅在 Java 版本高于 21 时写入 `jdk.util.jar.version=21`。
- 调整 `CommonProxy`，在 Java 21 及以下直接 no-op，避免普通 Forge 生命周期继续输出加载日志。
- 添加测试覆盖 Java `8/17/21` 不启用，以及 Java `22/23/25/26` 启用。

### 遇到的问题
- **Forge 仍会扫描 jar**：同一个 CoreMod jar 无法在 JVM 启动后阻止 Forge 发现自身，因此实现为 Java 21 及以下 no-op，Java 22 及以上才执行属性强制。

### 决策
- 使用 `java.specification.version` 判断运行时主版本：这是 JVM 标准属性，适合在 CoreMod 早期读取。

---

## 2026-04-27: 创建 JDK Jar Version 21 Enforcer

### 已完成
- 创建 GTNH 1.7.10 模组项目 `JdkJarVersion21Enforcer`。
- 添加 CoreMod 入口 `JarVersion21CorePlugin`，在 CoreMod 构造期强制设置 `jdk.util.jar.version=21`。
- 添加普通 Forge `preInit` 兜底，再次强制设置同一系统属性。
- 添加单元测试覆盖属性缺失、旧值覆盖、CoreMod 入口和 `preInit` 兜底。
- 移除模板中不需要的机器、配方和 GregTech 示例依赖。
- 运行 `spotlessApply` 修复格式，并通过完整 `gradlew.bat build`。
- 确认构建产物 manifest 包含 `FMLCorePlugin` 和 `FMLCorePluginContainsFMLMod: true`。

### 遇到的问题
- **模板示例代码导致编译失败**：默认模板包含 GregTech 示例机器/配方引用，本项目不需要这些内容，已删除相关占位文件和依赖。
- **`coreModClass` 配置阶段检查源码文件**：GTNH convention 插件会在 Gradle 配置阶段验证 CoreMod 类文件存在，已先添加最小 `IFMLLoadingPlugin` 入口。
- **Spotless 换行格式检查失败**：模板文件和补丁文件换行不一致，已使用 `gradlew.bat spotlessApply` 统一格式。

### 决策
- 使用 CoreMod 方案而不是启动器参数改写：Forge 模组无法在 JVM 真实启动前修改命令行参数，但 CoreMod 能在 Forge 加载早期尽快设置系统属性。
- 保留普通 `@Mod` 入口：用于日志和 `preInit` 阶段兜底校验，避免只依赖 CoreMod 构造路径。
