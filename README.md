# MusicParty / mpsync — 服务端一起听

服务端托管的「一起听」房间：房主邀请成员进房，同一首歌在所有人客户端上同时播放，
房员排队点歌，房主可以授予管理员（职权与房主完全相同）。

服务端**从不**访问任何音乐平台：点歌方在自己客户端解析分享链接与取流链接，
只把「平台 + 歌曲 ID + 元数据 + 链接」上传，服务端负责校验、转发与全房时钟。
因此一条会员账号解析出的完整音频链接，房间里的非会员成员也能听到同一条流；
反过来，非会员解析出的试听片段会被服务端按「链接实际时长 < 歌曲时长」拒绝。

播放源**不限平台**：服务端既不解析也不取流，平台对它没有技术意义，客户端支持哪个平台，
房间就能放哪个平台（`netease` / `qq` / `kugou` / `gd` 已在协议里留了常量，但不限于它们）。
服主想收窄就填 `allowedSources`。

## 目录结构

```
core/              与平台无关的房间引擎 + 线协议（只依赖 gson）
core-test/         core 的纯 JVM 单元测试，同时是「core 不许碰 Minecraft」的编译期守卫

fabric-common/     Yarn 命名的 Fabric 适配层（1.20.1 / 1.21.1 / 1.21.11 共用）
paper-common/      Bukkit 适配层（所有 Paper 版本共用）
forge-common/      Forge 适配层
neoforge-common/   NeoForge 适配层

<版本><加载器>/    16 个构建工程，版本 × 加载器的笛卡尔积：
                   1.20.1 / 1.21.1 / 1.21.11 / 26.2
                   × fabric / paper / forge / neoforge

build-all.ps1         构建驱动：仓库自检 -> core 单元测试 -> 16 个模块，产物统一汇总
check-repo.ps1        仓库自检：模块矩阵 / 配置口径 / core 平台无关性 / 脚本编码，不联网、秒级
resolve-jdk-hosts.ps1 用 DoH 生成一份 JDK 专用 hosts，绕开 ForgeGradle 7 那个 5 秒硬超时
                      （由 build-all.ps1 自动调用，见下文「ForgeGradle 7 与 5 秒硬超时」）
smoke-runserver.ps1   冒烟测试：起一次 runServer，盯启动日志
mirror-server.ps1     本地 maven 镜像兜底（目前没有任何工程指向它，留作 Mojang 直连不通时的后手）
.github/workflows/    CI：只跑 core-test 与 core 的平台 import 静态检查
```

16 个工程各自独立（各有 gradlew），通过 build.gradle 里的

```gradle
sourceSets.main.java.srcDirs += ['../core/src/main/java', '../fabric-common/src/main/java']
```

共用同一份源码，因此房间逻辑只有一份实现，平台差异只留在
`MpNet` / `MpPayloads` / `*Platform` / `*Commands` 这几个薄适配文件里。

## 架构：为什么一份 core 能跨四个游戏版本

跨版本共用代码通常不靠谱，因为 Minecraft 的 API 每个版本都在改名改签名（Yarn 名与 26.x 的
官方名还不通用）。这里成立的原因很具体：**core 一行 Minecraft 都不碰。**

core 的全部 import 只有四类：`com.google.gson`、`java.*`、`javax.*`、`yun.shan.mpsync` 自己。
里面是房间成员表、点歌队列、`pos = now - startEpoch` 的时钟、varint 分帧——这些东西
1.20.1 和 26.2 没有任何差异。它与宿主之间只有 `MpPlatform` 这一个接口（8 个方法），
跨过这个边界的只有原始字节。

这条不变量现在有守卫：`core-test` 的类路径上**只有 gson**，core 里一旦出现
`net.minecraft.*` / `net.fabricmc.*` / `net.neoforged.*` / `org.bukkit.*` / `com.mojang.*`
就直接编不过（`CorePlatformFreeTest` 会再给一条指明文件行号的错误信息）。

另一条硬约束是**跨版本同房**：服务器核心是 1.21.1，允许 1.20.1 的客户端进来，功能不受影响。
实现上靠三点：

1. `core/net/Proto` 自己写 varint 分帧，不借用任何 Minecraft 的缓冲辅助方法，
   所以 Fabric / Paper / Forge / NeoForge、1.20.1 到 26.2 产出的帧**逐字节相同**；
