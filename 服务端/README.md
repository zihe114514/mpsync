# MusicParty (mpsync) — 服务端一起听

Fabric **服务端** mod（Minecraft 1.20.1 / Fabric Loader 0.19.3 / Fabric API 0.92.11）。
为客户端音乐播放器 mod（Melodify / modernMP）提供**服务器托管的「一起听」房间**：
房主与管理员控制播放、房员排队点歌，服务器负责统一时钟与歌单广播。

**仅支持网易云音乐播放源。** 服务器不接触音频、不请求网易云接口：
链接解析与播放地址获取全部在客户端完成（客户端有登录态），服务器只校验来源并转发
`songId / 歌名 / 歌手 / 专辑 / 封面 / 时长 / 播放地址`。

## 构建与安装

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-17'
.\gradlew.bat build
```

产物：`build/libs/mpsync-1.0.0.jar` → 放进服务端 `mods/`（同时需要 Fabric API）。
声明为 `environment: "*"`，因此单人/联机主机（LAN）也会加载，但只注册服务端逻辑。

首次启动生成 `config/mpsync.json`：

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `maxRoomMembers` | 32 | 单房间人数上限（0 = 不限） |
| `maxQueueSize` | 200 | 房间歌单上限 |
| `maxQueuePerMember` | 5 | 普通成员同时排队数上限（管理员不受限） |
| `maxRooms` | 32 | 服务器同时存在的房间数 |
| `timeBroadcastTicks` | 40 | 时钟广播间隔（tick） |
| `assumedUrlLifetimeMs` | 1200000 | 客户端未报告有效期时假定的播放地址寿命 |
| `urlWaitTimeoutMs` | 10000 | 等待重新解析播放地址的超时（超时跳过该曲） |
| `emptyRoomGraceMs` | 300000 | 全员离线多久后自动关闭房间 |
| `membersCanEnqueue` | true | 是否允许非管理员点歌 |
| `requesterCanRemoveOwn` | true | 点歌人可否删除自己的排队歌曲 |
| `minStreamDurationRatio` | 0.8 | 播放地址实际时长 / 歌曲时长 的最低比例（拦截试听片段，0 = 关闭） |
| `allowedHosts` | 网易云域名 | 播放/封面地址的域名白名单 |

## 权限模型

| 角色 | 权限 |
| --- | --- |
| 房主 OWNER | 全部权限 + 解散房间、转让房主、撤销管理员 |
| 管理员 ADMIN | 与房主相同的播放/歌单/成员权限（切歌、暂停、拖动、删歌、清空、踢人、授予管理员） |
| 成员 MEMBER | 点歌排队、删除自己点的歌 |

管理员权限与房主一致（按需求「管理员有和房主一样的职权」），仅房间生命周期动作
（解散 / 转让 / 撤销管理员）保留给房主，否则管理员可以夺走房间。

## 指令（`/mp`）

```
/mp create [房名]              创建房间（返回 6 位邀请码）
/mp join <邀请码>              加入房间
/mp invite <玩家>              邀请（对方收到邀请码，且可免码加入）
/mp leave | /mp disband        离开 | 解散（房主）
/mp admin <玩家> | /mp unadmin <玩家>
/mp transfer <玩家> | /mp kick <玩家>
/mp pause | resume | skip | prev | clear
/mp play <序号> | /mp seek <秒> | /mp repeat <off|one|all>
/mp info | /mp queue           房间信息 | 一起听歌单
/mp list                       所有房间（权限等级 2）
```

点歌需要客户端 mod（解析链接 + 取播放地址）：`/melodify party` 打开面板，或
`/melodify party request <网易云链接或ID>`。

## 协议（v1）

两条 plugin channel，载荷为「varint 长度 + UTF-8 JSON」：

- `mpsync:c2s` 客户端 → 服务器
- `mpsync:s2c` 服务器 → 客户端

服务端定义见 `yun.shan.mpsync.net.Proto`，客户端镜像见
`yun.shan.musicplayer.music.party.PartyProto`。JSON 而非二进制打包是刻意的：两个 mod
分别升级，未知字段必须可忽略、新字段必须可增量添加。

### C2S

| op | 载荷 | 权限 |
| --- | --- | --- |
| `hello` | `proto`, `client` | 任意 |
| `create_room` | `name?` | 任意 |
| `join` | `code` | 任意（邀请码或被邀请） |
| `leave` / `disband` | — | 成员 / 房主 |
| `invite` | `target` | 成员 |
| `enqueue` | `track`, `next?` | 成员（点歌）；`next=true` 仅房主/管理员生效为插播队首，成员按追加处理 |
| `play_now` | `track` | 房主/管理员（自由切歌） |
| `play_index` | `entryId` 或 `index` | 房主/管理员 |
| `remove` | `entryId` | 房主/管理员 或 点歌本人 |
| `move` | `entryId`, `to` | 房主/管理员 |
| `clear_queue` / `pause` / `resume` / `next` / `prev` | — | 房主/管理员 |
| `seek` | `pos` (ms) | 房主/管理员 |
| `repeat` | `mode` = off/one/all | 房主/管理员 |
| `set_role` | `target`, `role` = admin/member | 房主/管理员 |
| `kick` / `transfer` | `target` | 房主/管理员（转让仅房主） |
| `progress` | `pos`, `playing` | 心跳（5s） |
| `sync` | — | 请求完整快照 |
| `url_supply` | `songId`, `url`, `urlExpiresAt` | 回应 `url_needed` |

`track` 对象（服务器只接受 `source = "netease"`、数字 `songId`、白名单域名的 `url`）：

```json
{ "source": "netease", "songId": "1901371647", "name": "…", "artist": "…",
  "album": "…", "cover": "https://p1.music.126.net/…", "duration": 231000,
  "url": "https://m801.music.126.net/…mp3", "urlExpiresAt": 1770000000000,
  "urlDuration": 231000 }
