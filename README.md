# JDK Jar Version 21 Enforcer

为 GTNH / Minecraft 1.7.10 Forge 环境在 Java 22+ 下自动设置 `-Djdk.util.jar.version=21`，把 `JarFile` 的多版本行为压回 Java 21 兼容性档位，**无需手动改启动器配置**。

## 这个模组为什么诞生

高版本 Java、LWJGL、`MemoryUtil`、`sun.misc.Unsafe`、FFM、`MethodHandle` 等机制交织时，旧版 Minecraft / Forge / Mod 生态里的 jar 与运行时行为可能冲突。最直接的修复是 `-Djdk.util.jar.version=21`，但要让普通玩家自己改 JVM 参数门槛很高。

![诞生原因](docs/assets/QQ_1777283414511.png)

## 一个关键事实

`jdk.util.jar.version` **只在 `JarFile` 类首次初始化时被读一次**，固化到静态字段。一旦 `JarFile` 已经初始化（FML 扫 mods/ 时就触发了），再 `setProperty` 也没用。

所以本 mod 的 jar **同时是 Forge mod / CoreMod / Java Agent / LaunchWrapper Tweaker**——同一份 jar 通过 manifest 声明四种身份。Tweaker 在 Forge mod 加载之前跑，最早能介入的阶段。

## 安装

把 `jdkjarversion21enforcer-0.5.12.jar` 放进 `mods/` 即可。

## 使用方式

### 方式 A：自动 fork（最方便，零配置）

直接放到 `mods/` 启动游戏。Java 22+ 时本 mod 在 LaunchWrapper Tweaker 阶段弹一个对话框「Configure JVM startup arguments」，点 **Apply & Restart Now** —— mod 自己 fork 一个新 JVM 把 `-Djdk.util.jar.version=21` 加上，子进程跑游戏。

> **注意**：每次启动都会 fork 一次（约 6 秒额外启动时间），因为 GTNH RFB 不读 lwjgl3ify 的 customOptions。

### 方式 B：启动器直接传参（最高效，零启动开销）

在启动器 JVM 参数里加：

```
-Djdk.util.jar.version=21
```

或者：

```
-javaagent:mods/jdkjarversion21enforcer-0.5.12.jar
```

各启动器位置：

- **HMCL / PCL2 / BakaXL**：游戏 JVM 参数
- **PrismLauncher / MultiMC**：实例 → 设置 → Java → "JVM 参数"
- **服务端 `start.bat` / `run.sh`**：在 `java` 与 `-jar` 之间插入

加了之后，本 mod 检测到 `-D` 已生效就静默不动，无需 fork，无对话框。

## 适用范围

- Minecraft 1.7.10
- Forge / GTNH 环境
- Java 22+（≤ 21 时本 mod 完全 no-op）

## 端到端验证（开发者）

```bat
javac -d build\agent-verify Probe.java
java -javaagent:build\libs\jdkjarversion21enforcer-0.5.12.jar -cp build\agent-verify Probe
```

`Probe.java`：

```java
public class Probe {
    public static void main(String[] args) {
        System.out.println("jdk.util.jar.version=" + System.getProperty("jdk.util.jar.version"));
        System.out.println("JarFile.runtimeVersion().feature()=" + java.util.jar.JarFile.runtimeVersion().feature());
    }
}
```

期望输出：

```
jdk.util.jar.version=21
JarFile.runtimeVersion().feature()=21
```

## 可靠性矩阵

| Java 版本 | 场景 | 本 mod 动作 |
|---|---|---|
| ≤ 21 | 任何 | no-op |
| 22+ | 启动器已传 `-D` 或 `-javaagent:` | 静默通过，自检 EFFECTIVE |
| 22+ | GTNH 官方 RFB 整合包（不传 `-D`） | Tweaker 弹对话框 + fork 子 JVM，每次启动一次 |
| 22+ | 独立启动器 + lwjgl3ify | 自动写 lwjgl3ify customOptions，第二次启动生效 |
| 22+ | 独立启动器 + 无 lwjgl3ify | Tweaker 弹对话框 + fork 子 JVM |
| 22+ | 服务端 | preInit 生成并行启动脚本，用它启动后生效 |

## 故障排查

如果游戏没正常启动，搜 `<gameDir>/logs/fml-client-latest.log` 里的诊断标签：

- `[launch-args]` —— 从 LaunchWrapper Tweaker 阶段捕获的 main args
- `[relaunch-cp]` —— 子 JVM classpath 探测
- `[main-args]` —— main args 来源决策（LaunchArgsCapture / ProcessHandle / split）
- `[tweaker]` —— Tweaker 阶段的总入口

子进程的 stdout/stderr 重定向到 `<gameDir>/logs/jdkjarversion21enforcer-relaunch-{out,err}.log`。

## 完整开发历程

详见 `log.md`。一句话总结：v0.5.0 → v0.5.12 在 GTNH RFB / 中文路径 / HMCL / Windows ProcessHandle 限制 / Java 9+ 模块墙等多重场景下迭代 8 个版本，把 fork 子 JVM 这条最复杂的路径完全跑通。

## 协议

参考仓库 `LICENSE`。
