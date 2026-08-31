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
}
