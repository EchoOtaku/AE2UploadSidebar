# 从源码构建

## 工具

- JDK 21：自行安装并设置 JAVA_HOME，或在个人 Gradle 配置中指定 org.gradle.java.installations.paths。
- Gradle 9.2.1：仓库提供 Wrapper。若本机已有同版本，可直接使用 gradle 命令。Wrapper 首次运行会下载 Gradle 发行包；已有缓存时无需重新下载。
- 项目关闭了 Java toolchain 自动下载，不自动安装 JDK。

## 本地模组依赖

当前构建使用本地 JAR，第三方模组及资源包不随源码仓库分发。将下列版本放到仓库根目录 test-dependencies，或通过 `-PpackMods=/path/to/mods` 指定目录。请从对应作者的正式发布渠道获取，不能只重命名其他版本冒充。

| 文件名 | 用途 |
| --- | --- |
| appliedenergistics2-19.0.10-alpha.jar | 通用包编译基线、1.21 最低版测试 |
| appliedenergistics2-19.0.20-beta.jar | 1.21.1 最低官方版本测试 |
| appliedenergistics2-19.0.24.jar | 可选的旧稳定版兼容测试 |
| appliedenergistics2-19.2.7.jar | 可选的历史兼容测试 |
| appliedenergistics2-19.2.17.jar | 默认运行版本 |
| appliedenergistics2-19.2.18.jar | 可选的新版本兼容测试 |
| jei-1.21.1-neoforge-19.27.0.336.jar | 编译与 JEI 功能测试 |
| ae2jeiintegration-1.2.1.jar | 配方类别捕获 |
| ExtendedAE-1.21-2.2.32-neoforge.jar | 扩展供应器测试 |
| Glodium-1.21-2.2-neoforge.jar | 测试依赖 |
| guideme-21.1.16.jar | 测试依赖 |
| ae2utility-1.8.0.jar | 集成场景测试 |
| ae2wtlib-19.5.0.jar | 无线终端及接口契约测试 |
| extendedae_plus-1.6.2.jar | 仅 -Pplus=true 时使用 |

正常构建与完整测试准备以上必需文件，可选历史版本及 Plus 按测试选项添加。AE2 默认以最低允许版本 19.0.10-alpha 编译，ae2Version 切换运行与接口验证版本；仅调查 API 时可用 ae2CompileVersion 显式覆盖编译版本，正式发布保持最低版本基线。不同组合的测试模组不等于本模组的必装列表。