```

`url` 必须是**已解析的 CDN 直链**（不是 `music.163.com/song?id=` 分享页）：直链带签名、
与账号无关，房间里没有 VIP 的成员也能播放。`urlDuration` 是该直链实际提供的音频时长，
服务器用它拦截试听片段——详见下方「VIP 与试听片段」。

### S2C

| op | 说明 |
| --- | --- |
| `hello` | `proto`, `membersCanEnqueue`, `maxQueuePerMember` |
| `room_state` | 完整快照：`room`(id/code/name/owner/repeat/listeners/members/queue/history) + `playback`(state/pos/serverTime/seq/track) |
| `queue` | 仅歌单 + 历史（一起听歌单刷新） |
| `play` | `track`（含播放地址）, `pos`, `serverTime`, `seq`, `playing`, `requesterName` |
| `pause` / `resume` / `seek` | `pos`, `serverTime`, `seq` |
| `stop` | 队列播完 / 离开 / 解散 |
| `time` | 权威时钟：`pos`, `duration`, `playing`, `listeners`, `serverTime` |
| `invite` | `roomId`, `code`, `roomName`, `fromName` |
| `url_needed` | `songId` — 请客户端用自己的会话重新取播放地址 |
| `notice` / `error` | 文案（服务端已本地化） |
| `left` | `reason` |

### 时钟模型

服务器持有位置：`pos = now - startEpoch`（播放中）或 `pausedPos`（暂停）。
每帧带 `serverTime`，客户端用「历史最小 `localNow - serverTime`」作为时钟偏移，
把超出该最小值的部分当作本帧额外延迟补偿进位置；漂移 > 1.5s 才硬 seek，否则只重锚。

### 播放地址过期

网易云播放地址有签名有效期。曲目开始播放时若地址不新鲜，房间**不播放**，而是向
点歌人（其次管理员，再其次全员）发 `url_needed` 并等待 `urlWaitTimeoutMs`，拿到后
再广播 `play`；超时则跳过该曲。播放中若地址即将过期，会静默请求一次刷新，仅用于
让中途加入的玩家拿到可用链接（不会打断正在播放的成员）。

### VIP 与试听片段

房间对所有成员只广播**同一条已解析的直链**，所以直链的来源账号决定了所有人听到的内容：

- 非 VIP 账号解析 VIP 歌曲，网易云会返回空地址或约 30 秒试听片段。若把它推给房间，
  全员都只能听到片段。
- 因此点歌方在上传前先自查：空地址 → 提示「可能需要 VIP 或登录」；实际时长明显短于
  歌曲时长 → 提示「解析到的只是试听片段」并取消点歌。
- 服务器再校验一次（`minStreamDurationRatio`，默认 0.8）：不合格的 `track` 被拒绝，
  不合格的 `url_supply` 被忽略且房间继续等待，好让能解析完整曲目的成员来应答。
- 两侧的时长都直接来自网易云返回（歌曲时长 vs. 直链实际时长），不需要额外请求；
  时长未知时放行，以兼容不上报 `urlDuration` 的旧客户端。

## 房间内的播放器行为

加入房间后播放器完全交由房间驱动:

- 每一行歌曲右侧出现 **「下一曲播放」** 按钮 —— 房间内唯一的入队入口(`enqueue`)。
- 房主/管理员的每一行还多一枚 **「立即播放」**(`play_now`)—— 从任意列表直接切歌;
  一起听歌单里点某一行同样是跳到该曲(`play_index`)。
- 直接点歌曲行(主动播放)无效:`playQueue` 只更新浏览列表并提示"请点「下一曲播放」",
  `playSong` 静默拦截(曲末自动续播、automix 也走这里,避免有人偏离房间进度)。
- 本地文件行不显示按钮(房间只接受网易云曲目),点击同样被拦截。
- 播放器自带的播放/暂停、上一首/下一首、进度拖动经 `PartyClient.intercept*`:
  房主/管理员的操作变成房间指令,普通成员被拒绝并提示。拦截点在
  `MusicPlayerGui.playNext()/playPrev()` 这一层(不是 `*Public` 包装),所以迷你播放器、
  全屏歌词页、快捷键、系统媒体键(SMTC)按下上一首/下一首都会驱动整个房间,不必打开房间面板;
  曲末自动续播走 `onTrackEnd → playSong`,不经过这里,不会有人的自然曲末把房间整体切歌。
- 循环模式按钮(迷你播放器):房间中改的是**房间**的 `repeat`(off → one → all),
  本地图标随之镜像;普通成员被拒绝并提示。
- 进度调整:迷你播放器与全屏歌词的进度条,房主/管理员可直接拖动,松手时作为房间 `seek`
  广播给所有人;普通成员抓不动进度条并收到提示。房间面板另有进度条与 ±15s 微调。
- 迷你播放器的 一起听 图标:服务器装有本 mod 时打开服务器房间面板,否则回退到网易云官方
  一起听弹窗;在任一房间中时图标高亮。

## 客户端接入（预留接口）

客户端实现位于 `modernMP-master/src/client/java/yun/shan/musicplayer/music/party/`：

| 类 | 作用 |
| --- | --- |
| `PartyProto` | 协议常量与编解码（服务端 `Proto` 的镜像） |
| `PartyClient` | 通道注册、房间快照、时钟、心跳、发送 API、`url_needed` 回应 |
| `PartyBridge` | **播放器接入接口**：`onPlay/onPause/onResume/onSeek/onStop/onTime/onRoomChanged/onNotice/onInvite` + `localPositionMs/localPlaying` |
| `GuiPartyBridge` | 默认实现，driving `MusicPlayerGui` 的 external 播放入口 |
| `PartyScreen` | 一起听面板：房间信息、成员角色、传输控制、**一起听歌单**（含点歌人） |
| `NeteaseLink` | 分享链接 / 短链 / 纯 ID → songId |

替换播放实现只需：

```java
PartyClient.setBridge(myBridge);   // myBridge implements PartyBridge
```

读取一起听歌单（任意 UI 均可）：

```java
if (PartyClient.inRoom()) {
    for (PartyTrack t : PartyClient.queue()) {
        // t.name / t.artist / t.songId / t.durationMs / t.requesterName / t.entryId
    }
}
PartyClient.addListener(this::onRoomChanged);   // 房间/歌单变化回调
```

播放器自带的播放/暂停、上一首/下一首、进度拖动、循环模式会先经过
`PartyClient.intercept*` / `cycleRepeatMode()`：在房间中时房主/管理员的操作变成房间指令，
普通成员被拒绝并提示，避免个人操作把自己和房间拖不同步。

歌词由客户端按 `songId` 自行拉取（服务端不搬运歌词）：先按 id 查，查不到时退化为
「歌名 + 歌手」搜索后再查一次；两次都空时桌面歌词框显示曲名占位，便于区分"拉取失败"
与"浮层没渲染"（`/melodify lyrics diag` 可打印桌面歌词的完整状态）。

设置项 `partySync`（默认开启）可整体关闭本功能。
