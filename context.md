# 项目上下文

## 基本信息
- Mod Name: JDK Jar Version 21 Enforcer
- Mod ID: jdkjarversion21enforcer
- Package: `com.andgatech.jdkjarversion21enforcer`
- Target: MC 1.7.10 + GTNH 2.8.4

## 已实现内容

### 启动期逻辑
| 类 | 阶段 | 状态 |
|----|------|------|
| `JarVersionPropertyEnforcer` | 通用属性强制器 | 已实现 |
| `JavaRuntimeVersion` | Java 运行时版本解析 | 已实现 |
| `JarVersion21CorePlugin` | CoreMod / `IFMLLoadingPlugin` | 已实现 |
| `CommonProxy.preInit` | Forge `preInit` 兜底和生效日志 | 已实现 |

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
| `jdk.util.jar.version` | `21` | Java 版本高于 21 时由 CoreMod 和 `preInit` 强制写入的 JVM 系统属性 |

### Mixins
- 未启用 Mixins。

## Dependencies
- 仅使用 GTNH Gradle/Forge 开发环境和 JUnit 5 测试依赖。

## 架构说明
- `gradle.properties` 中 `coreModClass = core.JarVersion21CorePlugin`，由 GTNH convention 插件写入 CoreMod manifest。
- `JarVersion21CorePlugin` 不注册 ASM transformer，只利用 CoreMod 加载时机尽早调用 `JarVersionPropertyEnforcer.enforce()`。
- `JarVersionPropertyEnforcer` 读取 `java.specification.version`：Java `22/23/25/26` 等高于 21 的版本会写入 `jdk.util.jar.version=21`，Java `21` 及以下保持 no-op。
- Java 高于 21 时，`CommonProxy.preInit` 会输出类似 `Java 22 detected; forced jdk.util.jar.version=21.` 的日志。
- `build.gradle` 的开发运行参数也包含 `-Djdk.util.jar.version=21`，方便本地 `runClient` / `runServer` 与实际目标保持一致。
- 构建产物 `jdkjarversion21enforcer-0.1.0-dev.jar` 的 manifest 包含 `FMLCorePlugin` 和 `FMLCorePluginContainsFMLMod: true`。
