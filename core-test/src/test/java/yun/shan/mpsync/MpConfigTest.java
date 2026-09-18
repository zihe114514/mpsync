package yun.shan.mpsync;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import yun.shan.mpsync.config.MpConfig;
import yun.shan.mpsync.util.SourceGuard;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MpConfigTest {

    /**
     * 默认值被钉住是有原因的：这几个数同时是**客户端**的默认值
     * （MuoniumPlayer 的 MpSyncPolicy、modernMP 的 PartySyncPolicy）。
     * 老客户端读不到 hello 里的下发值时就用它们自带的默认，两边必须一致，
     * 否则同一房间里会出现有人跳、有人不跳，互相拉扯。
     */
    @Test
    void clockDefaultsMatchTheClientSideDefaults() {
        MpConfig cfg = new MpConfig();
        assertEquals(4000L, cfg.clockGraceMs);
        assertEquals(2500L, cfg.softDriftMs);
        assertEquals(6000L, cfg.hardDriftMs);
        assertEquals(10_000L, cfg.resyncCooldownMs);
        assertEquals(2, cfg.driftConfirmHits);
        assertEquals(40, cfg.timeBroadcastTicks);
    }

    @Test
    void loadClampsSelfContradictoryThresholds(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("mpsync.json");
        Files.writeString(f, """
                {
                  "softDriftMs": 100,
                  "hardDriftMs": 200,
                  "resyncCooldownMs": 10,
                  "driftConfirmHits": 99,
                  "clockGraceMs": 999999,
                  "urlWaitTimeoutMs": 1000,
                  "timeBroadcastTicks": 1
                }
                """, StandardCharsets.UTF_8);

        MpConfig cfg = MpConfig.load(f);
        assertEquals(1000L, cfg.softDriftMs, "软阈值有下限，太小等于每帧都判超标");
        assertTrue(cfg.hardDriftMs >= cfg.softDriftMs + 1000L, "硬档不能被软档吃掉");
        assertTrue(cfg.resyncCooldownMs >= 2000L);
        assertTrue(cfg.driftConfirmHits >= 1 && cfg.driftConfirmHits <= 10);
        assertTrue(cfg.clockGraceMs <= 30_000L, "静默窗封顶，否则等于把心跳关了");
        assertTrue(cfg.urlWaitTimeoutMs >= 12_000L, "低于这个值，向别人要链接在物理上来不及");
        assertTrue(cfg.timeBroadcastTicks >= 5);
    }

    @Test
    void brokenConfigFallsBackToDefaults(@TempDir Path dir) throws IOException {
        // 配置坏了不能把服务器拦在启动之前 —— 默认值赢。
        Path f = dir.resolve("mpsync.json");
        Files.writeString(f, "{ this is not json", StandardCharsets.UTF_8);
        MpConfig cfg = MpConfig.load(f);
        assertEquals(4000L, cfg.clockGraceMs);
    }

    @Test
    void legacyNeteaseOnlyHostListIsUpgradedToUnrestricted(@TempDir Path dir) throws IOException {
        // 旧版写出来的配置里 allowedHosts 只有网易云那几条后缀。它非空，所以不会被当成
        // 「不限制」，升级之后其他平台的直链会一律被拒 —— 看起来像「多平台没生效」。
        Path f = dir.resolve("mpsync.json");
        StringBuilder sb = new StringBuilder("{\"allowedHosts\":[");
        List<String> legacy = SourceGuard.NETEASE_HOSTS;
        for (int i = 0; i < legacy.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(legacy.get(i)).append('"');
        }
        sb.append("]}");
        Files.writeString(f, sb.toString(), StandardCharsets.UTF_8);

        MpConfig cfg = MpConfig.load(f);
        assertTrue(cfg.allowedHosts.isEmpty(), "旧的「仅网易云」列表应当被清空成不限制");
    }

    @Test
    void handEditedHostListIsNeverOverwritten(@TempDir Path dir) throws IOException {
        // 只有内容与旧默认值完全一致时才升级：服主自己收窄过的列表不能被吃掉。
        Path f = dir.resolve("mpsync.json");
        Files.writeString(f, "{\"allowedHosts\":[\"music.126.net\"]}", StandardCharsets.UTF_8);
        MpConfig cfg = MpConfig.load(f);
        assertEquals(List.of("music.126.net"), cfg.allowedHosts);
    }

    @Test
    void emptySourceListMeansUnrestricted() {
        MpConfig cfg = new MpConfig();
        assertTrue(cfg.allowedSources.isEmpty());
        assertTrue(cfg.isSourceAllowed("netease"));
        assertTrue(cfg.isSourceAllowed("kugou"));

        cfg.allowedSources = new java.util.ArrayList<>(List.of("netease"));
        assertTrue(cfg.isSourceAllowed("NETEASE"), "平台判定不区分大小写");
        assertFalse(cfg.isSourceAllowed("qq"));
    }

    @Test
    void loadWritesTheFileBackSoServerOwnersCanSeeEveryKey(@TempDir Path dir) {
        Path f = dir.resolve("nested").resolve("mpsync.json");
        MpConfig.load(f);
        assertTrue(Files.exists(f), "首次启动要把带全部默认值的配置写出来");
    }

    // ── 房间内无缝交接（automixLeadMs）─────────────────────────────────────────

    /**
     * 默认必须是开的。
     *
     * <p>它不属于「客户端与服主之间要口径一致的阈值」那一类，而是**一个功能开关**：提前换歌
     * 意味着本曲最后这么多毫秒不再单独发声，会交叠的客户端把它叠在下一首底下，不支持的客户端
     * 则直接切歌、尾巴少一截。默认打开是因为房间无缝要两端配套才成立 —— 服务端关着的话，
     * 客户端那半连下一首的链接都拿不到，功能等于不存在。想保住整条尾巴的服主改成 0 即可，
     * 上限与静默窗联动见 {@link #turningAutomixOnWidensTheGraceWindowAndIsClamped}。</p>
     */
    @Test
    void roomAutomixIsOnByDefault() {
        MpConfig cfg = new MpConfig();
        assertEquals(4000L, cfg.automixLeadMs, "默认必须开着，否则客户端那半永远没有原料");
    }

    @Test
    void aConfigFileFromBeforeThisFeatureGetsTheDefaultLead(@TempDir Path dir) throws IOException {
        // 存量 mpsync.json 里没有 automixLeadMs 这个键，gson 保留字段默认值 —— 也就是新的默认提前量。
        Path f = dir.resolve("mpsync.json");
        Files.writeString(f, "{\"clockGraceMs\":4000,\"maxRoomMembers\":32}", StandardCharsets.UTF_8);

        MpConfig cfg = MpConfig.load(f);
        assertEquals(4000L, cfg.automixLeadMs, "老配置也要吃到新默认值，否则升级后房间还是无缝不起来");
        assertEquals(5000L, cfg.clockGraceMs, "默认提前量生效时，静默窗必须联动抬到 L+1000");
        assertEquals(32, cfg.maxRoomMembers, "其余键照旧");
    }

    @Test
    void turningAutomixOnWidensTheGraceWindowAndIsClamped(@TempDir Path dir) throws IOException {
        // 交叠发生在命令帧之后、正当全房开流：静默窗盖不住它的话，淡到一半就会被心跳硬跳打断。
        Path f = dir.resolve("mpsync.json");
        Files.writeString(f, "{\"automixLeadMs\":99999}", StandardCharsets.UTF_8);
        MpConfig cfg = MpConfig.load(f);

        assertEquals(12_000L, cfg.automixLeadMs, "提前量有上限，否则一首几分钟的歌会被吃掉太多");
        assertTrue(cfg.clockGraceMs >= cfg.automixLeadMs + 1000L, "静默窗必须盖住整个交叠");
        assertTrue(cfg.clockGraceMs <= 30_000L, "但静默窗本身仍有封顶");
    }

    /**
     * 指派超时可以被 {@code 0} 取消（Folia 那套模型推理动辄几十秒，固定超时会在它跑完前抢活儿）。
     *
     * <p>关键是 <b>0 不能被下限钳成 5 秒</b> —— 否则「取消超时」这个开关根本关不掉，
     * 只能让服主设一个很大的数去绕，那还是会有超时。</p>
     */
    @Test
    void theAssignTimeoutCanBeCancelledWithZero(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("mpsync.json");

        Files.writeString(f, "{\"automixAssignTimeoutMs\":0}", StandardCharsets.UTF_8);
        assertEquals(0L, MpConfig.load(f).automixAssignTimeoutMs,
                "0 表示取消超时，是个有意义的值，不该被钳到下限");

        Files.writeString(f, "{\"automixAssignTimeoutMs\":-1}", StandardCharsets.UTF_8);
        assertEquals(0L, MpConfig.load(f).automixAssignTimeoutMs, "负数按「取消」处理，不留负数超时这种状态");

        Files.writeString(f, "{\"automixAssignTimeoutMs\":100}", StandardCharsets.UTF_8);
        assertEquals(5_000L, MpConfig.load(f).automixAssignTimeoutMs,
                "但只要给了正数，下限仍然生效：太短的超时等于一指派就立刻改派，谁都没机会算完");

        Files.writeString(f, "{\"automixAssignTimeoutMs\":999999}", StandardCharsets.UTF_8);
        assertEquals(120_000L, MpConfig.load(f).automixAssignTimeoutMs, "上限照旧，别让一首歌白等太久");
    }
}