2. 一帧就是一个 JSON 对象，未知字段忽略、新字段追加，两侧可以各自升级；
3. 经协议转换器（如 ViaVersion）进来的客户端有时会漏掉通道声明，
   `Net.markCapable` 把「给我们发过帧的玩家」直接记为可达，绕过 `canSend` 的误判。

这条约束反过来也说明 core 不能按版本拆：四份 fork 要保持帧逐字节兼容，只能靠人工。
仓库里曾经留着一份反面样本 —— 最早那个不共用 core 的 `服务端/` 工程，它的房间逻辑
一路落后到还在用早已删除的 `NeteaseGuard`，多平台与时钟静默窗一个都没有。
它已于 2026-08-31 移出仓库（归档在 `F:\donked\归档\服务端-旧fork-20260831`，含它自己的
`.git.disabled` 历史），留在这里只是提醒别再开第二份 fork。

## 构建

```powershell
# 全量：仓库自检 + core 单元测试打头，然后 16 个模块，产物汇总到 F:\donked\构建产物\服务端mod-mpsync
powershell -ExecutionPolicy Bypass -File .\build-all.ps1

# 只跑 core 单元测试（几秒）
powershell -ExecutionPolicy Bypass -File .\build-all.ps1 -TestOnly

# 只构建指定模块
powershell -ExecutionPolicy Bypass -File .\build-all.ps1 -Only 1.21.1fabric,1.21.1paper

# 强制重建（jar 时间戳会刷新，用来消掉「产物比 core 源码旧」的告警）
powershell -ExecutionPolicy Bypass -File .\build-all.ps1 -Task 'clean build'

# 只跑仓库自检（不联网、不起 Gradle，一两秒）
powershell -ExecutionPolicy Bypass -File .\check-repo.ps1
```

守护进程 JDK 由脚本按模块指定，**工程里刻意不钉 `org.gradle.java.home`**：那会把某台机器的
绝对路径写进仓库（modernMP 曾被一条失效路径卡住；forge / neoforge 八个模块从云端构建机带回来的
`org.gradle.java.installations.paths` 是同样的毛病，已经删掉）。

| 模块 | 守护进程 JDK | 为什么 |
| --- | --- | --- |
| core-test | 17 | 纯 JVM，release 17 = core 的目标下限 |
| 1.20.1 四个 | 17 | Gradle 8.8 + loom 1.7.4 接不了 25；release 17 |
| 1.21.1 / 1.21.11 fabric | 25 | loom 1.17-SNAPSHOT 要新 JVM，编译目标仍是 release 21 |
| 1.21.1 / 1.21.11 的 paper / forge / neoforge | 21 | release 21；ForgeGradle 6（1.21.1forge）只跟到 21 |
| 26.2 四个 | 25 | release 25，必须真有一个 JDK 25 |

仓库注意事项（本机实测）：

* Maven Central 直连与走 7890 代理都不稳，各工程统一改写到 `https://maven.aliyun.com/repository/public`；
* `repo.papermc.io` 反过来只能**直连**（走 7890 代理会失败）；
* `maven.fabricmc.net` 放在 `pluginManagement` 里；
* `com.mojang` 走官方 `libraries.minecraft.net`（直连可用且快），BMCLAPI 只作兜底且限定
  `includeGroup 'com.mojang'`——它的 pom 拿得到，但 jar 会 302 到连不上的 CDN 节点，
  而 Gradle 对「模块找到了、artifact 下载失败」不会回退到下一个仓库，所以顺序是决定性的；
* Minecraft 版本清单用插件默认的官方 piston-meta，不要改到 BMCLAPI（它会把 client.jar 的
  地址一起改写到那组连不上的 CDN）。

### ForgeGradle 7 与 5 秒硬超时

forge 四个模块**不是同一套构建插件**，这条闸由 `check-repo.ps1` 守着，别顺手统一：

| 模块 | ForgeGradle | Gradle |
| --- | --- | --- |
| 1.20.1forge / 1.21.1forge | 6.0.x | 8.x |
| 1.21.11forge / 26.2forge | 7.0.x | 9.6.1 |

