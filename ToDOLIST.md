# TODO 列表

## 当前计划
- [ ] 推送代码到 `https://github.com/HOMEFTW/JdkJarVersion21Enforcer`。
- [ ] 创建 `v0.1.0` GitHub Release 并上传新打包 jar。

## 未来想法
- [ ] 如需真正修改启动器命令行，可另做启动器配置生成器或实例补丁工具。

## 已完成
- [x] 准备 `v0.1.0` 版本号、README 和 README 图片资产。
- [x] 按请求打包生成最新 `jdkjarversion21enforcer-0.1.0.jar`。
- [x] Java 高于 21 触发属性强制时，在 `preInit` 后续输出生效日志。
- [x] 添加 Java 版本检测：Java `22/23/25/26` 启用属性强制，Java `21` 及以下保持 no-op。
- [x] 创建一个 GTNH CoreMod，在 Forge 加载早期强制设置 `-Djdk.util.jar.version=21` 对应的系统属性。
- [x] 完整构建并确认产物可打包为 GTNH CoreMod。

## 已拒绝 / 延后
- 暂无。
