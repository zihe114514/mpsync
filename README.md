<div align="center">

# MusicParty / mpsync

**让整个服务器一起听歌。**

<sub>*Server-side listen-together rooms for Minecraft. Players queue songs from their in-game music player, everyone hears the same track at the same moment. Works with any platform the client supports. 7 MC versions × 5 server targets (Fabric / Forge / NeoForge / Paper / vanilla Java Agent).*</sub>

[![版本](https://img.shields.io/badge/%E7%89%88%E6%9C%AC-1.4.2-e44050?style=flat-square&labelColor=2b2b33)](#安装)
[![Minecraft](https://img.shields.io/badge/Minecraft-1.20.1%20%7C%201.21.1%20%7C%201.21.10%20%7C%201.21.11%20%7C%2026.1.2%20%7C%2026.2%20%7C%2026.3-3d8528?style=flat-square&labelColor=2b2b33)](#支持的游戏版本)
[![加载器](https://img.shields.io/badge/%E5%8A%A0%E8%BD%BD%E5%99%A8-Fabric%20%7C%20Forge%20%7C%20NeoForge%20%7C%20Paper%20%7C%20Java%20Agent-7a4fd0?style=flat-square&labelColor=2b2b33)](#支持的游戏版本)
[![协议](https://img.shields.io/badge/%E5%8D%8F%E8%AE%AE-%E5%BC%80%E6%94%BE%E6%8E%A5%E5%85%A5-2f7fd0?style=flat-square&labelColor=2b2b33)](docs/接入指南.md)

**配套客户端：[MuoniumPlayer](https://github.com/zihe114514/MuoniumPlayer-multiversion)** —— 游戏内音乐播放器 Mod，房间点歌、投票、队列全在它的界面里操作。
官网：[melodifymc.cc.cd](https://melodifymc.cc.cd/) · MC 百科：[class/31323](https://www.mcmod.cn/class/31323.html)

---

## 🔗 生态索引 —— 从这里开始

一起听是一个三件套生态，按你的身份对号入座：

| 我是… | 去哪里 | 做什么 |
| --- | --- | --- |
| **玩家** | [MuoniumPlayer 客户端](https://github.com/zihe114514/MuoniumPlayer-multiversion) · [官网下载](https://melodifymc.cc.cd/) · [MC 百科](https://www.mcmod.cn/class/31323.html) | 装客户端 Mod，播放器里点歌、看词、建房进房 |
| **服主** | 本仓库 [安装](#安装) | 装服务端组件，开全服房间 |
| **其他 Mod 作者** | [docs/接入指南.md](docs/接入指南.md) | 按开放的线协议把你的客户端接入房间 —— 两个已验证实现可直接对照 |

</div>

---

## 这是什么

服主把 mpsync 装进服务器，玩家就能在游戏里**开一个全服共享的听歌房间**：

- 房主点歌，全服（或全房）同一时刻听到同一首 —— 进度卡到位，不会有人快有人慢
- 成员自己排队点歌、投票切歌，房主可以任命管理员帮忙管
- **不限音乐平台** —— 客户端能放的平台，房间就能放：网易云、QQ 音乐、酷狗、汽水、YouTube Music、B 站……
- **会员福利全房共享**：房间里有会员的人放歌，其他人听到的就是完整版 —— 因为音频链接是从解析者自己的客户端发出的，服务器从头到尾不碰任何音乐平台，**也碰不到任何人的账号**

> **给服主的一句话**：选择与服务器游戏版本和平台对应的 jar。Fabric / Forge / NeoForge 放 `mods/`，Paper 放 `plugins/`，原版服务端用 Java Agent 启动参数加载。玩家侧需要装 [MuoniumPlayer](https://github.com/zihe114514/MuoniumPlayer-multiversion) 客户端 Mod。

---

## 三件套：三个仓库怎么配合

一起听由三个开源项目组成，各管一段（速查表见顶部「生态索引」，这里是细节）：

| 仓库 | 角色 | 谁需要装 |
| --- | --- | --- |
| **[MuoniumPlayer](https://github.com/zihe114514/MuoniumPlayer-multiversion)** | 玩家侧的音乐播放器 Mod：点歌、排队、投票、歌词、下载全在这里 | 每个想听歌/点歌的玩家 |
| **本仓库（mpsync）** | 服务端的房间引擎：建房、成员、队列、全房时钟同步 | 只有「服务器房间」模式需要服主装 |
| **官网 [melodifymc.cc.cd](https://melodifymc.cc.cd/)** | 下载入口、更新日志与公告 | — |
| **MC 百科 [class/31323](https://www.mcmod.cn/class/31323.html)** | 中文介绍、下载镜像与评论区 | — |

**没有服务器权限？** 不需要 mpsync 也能一起听 —— MuoniumPlayer 自带「直连房间」和「局域网房间」两种模式，房主发一条邀请串，好友粘贴即进，什么都不用装（见客户端 README 的「一起听」一节）。

---

## 安装

1. **认准你的服务器**：选择对应的游戏版本和平台（Fabric / Forge / NeoForge / Paper / 原版 Java Agent）。
2. **取对应 jar**：基本名称为 `mpsync-<游戏版本>-<平台>-<mpsync版本>.jar`（本仓库 Release 会加 `<模块>__` 前缀）；可从 [官网](https://melodifymc.cc.cd/)、MC 百科或本仓库的 Release 查找。
3. **安装并启动**：Fabric / Forge / NeoForge 放 `mods/`，Paper 放 `plugins/`；原版使用下面的 Java Agent 启动参数。

原版 Java Agent 放在服务器可访问的位置，并在 `-jar` **之前**指定它，例如：

```sh
java -javaagent:mpsync-1.21.1-javaagent-1.4.2.jar -jar server.jar nogui
```

将文件名换成实际下载的 jar（包括 Release 附带的模块前缀）。1.20.1 需要 Java 17 或更高版本，1.21.x 需要 Java 21 或更高版本，26.x 需要 Java 25 或更高版本。Agent 目前针对**对应版本的 Mojang 原版专用服务器**；Fabric、Forge、NeoForge、Paper 等服务端请使用对应平台的构建，Agent 与它们的兼容性尚未验证。不要把 Agent jar 放进 `mods/` 或 `plugins/` 当作插件加载。

启动后服务器控制台看到 `MusicParty ready` 即就绪。玩家在游戏内按 MuoniumPlayer 的说明加入或创建房间。

### 玩家怎么用：全在播放器界面里

装好 [MuoniumPlayer](https://github.com/zihe114514/MuoniumPlayer-multiversion) 客户端后，一起听**不需要记任何指令** —— 播放器界面就是完整的房间操作台：

- **建房 / 进房**：播放器侧栏点「一起听」，选服务器房间，建房发邀请或直接加入
- **点歌**：搜索到的歌右键「投到房间」，或从歌单整张灌进队列；队列支持三档优先级与插队
- **管理**：投票切歌（过半）、投票踢人（80%）、房主任命管理员、转让房主 —— 全是界面按钮
- **听歌体验不打折**：房间里的歌照常有逐字歌词、封面、下载、Automix 无缝切歌，跟自己听一模一样
- **个人暂停**：你可以暂停自己这边的播放去处理别的事，不影响房间其他人

### 指令（备用，各平台共用）

<!-- 绝大多数操作在播放器界面完成；指令主要用于服务器控制台和没有客户端界面的场景： -->
绝大多数操作在播放器界面完成；指令主要用于没有客户端界面的场景：
<!-- 修正：目前全平台均没有实现控制台 -->

```
/mp create [房名]         创建房间
/mp join <邀请码>         加入房间
/mp invite <玩家>         邀请玩家
/mp leave | /mp disband   离开 | 解散（房主）
/mp admin <玩家> | /mp unadmin <玩家>   授予/撤销管理员
/mp transfer <玩家> | /mp kick <玩家>   转让房主 | 移出成员
/mp pause | resume | skip | prev | clear
/mp play <序号> | /mp seek <秒> | /mp repeat <off|one|all>
/mp info | /mp queue      房间信息 | 一起听歌单
/mp list                  查看全部房间（OP）
```

### 常用配置 `config/mpsync.json`

首次启动自动生成全部默认值，多数服主**什么都不用改**。想收窄时最常用的两条：

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `allowedSources` | `[]`（不限平台） | 只想放某些平台就填平台名，如 `["netease","qq"]` |
| `minStreamDurationRatio` | 0.8 | 防试听片段混进房间：链接实际时长低于歌曲时长的这个比例即拒绝 |

完整配置项与调优说明见仓库内文档与配置文件注释。

### 支持的游戏版本

7 个 Minecraft 版本 × 5 种服务端目标 = **35 个构建**，按服务器版本和类型选择：

| Minecraft | Fabric | Forge | NeoForge | Paper | 原版 Java Agent |
| --- | :-: | :-: | :-: | :-: | :-: |
| 1.20.1 | ✅ | ✅ | ✅ | ✅ | ✅ |
| 1.21.1 | ✅ | ✅ | ✅ | ✅ | ✅ |
| 1.21.10 | ✅ | ✅ | ✅ | ✅ | ✅ |
| 1.21.11 | ✅ | ✅ | ✅ | ✅ | ✅ |
| 26.1.2 | ✅ | ✅ | ✅ | ✅ | ✅ |
| 26.2 | ✅ | ✅ | ✅ | ✅ | ✅ |
| 26.3 | ✅ | ✅ | ✅ | ✅ | ✅ |

> **Folia 也能跑**：Paper 那一列的 jar 同时支持 Folia（`folia-supported` 已声明，引擎跑在全局区域线程上），同一个 jar 不用换，丢 `plugins/` 即装。

> **跨版本同房**：服务器是 1.21.1，1.20.1 的玩家照样进房听歌 —— 房间协议跨版本逐字节兼容，ViaVersion 玩家也能正常加入。

### 隐私与安全

- 服务器**不访问任何音乐平台**、不存储任何账号凭据：播放链接由点歌者自己的客户端解析后上传，服务器只做校验与转发
- 非会员解析出的试听片段会被自动拦下（链接时长与歌曲时长不符），**不会让全房听半截歌**
- 想限制解析来源或平台，`allowedSources` / `allowedHosts` 两条配置即可

---

## 给其他 Mod / 整合包作者：接入一起听

顶部「生态索引」里那一份 [docs/接入指南.md](docs/接入指南.md) 就是为你准备的：线协议、通道注册、时钟同步策略、最小接入清单，两个已验证实现（MuoniumPlayer 与 Melodify）可直接对照。

---

## 项目结构与开发者信息

本节面向参与开发与自行构建的人。

```
core/              与平台无关的房间引擎 + 线协议（只依赖 gson）
core-test/         core 单元测试 + 「core 不许碰 Minecraft」编译期守卫
fabric-common / paper-common / forge-common / neoforge-common / javaagent-common   各平台适配层
<版本><平台>/       35 个构建工程（版本 × 平台），共用同一份 core 源码
build-all.ps1      构建驱动：自检 -> core 测试 -> 35 模块 -> 产物汇总
check-repo.ps1     仓库自检（模块矩阵 / 版本一致 / core 平台无关性）
```

架构核心：**core 一行 Minecraft 都不碰**（跨版本共用的前提），且有测试守卫；房间协议跨版本逐字节兼容，这是「跨版本同房」的根基。本地批量构建可用 `MPSYNC_JDK17`、`MPSYNC_JDK21`、`MPSYNC_JDK25` 指定对应 JDK。构建环境要求、JDK 映射、ForgeGradle 7 超时处理、编码与权限位约定等细节见 `build-all.ps1` / `check-repo.ps1` 头注释与 `docs/`。

### CI

`.github/workflows/` 下三个 workflow，分工是「便宜的常驻、贵的按需」：

| workflow | 何时跑 | 做什么 |
| --- | --- | --- |
| `core-test` | 每次 push / PR | core 单元测试 + 平台 import 静态检查，秒级出结果 |
| `build-modules` | 每次 push / PR | **全量 35 个模块并行构建**（包括 Agent 入口检查），fail-fast 关掉：一次看到所有失败项 |
| `release` | 手动触发 | 35 个模块构建后汇成 `<模块>__<jar>` 并挂到 GitHub Release |

全量门禁存在的理由与本地 `build-all.ps1` 同一条：**core 与各 `*-common` 不是 Gradle 依赖，而是被各模块 `srcDirs` 按值拷进自己的 jar**，所以「core 改了、某个加载器编不过」在没有模块构建的地方根本看不出来（`core-test` 只编 core + gson）。真正的风险面是同一份 core 编进 35 套不同年代的工具链 —— Yarn 名与官方名（26.x）、loom 1.7.4 与 1.17、FG6 与 FG7、release 17 与 25，只有各自真编一遍才能确认。

发版刻意留成手动（打 tag 不自动发布）：自动发版时某个模块若恰好因上游抽风编不过，对外就成了「一半新一半旧」的产物，而不同 core 版本的 jar 混在同一个房间里通信正是本项目最怕的事。发版前先看本机 `build-all.ps1` 的结论，再在 Actions 里跑一遍跨环境复核。

### 配套项目

- **[MuoniumPlayer](https://github.com/zihe114514/MuoniumPlayer-multiversion)**：玩家侧客户端（本协议的第一个完整实现）
- **Melodify / modernMP**：同协议的姊妹客户端，可与 MuoniumPlayer 玩家同房互听

---

## 许可

本项目以 **Apache-2.0** 开源 —— 允许自由使用、修改与再分发。Minecraft 是 Mojang AB 的商标，本项目与 Mojang AB 及各音乐平台无隶属关系。