1.21.11forge 原先也钉着 FG6，构建固定死在
`java.util.zip.ZipException: duplicate entry: mcp/client/Start.class`：FG6 会把 mcp_config 的
inject 源码编译后再塞进 srg jar，而 1.21.11 的 `binpatched.jar` 里本来就带着 `mcp/client/Start.class`
（1.21.1 的没有）——所以这不是缓存脏，清缓存没用。Forge 61.x 的官方 MDK 用的是 FG `[7.0.3,8)`，
改过来才对。

FG7 在配置阶段会 fork 一个 Minecraft Mavenizer 进程去拉版本清单、client/server jar、依赖和
userdev，而它的下载器把连接超时**硬编码成 5 秒**且没有开关。本机是 Clash TUN + fake-ip，
`launchermeta.mojang.com` / `piston-data` / `libraries.minecraft.net` / `maven.minecraftforge.net`
的 TLS 握手实测 5~6.3 秒（改走 7890 显式代理一样慢），正好压在线上，于是每次都死在
`downloadLauncherManifest`；而直连这些域名的**真实 IP** 只要 0.6~1.2 秒。

`resolve-jdk-hosts.ps1` 因此用 DoH 查出真实 IP 写成一份 JDK 专用 hosts，`build-all.ps1` 只对
这两个模块通过 `JAVA_TOOL_OPTIONS=-Djdk.net.hosts.file=...` 注入，并强制 `--no-daemon`
（fork 出来的进程继承的是守护进程的环境，复用旧守护进程这条注入就没了）。这个开关是**独占**的，
hosts 列表缺哪个主机就会 UnknownHost，加新仓库时记得同步那份列表。

另外这两个模块**不能留 BMCLAPI 仓库**：它的 pom 会 302 到动态主机，Gradle 一失败就把整个仓库
禁掉（`Repository maven2 is disabled due to earlier error`），连 `authlib` / `jtracy` 都解析不出来。
它们改用 FG7 自带的 `fg.minecraftLibsMaven`。

冷启动开销：26.2forge 约 8 分钟、1.21.11forge 约 13 分钟（含反编译），之后缓存命中几十秒。

### 仓库自检

`build-all.ps1` 在动 Gradle 之前先跑 `check-repo.ps1`（不通过就中止，确实要跳过用 `-SkipChecks`）。
它守的是这类「能构建成功、但发出去的十六个 jar 不是一套」的问题：

* 模块矩阵就是 4 个版本 x 4 个平台，多一个少一个都报（`build-all.ps1` 不会构建没登记的目录）；
* `minecraft_version` 必须与目录名一致，`archives_base_name` 必须是 `mpsync-<mc>-<loader>`
  —— 十六个 jar 平铺在一个目录里，产物名是发版时唯一的辨识依据；
* 版本号必须全仓一致（fabric/forge/neoforge 叫 `mod_version`，paper 叫 `plugin_version`，
  名字随平台模板，值不许漂）；
* `mod_id` 一律 `mpsync`；
* `gradle.properties` 里**值**必须是纯 ASCII —— Gradle 用 ISO-8859-1 读它，非 ASCII 会以乱码
  展开进 `mods.toml` / `fabric.mod.json`（实测发生过，`mod_description` 里一个破折号就够了）；
* 每个模块的 `build.gradle` 必须把 `../core/src/main/java` 加进 `srcDirs`（core 只许有一份实现）；
* 不许出现 `org.gradle.java.installations.paths`、`C:\Users\user`、`127.0.0.1:18765`、
  `org.gradle.java.home=` —— 都是从云端构建机带回来的绝对路径/失效镜像；
* core 里不许出现 `net.minecraft` / `net.fabricmc` / `net.minecraftforge` / `net.neoforged` /
  `org.bukkit` / `io.papermc` / `cpw.mods` 的 import；
* 根目录 `.ps1` 必须带 UTF-8 BOM，`gradlew` 在 git 索引里必须是 `100755`。

CI（`.github/workflows/core-test.yml`）只做两件对应的事：同一条 core 平台 import 静态检查，
以及 `core-test` 的 44 个单元测试。**故意不在 CI 上构建那十六个模块** —— 要下 Minecraft 客户端 jar、
MCP/NeoForm 映射和各家 MDK，既慢又脆，而它们编进去的 core 本来就是同一份源码。

