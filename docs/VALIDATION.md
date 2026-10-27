# 验证记录

## 0.13.3-beta — 2026-10-27：1.21 / 1.21.1 通用包

发布 JAR 使用 Minecraft 1.21 / NeoForge 21.0.167 / AE2 19.0.10-alpha 编译。元数据：Minecraft `[1.21,1.21.2)`、NeoForge `[21.0.167,21.2)`、AE2 `[19.0.10-alpha,20)`。AE2 自身的游戏版本与依赖要求仍须满足。

本次通过 smokeArtifact 模式直接加载同一个已打包 JAR；测试辅助检查 NeoForge 的实际模组文件路径，日志记录 SHA-256。启动器仅注册 smoke 开发源集，避免 main 开发类遮蔽已打包类。首次启动发现该源集隔离问题后已修正，失败日志亦保留；没有因此修改生产功能代码。

| Minecraft / NeoForge | AE2 | 同一发布 JAR 实测 |
| --- | --- | --- |
| 1.21 / 21.0.167 | 19.0.10-alpha | SMOKE_PASS，正常退出 |
| 1.21.1 / 21.1.241 | 19.0.20-beta | SMOKE_PASS，正常退出 |
| 1.21.1 / 21.1.241 | 19.2.18 + GuideME 21.1.16 | SMOKE_PASS，正常退出；112 项 JUnit 通过 |

两个版本的 112 项 JUnit 均通过，0 失败、0 错误、0 跳过。游戏测试核验严格加载名单，无 EAE、EAEP、JEI；真实客户端上传与服务端源槽 3→2 / 供应器合计新增 1 张均通过。另覆盖原生映射、备注持久化、拼音、收藏、分组、布局和运行时开关，已查看两端最终截图。可选联动、完整编码触发自动上传、图标拖放及独立多人服务器没有在本轮专项重测。

文件：`outputs/universal/0.13.3-beta/ae2-upload-sidebar-1.21-1.21.1-0.13.3-beta.jar`。SHA-256：`4190D937F46E0C37D7AB23CCCBBFB8B740226512D95BE47657AFE5BC55B30D87`。生产 JAR 无 smoke 类或嵌套依赖 JAR；原始报告、日志和截图位于同目录 verification，旧版本保留。

## Minecraft 1.21 探测 — 2026-10-27

使用独立目录、JDK 21、NeoForge 21.0.167；不修改生产功能代码，不将默认 1.21.1 发布包直接声明为支持 1.21。

| AE2 版本 | 结果 |
| --- | --- |
| 19.0.0-alpha | 编译失败：PatternEncodingTermMenu 继承链没有 getGridNode() |
| 19.0.9-alpha | 相同编译失败；保存完整失败日志 |
| 19.0.10-alpha | 编译通过，112 项 JUnit 全通过，实际游戏最小环境 SMOKE_PASS，正常退出 |
| 19.0.19-beta | 编译通过，112 项 JUnit 全通过，实际游戏最小环境 SMOKE_PASS，正常退出 |

