# 开发日志

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