### 发版纪律：改完 core 必须全量重建

core 是被 `srcDirs` **按值拷进**每个 jar 的，16 个模块之间没有任何 Gradle 依赖关系。
只重建一部分模块，剩下那些 jar 里就还是旧 core，而它们会在同一个房间里通信——这种
「新旧混跑」实测发生过，且不会有任何报错。`build-all.ps1` 结束时会拿共用源码的最后修改时间
和产物目录里每个 jar 的时间比一遍，比源码旧的会被列出来并以退出码 2 提示。

### 冒烟测试

```powershell
powershell -ExecutionPolicy Bypass -File .\smoke-runserver.ps1 -Modules 1.21.1fabric
```

起一次 `runServer`，盯日志里的 `MusicParty ready` / `Done (`。单元测试证明不了
「这个 jar 能被这个版本的加载器装上」——通道注册、事件挂点、`mods.toml` / `fabric.mod.json`
只有真的起一次服务器才会暴露。paper 那四个是普通 java 工程，没有 `runServer`。

## 仓库约定：编码、换行、权限位

这三件事在这个仓里都踩过，所以写进 `.editorconfig` / `.gitattributes` 随仓库走，并由
`check-repo.ps1` 复查：

| 文件 | 约定 | 为什么 |
| --- | --- | --- |
| `*.ps1` | UTF-8 **BOM** + CRLF | `powershell.exe` 5.1 会把没有 BOM 的 `.ps1` 按本机 ANSI(GBK) 读，脚本里的中文注释和全角括号直接让解析器崩 |
| `gradle.properties` | 文件存 UTF-8，**值只许 ASCII** | Gradle 按 ISO-8859-1 读，值里的非 ASCII 会变成乱码展开进 mod 元数据；注释行整行被跳过，无所谓 |
| `gradlew` | LF + git 索引里 `100755` | 仓库在 Windows 上开发，但 clone 到 Linux / CI 上要能直接 `./gradlew` |
| 其余文本 | LF | `*.bat` / `*.cmd` / `*.ps1` 例外，检出成 CRLF |

`.idea/` 是被忽略的（里面 17 个 Gradle 工程的 JVM 绑定用的是本机 SDK 名字，换机器没有意义），
所以 IDEA 侧的配置不入库：新机器上打开后需要自己把每个 Gradle 工程的 Gradle JVM 按上面那张
JDK 映射表点一遍。

## 时钟同步与防抖

服务端持有权威位置：`pos = now - startEpoch`（播放中）或 `pausedPos`（暂停）。每帧都带
`serverTime`，客户端据此补单向延迟，而不是相信到达时间；`seq` 单调递增，客户端用它丢弃乱序命令。

实测在网络不佳时出现过「进度来回跳、根本听不成」。成因是正反馈：命令帧（换歌／暂停／恢复／
拖进度）刚下发的那几秒，全房都在开流，位置天然是散的；这时候打一发心跳，每个还没缓冲完的
客户端都算出一个大偏差 → 集体 seek → seek 又要重新开流 → 下一发心跳还是同样的局面。

两侧一起改：

* **服务端**：命令帧照旧立刻下发，但之后 `clockGraceMs`（默认 4000）内**不发时钟心跳**；
  `waitingUrl` 期间同样不发。心跳帧带 `heartbeat: true` 明示「这不是命令」。
* **客户端**：三档判定——`softDriftMs`（2500）以内不管；软硬阈值之间也不动播放头；
  只有超过 `hardDriftMs`（6000）、**连续** `driftConfirmHits`（2）次心跳都超、
  且距上次硬跳已过 `resyncCooldownMs`（10000），才真的跳一次。命令帧不走这套判定，永远立刻执行。
* 阈值由服务端在 `hello` 里下发（`clockGraceMs` / `softDriftMs` / `hardDriftMs` /
  `resyncCooldownMs` / `driftConfirmHits` / `timeBroadcastTicks`）。**这是让不同客户端口径一致的
  关键**：modernMP 与 MuoniumPlayer、各游戏版本全用服务端这一套数，不会出现同一发心跳
  有人跳有人不跳、互相拉扯。老客户端读不到就用自带默认值，与服务端默认值相同。

客户端侧实现分别在 `MpSyncPolicy`（MuoniumPlayer）和 `PartySyncPolicy`（modernMP）。

