# 从源码构建

## 工具

- JDK 21：自行安装并设置 JAVA_HOME，或在个人 Gradle 配置中指定 org.gradle.java.installations.paths。
- Gradle 9.2.1：仓库提供 Wrapper。若本机已有同版本，可直接使用 gradle 命令。Wrapper 首次运行会下载 Gradle 发行包；已有缓存时无需重新下载。
- 项目关闭了 Java toolchain 自动下载，不自动安装 JDK。

## 本地模组依赖

当前构建使用本地 JAR，第三方模组及资源包不随源码仓库分发。将下列版本放到仓库根目录 test-dependencies，或通过 `-PpackMods=/path/to/mods` 指定目录。请从对应作者的正式发布渠道获取，不能只重命名其他版本冒充。

| 文件名 | 用途 |
| --- | --- |
| appliedenergistics2-19.2.17.jar | 编译基线及默认运行版本 |
| appliedenergistics2-19.2.18.jar | 可选的新版本兼容测试 |
| jei-1.21.1-neoforge-19.27.0.336.jar | 编译与 JEI 功能测试 |
| ae2jeiintegration-1.2.1.jar | 配方类别捕获 |
| ExtendedAE-1.21-2.2.32-neoforge.jar | 扩展供应器测试 |
| Glodium-1.21-2.2-neoforge.jar | 测试依赖 |
| guideme-21.1.16.jar | 测试依赖 |
| ae2utility-1.8.0.jar | 集成场景测试 |
| ae2wtlib-19.5.0.jar | 无线终端及接口契约测试 |
| extendedae_plus-1.6.2.jar | 仅 -Pplus=true 时使用 |

正常构建与完整测试准备以上文件（19.2.18 和 Plus 除外，按选项添加）。AE2 始终以 19.2.17 编译，ae2Version 只切换运行与接口验证版本。不同组合的测试模组不等于本模组的必装列表。

作者渠道：[AE2](https://github.com/AppliedEnergistics/Applied-Energistics-2)、[JEI](https://github.com/mezz/JustEnoughItems)、[ExtendedAE](https://www.curseforge.com/minecraft/mc-mods/ex-pattern-provider)、[ExtendedAE Plus](https://www.curseforge.com/minecraft/mc-mods/extendedae-plus)。其余依赖按准确文件名在作者发布页查找。

## 命令

Windows 使用 `gradlew.bat`，Linux/macOS 使用 `./gradlew`：

```powershell
.\gradlew.bat test build
.\gradlew.bat test build '-Pae2Version=19.2.18'
.\gradlew.bat runClient '-Psmoke=true' '-Pae2Version=19.2.18'
.\gradlew.bat runClient '-Psmoke=true' '-Pae2Version=19.2.18' '-Pplus=true'
```

构建结果在 build/libs。普通 JAR 用于安装，sources.jar 仅是源码。模组版本只在 gradle.properties 的 mod_version 设置，资源处理时注入元数据。

## 隔离游戏测试

`-Psmoke=true` 会在项目 run-test 目录启动可见客户端，创建新的测试世界，操作测试机器与测试配置，执行断言并自动退出。需要图形环境。不要把正式存档或正式游戏配置放入该目录。

Gradle 显式传入隔离目录，测试还会核对实际游戏目录；没有此参数就拒绝执行。smoke 源码独立于生产 source set，不会打包进安装 JAR。

结果文件：run-test/embedded-smoke-result.txt，必须是本轮的 SMOKE_PASS；旧报告不能代表新测试成功。日志和截图同在 run-test 内，均不提交 Git。

可选 CozyUI 回归：把自行取得的 CozyUI+_v1.10.zip 放入 run-test/resourcepacks，并追加 `-Pcozy=true`。此检查针对该版本的具体贴图，不是所有资源包通用测试。

## 代码目录

- src/main/java：客户端面板、搜索与私人配置、网络协议和服务端上传逻辑。
- src/main/resources：模组元数据、Mixin 配置、拼音数据和第三方许可。
- src/test/java：纯逻辑、存储保护和实际依赖 JAR 的接口契约测试。
- src/smoke/java：真实游戏场景验证，仅在显式启用时加载。

依赖、构建缓存、测试实例、旧发布产物及历史开发记录保留在本机并由 .gitignore 排除。提交前检查 git diff --cached，避免加入存档、配置或凭据。