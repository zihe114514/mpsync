# MusicParty / mpsync — 服务端一起听

服务端托管的「一起听」房间：房主邀请成员进房，同一首歌在所有人客户端上同时播放，
房员排队点歌，房主可以授予管理员（职权与房主完全相同）。播放源只支持网易云。

服务端**从不**访问网易云：点歌方在自己客户端解析分享链接与取流链接，
只把「歌曲 ID + 元数据 + 链接」上传，服务端负责校验、转发与全房时钟。
因此一条会员账号解析出的完整音频链接，房间里的非会员成员也能听到同一条流；
反过来，非会员解析出的试听片段会被服务端按「链接实际时长 < 歌曲时长」拒绝。

## 目录结构

```
core/            与平台无关的房间引擎 + 线协议（只依赖 gson）
fabric-common/   Yarn 命名的 Fabric 适配层（1.20.1 / 1.21.1 / 1.21.11 共用）
paper-common/    Bukkit 适配层（所有 Paper 版本共用）
forge-common/    Forge 适配层（1.20.1 / 1.21.1 / 1.21.11 共用；26.2 因 API 差异有独立副本）
neoforge-common/ NeoForge 适配层（各版本共用接口）

1.20.1fabric/   1.21.1fabric/   1.21.11fabric/   26.2fabric/
1.20.1paper/    1.21.1paper/    1.21.11paper/    26.2paper/
1.20.1forge/    1.21.1forge/    1.21.11forge/    26.2forge/
1.20.1neoforge/ 1.21.1neoforge/ 1.21.11neoforge/ 26.2neoforge/

服务端/          最早那份 1.20.1 Fabric 工程（已被 1.20.1fabric 取代，仅作历史保留）
```

各工程独立（各有 gradlew），通过 build.gradle 里的

```gradle
sourceSets.main.java.srcDirs += ['../core/src/main/java', '../fabric-common/src/main/java']
```

共用同一份 `core` 源码，因此房间逻辑只有一份实现；平台差异只留在
`MpNet` / `MpPayloads` / `*Platform` 这几个薄适配文件里（Forge/NeoForge 同理）。

## 构建

| 工程 | 构建 JDK | 产物 |
| --- | --- | --- |
| 1.20.1fabric | JDK 17（`C:\Program Files\Java\jdk-17`） | `build/libs/mpsync-1.20.1-fabric-1.1.0.jar` |
| 1.21.1fabric / 1.21.11fabric | JDK 21+（本机用 JDK 25 编到 release 21） | `mpsync-<ver>-fabric-1.1.0.jar` |
| 26.2fabric | JDK 25 | `mpsync-26.2-fabric-1.1.0.jar` |
| \*paper | 同上（release 17 / 21 / 25） | `mpsync-<ver>-paper-1.1.0.jar` |

```powershell
$env:JAVA_HOME='C:\Users\24914\.gradle\jdks\eclipse_adoptium-25-amd64-windows\jdk-25.0.4.1+1'
cd 'F:\donked\新建文件夹 (3)\1.21.1fabric'; .\gradlew.bat build --console=plain
```

仓库注意事项（本机实测）：Maven Central 直连与走 7890 代理都不稳，已统一改写到
`https://maven.aliyun.com/repository/public`；`repo.papermc.io` 反过来只能**直连**
（走 7890 代理会失败）；`maven.fabricmc.net` 放在 `pluginManagement` 里。

## 跨版本

**服务器核心是 1.21.1，允许 1.20.1 的客户端进来，功能不受影响。** 实现上靠三点：

1. `core/net/Proto` 自己写 varint 分帧，不借用任何 Minecraft 的缓冲辅助方法，
   所以 Fabric / Paper、1.20.1 / 26.2 产出的帧**逐字节相同**；
2. 一帧就是一个 JSON 对象，未知字段忽略、新字段追加，两侧可以各自升级；
3. 经协议转换器（如 ViaVersion）进来的客户端有时会漏掉通道声明，
   `Net.markCapable` 把「给我们发过帧的玩家」直接记为可达，绕过 `canSend` 的误判。

## 指令（Fabric 与 Paper 完全一致）

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
  网易云查询（`MpNetease`）与分享链接解析（`MpLink`）；
* 各工程 `src/main/java/com/muoniumplayer/party/net/` —— 版本相关的通道注册
  （1.20.1 裸 `PacketByteBuf`；1.21.1 / 1.21.11 的 `CustomPayload`；26.2 的官方名 `CustomPacketPayload`）
  以及玩家 UUID / 聊天；
* 播放不另开通路：`MpPlayback` 复用 `CloudMusic.play(...)`，房间下发的链接通过
  `Music#partyStreamUrl` 注入取流链路，因此下载、容器识别、AAC 转码、封面、歌词、
  灵动岛全部照常工作；
* 进房后本地传输控制被接管：起播/「下一首播放」改成点歌，房主与管理员的
  播放暂停/上下曲/进度/播放模式键变成房间指令，普通成员会收到「只有房主或管理员可以…」的提示。

## 尚未做的事

* 全部八个服务端工程都已构建成功，MuoniumPlayer 四个工程都已 `compileJava` 通过，
  但**没有做过游戏内实机联调**（同一个房间里 Melodify + MuoniumPlayer 同时在听）。
* `服务端/` 与 `1.20.1fabric/` 内容重复，确认后可以删掉前者。