官方 [PR #8024](https://github.com/AppliedEnergistics/Applied-Energistics-2/pull/8024) 在 MEStorageMenu 中把 public getNetworkNode() 改名为 getGridNode()；该变更首次列入 [19.0.10-alpha](https://github.com/AppliedEnergistics/Applied-Energistics-2/releases/tag/neoforge/v19.0.10-alpha)。本模组 ServerUploads 使用后者，所以当前代码直接兼容的 API 下限是 19.0.10-alpha。更早版本需要实现旧方法兼容分支；不能单改元数据来宣称支持。接口契约测试已补上此方法的签名和访问性检查。没有宣称逐一运行过所有中间版本。

两次游戏测试严格加载名单都是 Minecraft、NeoForge、AE2、本模组及 smoke 测试辅助；没有 EAE、EAEP、JEI、GuideME。覆盖真实网络上传及服务端源槽 3→2 / 供应器合计新增 1 张、原生映射、备注持久化、拼音搜索、收藏、分组展开、布局与运行时开关。最终截图已检查。未专项验证此组合的图标拖放、完整编码触发自动上传、可选附属模组或独立多人服务器。

探测包 `ae2-upload-sidebar-1.21-0.13.2-beta-compat-probe.jar`：Minecraft `[1.21,1.21.1)`、NeoForge `[21.0.167,21.1)`、AE2 `[19.0.10-alpha,20)`；内部版本 `0.13.2-beta-probe`，无 smoke 类或嵌套依赖 JAR。SHA-256：`A8789EF7773925F852AB72AAEE6CBF3ED7802622C7B9BA9CE11515F780D5F7D6`。这是独立测试包，默认正式包仍针对 1.21.1。原始日志、报告、截图保存在本机 `outputs/compatibility/1.21/`。

## 0.13.2-beta — 2026-10-27：最低版本调查

下限降至 `19.0.20-beta`，生产声明与测试辅助统一为 `[19.0.20-beta,20)`，生产代码也使用该版本编译。这里的下限是当前 Minecraft 1.21.1 构建的官方版本边界，不代表更早 AE2 的上传 API 必然不兼容。

| AE2 版本 | 核验方式与结论 |
| --- | --- |
| 19.0.19-beta | 官方发布面向 Minecraft 1.21，JAR 内声明 `minecraft: 1.21`。未强行绕过平台依赖运行。 |
| 19.0.20-beta | 首个官方切换至 1.21.1 的版本；全量源码编译、112 项单元/接口测试、无 GuideME 最小环境 SMOKE_PASS。 |
| 19.0.24 | 较早稳定版；编译检查、112 项单元/接口测试、无 GuideME 最小环境 SMOKE_PASS。 |
| 19.2.0-beta | 读取官方变更及 JAR 声明，确认开始依赖 GuideME（声明版本 2.5.1）；未专项启动该过渡版。 |
| 19.2.7 | 上一轮 0.13.1 已完成 111 项测试及最小环境实测；本轮未重复启动。 |
| 19.2.18 | 同一份最低版本编译产物，112 项单元/接口测试、携带 GuideME 21.1.16 的最小环境 SMOKE_PASS。 |

旧版最小环境实际仅含 Minecraft、NeoForge、AE2、本模组和测试辅助。验证真实客户端上传，服务端源样板 3→2、供应器合计新增 1 张；同时覆盖自带映射、备注、拼音、收藏、分组、布局与运行时开关。运行使用 NeoForge 21.1.241，未承诺老 NeoForge 版本可用。旧版的可选 JEI、EAEP、无线终端组合及完整自动编码流程未专项实测。

官方 API/实现变化核对：

- [PR #6803](https://github.com/AppliedEnergistics/Applied-Energistics-2/pull/6803) 在 2022 年引入 PatternContainer，早于 AE2 19.x。实际 JAR 契约检查验证 `getGrid` 所需类型、`isVisibleInTerminal`、`getTerminalPatternInventory`、分组名称及库存插入接口；不是仅凭接口名称判断兼容。
- [PR #8128](https://github.com/AppliedEnergistics/Applied-Energistics-2/pull/8128) 将游戏版本升至 1.21.1，对应 [19.0.20-beta 发布](https://github.com/AppliedEnergistics/Applied-Energistics-2/releases/tag/neoforge/v19.0.20-beta)。这是本轮支持下限的依据。
- [PR #8337](https://github.com/AppliedEnergistics/Applied-Energistics-2/pull/8337) 删除 PatternProviderMenu 的三参数构造函数，并调整带 MenuType 构造函数可见性。本模组不直接构造该菜单，不受这处变化影响。
- [PR #8342](https://github.com/AppliedEnergistics/Applied-Energistics-2/pull/8342) 将指南迁移到 GuideME；[19.2.0-beta 发布](https://github.com/AppliedEnergistics/Applied-Energistics-2/releases/tag/neoforge/v19.2.0-beta) 将其列为前置。19.0.x 无该前置，不能把当前版本的 GuideME 要求套用于全部 19.x。
- [PR #8427](https://github.com/AppliedEnergistics/Applied-Energistics-2/pull/8427) 将 `transferStackToMenu(ItemStack)` 的返回类型由 ItemStack 改为 int。本模组未调用或覆盖该方法；自动编码钩子目标仍为实例方法 `encode()V`，已新增明确的签名回归检查。
- `AEBaseScreen.getExclusionZones()` 和 `hasClickedOutside(DDIII)Z` 通过真实 JAR 签名检查及游戏内 Mixin 应用测试；19.0.20-beta 实际截图确认面板纹理和文字可正常绘制。

未对范围内每个中间版逐一实测；不保证未来 19.x 兼容。其他附属模组及 AE2 自身的依赖仍分别生效。

三轮游戏均正常退出。0.13.2-beta 生产包元数据范围核验通过，不包含 smoke 类或依赖 JAR。SHA-256：`2A6C0C4B87134933B48793196217134F62076B8C72213FBD43B4B985577A4326`。

## 0.13.1-beta — 2026-10-27

- AE2 声明从 `[19.2.17,19.3)` 改为 `[19.2.7,20)`；生产包与测试辅助声明一致，Minecraft/NeoForge 约束不变。
- 从 [AE2 官方 19.2.7 发布](https://github.com/AppliedEnergistics/Applied-Energistics-2/releases/tag/neoforge/v19.2.7)取得测试依赖，JAR SHA-256：`7832F007BBC9A270887E6CBD3717A4D9C93B3F4D3BD9538096580AC80562E489`。
- 所有生产源码改用 19.2.7 API 编译成功，避免仅改版本声明却保留较新静态 API 引用。
- AE2 19.2.7 和 19.2.18 均通过接口契约及 111 项 JUnit 测试，0 失败、0 错误、0 跳过。
- 同一构建在两个版本的最小环境均 SMOKE_PASS、正常退出。包含严格加载名单检查、真实客户端上传、服务端源槽 3→2 / 供应器新增 1 张、原生映射、备注、拼音、收藏、分组、布局和开关。
- 范围内其他版本未逐个实测；未来 19.x 的内部方法、Mixin 目标或行为变化仍可能造成不兼容。此范围是加载策略，不是未来兼容性保证，也不会绕过 AE2 自身或其他附属模组的依赖限制。
- 生产包核验版本 0.13.1-beta、AE2 范围 `[19.2.7,20)`，不包含测试模组或依赖 JAR。SHA-256：`497D9849493E17D56652DC1056A9678AF375B7D9376F4A3DE5DDF91D5C23ABC1`。

## 0.13.0-beta — 2026-10-27

### 补充：最小环境实测

- `-Pminimal=true -Psmoke=true -Pae2Version=19.2.18`，NeoForge 21.1.241、GuideME 21.1.16，SMOKE_PASS；客户端正常退出。
- 启动时按严格白名单核验实际加载：Minecraft、NeoForge、AE2、GuideME、本模组和仅用于验证的 smoke 辅助模组。无 EAE、EAEP、JEI、AE2 JEI Integration、AE2WTLib、AE2 Utility、Glodium。
- 真实网络上传成功：客户端源样板从 3 张减至 2 张，服务端再次确认源槽 2 张、两个供应器合计新增 1 张。
- 自带映射增删改/管理界面、无 JEI 的原生配方类型回退、备注持久化、拼音搜索、收藏、分组展开选中具体目标、布局、运行时开关均通过。
- JEI、无线终端、扩展供应器专项不适用，未计为通过。未专项测试无 JEI 下完整编码触发自动上传或独立多人服务器。
- 缺少 JEI 时 Mixin 会输出一次可选目标类不存在的 WARN；没有阻断启动、界面使用或上传。
- 本轮仅补测试配置、测试代码和说明；0.13.0-beta 安装 JAR 未改动，SHA-256：`B62B08ADD5AA71562872F7E56F407924226391FC728B47AC311390147A3C6675`。

### 完整集成回归

- JDK 21、Gradle 9.2.1：离线 test/build 通过；111 项 JUnit 测试，0 失败、0 错误、0 跳过。
- AE2 19.2.18、NeoForge 21.1.241，无 ExtendedAE Plus：隔离客户端 SMOKE_PASS，正常退出。
- 同版本加 ExtendedAE Plus 1.6.2＋CozyUI+ v1.10：SMOKE_PASS，正常退出；覆盖资源包和 2x/4x 缩放。
- 新增实际界面检查：设置开关、合并/展开/再次折叠、子项备注编辑选中准确 token、分组操作不发上传包、组头不接受图标拖放。
- 分组单元测试覆盖原始目标身份、不同备注及收藏分开、搜索后的单项、关闭分组及空列表。
- 实际截图检查 AE2 行背景与槽位数字边距，确认文字不再压住底部边框。
- Cozy 4x 截图仍可见底层 AE2 控件提示与侧栏提示重叠；属于已存在的提示层问题，本次背景及分组修改未处理该问题。
- 本机 ExtendedAE 1.21-2.2.32 的接口契约测试通过：装配矩阵样板核心实现 PatternContainer，并提供网络、样板库存及显示分组接口。另核对[官方源码](https://github.com/GlodBlock/ExtendedAE/blob/1.21-neoforge/src/main/java/com/glodblock/github/extendedae/common/tileentities/matrix/TileAssemblerMatrixPattern.java)的合成样板过滤；尚未专项实测完整矩阵结构，不将接口核验表述为完整矩阵实测。

## 0.12.1-beta — 2026-10-27

- JDK 21、Gradle 9.2.1：离线 test/build 通过。
- 107 项 JUnit 测试：0 失败、0 错误、0 跳过。
- Minecraft 1.21.1、NeoForge 21.1.241、AE2 19.2.18，无 ExtendedAE Plus：隔离客户端 SMOKE_PASS，正常退出。
- 覆盖普通/无线终端实际上传、自动上传、映射、备注、图标拖放、拼音搜索、收藏、定位和供应器直接标记。
- 测试目录改由 Gradle 显式传入，仍核对实际游戏目录；不再依赖开发者本机路径。
- 生产包检查：版本 0.12.1-beta、MIT 元数据、项目 MIT 许可、原始拼音数据许可；不包含测试模组和依赖 JAR。

## 历史功能回归 — 2026-10-25

0.12.0-test 通过相同 107 项单元测试，以及 AE2 19.2.18 的独立模式和 ExtendedAE Plus 1.6.2＋CozyUI+ v1.10 集成测试。后者包含资源包实际贴图与 2x/4x 缩放检查。

## 未验证范围

独立多人服务器、任意第三方终端及未来依赖版本不在上述实测范围。允许加载的版本范围不等于全部版本均已实测。版本保持 Beta。

原始日志、截图与测试世界留在本机 run-test/outputs，不提交源码仓库。