## 配置 `config/mpsync.json`

首次启动写出全部默认值。几个容易踩的：

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `allowedSources` | `[]` | 留空 = 不限平台。填了就只放这些 |
| `allowedHosts` | `[]` | 留空 = 只做公网校验（仍会挡回环／私网／链路本地） |
| `minStreamDurationRatio` | 0.8 | 链接实际时长低于歌曲时长这个比例即判为试听片段 |
| `urlWaitTimeoutMs` | 20000 | 等别人重新解析链接的上限。含一次跨机器往返，下限兜 12 秒 |
| `clockGraceMs` | 4000 | 命令帧之后的时钟静默窗 |
| `softDriftMs` / `hardDriftMs` | 2500 / 6000 | 客户端软／硬偏差阈值，随 hello 下发 |
| `resyncCooldownMs` / `driftConfirmHits` | 10000 / 2 | 两次硬跳的最小间隔／需要连续确认几次 |
| `timeBroadcastTicks` | 40 | 心跳间隔（游戏刻），四个加载器都是每刻驱动一次 |

自相矛盾的配置会在 `load()` 里被兜住（例如 `hardDriftMs` 至少是 `softDriftMs + 1000`），
配置文件坏掉不会拦住服务器启动——默认值赢。

## 指令（四个加载器完全一致）

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

点歌与切歌在播放器界面里操作 —— 需要装支持一起听的客户端 mod（见下）。

## 已接入的客户端播放器

两个项目共用同一套线协议，因此**可以在同一个房间里一起听**：

| 客户端 | 位置 | 接入方式 |
| --- | --- | --- |
| Melodify / modernMP | `F:\donked\modernMP-master` | `music/party/`：完整房间界面、一起听歌单、桌面/全屏歌词、播放器控制栏全部接房间 |
| MuoniumPlayer | `C:\MUSIC`（1.20.1 recode / 1.21.1 / 1.21.11 recode / 26.2） | `mpparty-common/` 共用源码 + 各工程 `party/net/MpNet.java` |

MuoniumPlayer 侧的接入要点（与四个 MC 版本共用同一份源码）：

* `mpparty-common/src/main/java/com/muoniumplayer/party/` —— 协议（`MpProto`）、房间状态
  （`MpRoom` / `MpMember` / `MpTrack`）、客户端引擎（`MpPartyClient`）、播放桥接（`MpPlayback`）、
  对时策略（`MpSyncPolicy`）、平台查询与分享链接解析；
* 各工程 `src/main/java/com/muoniumplayer/party/net/` —— 版本相关的通道注册
  （1.20.1 裸 `PacketByteBuf`；1.21.1 / 1.21.11 的 `CustomPayload`；26.2 的官方名 `CustomPacketPayload`）；
* 播放不另开通路：`MpPlayback` 复用既有播放链路，房间下发的链接注入取流环节，因此下载、
  容器识别、AAC 转码、封面、歌词、灵动岛全部照常工作；
* 进房后本地传输控制被接管：起播/「下一首播放」改成点歌，房主与管理员的
  播放暂停/上下曲/进度/播放模式键变成房间指令，普通成员会收到「只有房主或管理员可以…」的提示。

## 已知缺口

* **16 个模块已在本机全部构建通过（2026-08-31），产物齐全且都比当前 core 源码新。**
  遗留前提是：`1.21.11forge` / `26.2forge` 依赖 `resolve-jdk-hosts.ps1` 生成的 JDK hosts
  才能跑完（原因见上文），换到一个不用 fake-ip 代理的网络环境时这一步可以省掉。
* **没有做过游戏内实机联调**（同一个房间里 Melodify + MuoniumPlayer 同时在听）。
* **适配层还有该收没收的重复：** `1.21.1fabric` 与 `1.21.11fabric` 的 `MpNet` 完全相同；
  `1.21.1neoforge` / `1.21.11neoforge` / `26.2neoforge` 三份 `MpNet` 完全相同；
  `1.21.11forge` 与 `26.2forge` 完全相同；26.2 那三个模块各自重写了一份 Platform + Commands，
  与对应 `*-common` 重叠 46%~63%。该往 `*-common` 收，收完需要逐个模块重新构建验证。
