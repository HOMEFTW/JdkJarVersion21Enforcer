# 开发日志

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