作者渠道：[AE2](https://github.com/AppliedEnergistics/Applied-Energistics-2)、[JEI](https://github.com/mezz/JustEnoughItems)、[ExtendedAE](https://www.curseforge.com/minecraft/mc-mods/ex-pattern-provider)、[ExtendedAE Plus](https://www.curseforge.com/minecraft/mc-mods/extendedae-plus)。其余依赖按准确文件名在作者发布页查找。

## 命令

Windows 使用 `gradlew.bat`，Linux/macOS 使用 `./gradlew`：

```powershell
.\gradlew.bat test build
.\gradlew.bat test build '-Pae2Version=19.2.18'
.\gradlew.bat runClient '-Psmoke=true' '-Pae2Version=19.2.18'
.\gradlew.bat runClient '-Psmoke=true' '-Pae2Version=19.2.18' '-Pplus=true'
.\gradlew.bat runClient '-Psmoke=true' '-Pae2Version=19.2.18' '-Pminimal=true'
.\gradlew.bat runClient '-Psmoke=true' '-Pae2Version=19.2.7' '-Pminimal=true'
.\gradlew.bat test runClient '-Psmoke=true' '-Pae2Version=19.0.20-beta' '-Pminimal=true' '-PguideMe=false'
.\gradlew.bat test runClient '-Psmoke=true' '-Pae2Version=19.0.24' '-Pminimal=true' '-PguideMe=false'
```

构建结果在 build/libs。普通 JAR 用于安装，sources.jar 仅是源码。模组版本只在 gradle.properties 的 mod_version 设置，资源处理时注入元数据。

## 隔离游戏测试

`-Psmoke=true` 会在项目 compatibility/<gameVersion>/run-test 目录启动可见客户端，创建新的测试世界，操作测试机器与测试配置，执行断言并自动退出。需要图形环境。不要把正式存档或正式游戏配置放入该目录。

Gradle 显式传入隔离目录，测试还会核对实际游戏目录；没有此参数就拒绝执行。smoke 源码独立于生产 source set，不会打包进安装 JAR。

结果文件：compatibility/<gameVersion>/run-test/embedded-smoke-result.txt，必须是本轮的 SMOKE_PASS；旧报告不能代表新测试成功。日志和截图同在 run-test 内，均不提交 Git。

可选 CozyUI 回归：把自行取得的 CozyUI+_v1.10.zip 放入 compatibility/1.21.1/run-test/resourcepacks，并追加 `-Pcozy=true`。此检查针对该版本的具体贴图，不是所有资源包通用测试。

最小环境使用 `-Pminimal=true`：游戏运行时只加入 AE2 和 GuideME，不加入 EAE、EAEP、JEI、AE2 JEI Integration、AE2WTLib、AE2 Utility 或 Glodium。测试启动时严格校验模组名单（另含 Minecraft、NeoForge、本模组和测试辅助），验证原生终端上传、自带映射、备注、拼音、收藏、分组及布局。无线终端与 JEI 专项不适用于该环境，不计为通过。该选项不能与 plus/cozy 同时使用；编译期 JEI API 和接口契约测试用 JAR 仍需准备，但不会加入游戏运行时。

对尚未拆分指南的 AE2 19.0.x，最小环境额外指定 `-PguideMe=false`，运行时及加载白名单都不包含 GuideME。19.2.0-beta 引入 GuideME 后须按该 AE2 版本自身依赖准备指南模组；不能直接拿当前 GuideME 21.1.16 替代其声明的 2.5.1。本轮只检查了该过渡版的声明，没有专项启动该组合。

### 通用包构建与同一 JAR 双版本验证

默认开发环境为 1.21.1；发布通用包须使用较低的 1.21 / NeoForge 21.0.167 基线构建。gameVersion 只切换开发运行环境和输出目录，两个环境生成的元数据均为 Minecraft [1.21,1.21.2)、AE2 [19.0.10-alpha,20)、NeoForge [21.0.167,21.2)。

```powershell
.\gradlew.bat test build '-PgameVersion=1.21' '-Pminimal=true' '-PguideMe=false' '-Pae2Version=19.0.10-alpha'
# 将 build-compat-1.21/libs 中的普通 JAR 复制到固定发布路径，以下两个命令使用同一个文件
.\gradlew.bat runClient '-PgameVersion=1.21' '-Psmoke=true' '-Pminimal=true' '-PguideMe=false' '-Pae2Version=19.0.10-alpha' '-PsmokeArtifact=<发布JAR路径>'
.\gradlew.bat runClient '-PgameVersion=1.21.1' '-Psmoke=true' '-Pminimal=true' '-PguideMe=false' '-Pae2Version=19.0.20-beta' '-PsmokeArtifact=<同一发布JAR路径>'
```

smokeArtifact 模式只注册 smoke 开发源集，本模组从指定 JAR 加载；测试启动时核对 NeoForge 实际加载路径并记录 SHA-256。不要使用测试期间重新生成的开发输出替代已固定的发布 JAR。游戏目录分别为 compatibility/1.21/run-test 和 compatibility/1.21.1/run-test，均创建新的隔离世界。

1.21.1 新版回归可改为 -Pae2Version=19.2.18 -PguideMe=true。AE2 19.0.9-alpha 及更早版本缺少 getGridNode()，不能直接放宽下限。最初的 compatProbe 参数已由 gameVersion 和 smokeArtifact 替代。

## 代码目录

- src/main/java：客户端面板、搜索与私人配置、网络协议和服务端上传逻辑。
- src/main/resources：模组元数据、Mixin 配置、拼音数据和第三方许可。
- src/test/java：纯逻辑、存储保护和实际依赖 JAR 的接口契约测试。
- src/smoke/java：真实游戏场景验证，仅在显式启用时加载。

依赖、构建缓存、测试实例、旧发布产物及历史开发记录保留在本机并由 .gitignore 排除。提交前检查 git diff --cached，避免加入存档、配置或凭据。
