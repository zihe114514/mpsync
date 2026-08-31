package yun.shan.mpsync;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import yun.shan.mpsync.net.Proto;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 线协议。这是三个仓库之间的契约（服务端 core、modernMP 的 PartyProto、
 * MuoniumPlayer 的 MpProto），也是「1.20.1 的客户端能进 1.21.1 的房间」这个特性的地基。
 */
class ProtoTest {

    @Test
    void roundTrip() {
        JsonObject m = Proto.msg(Proto.S_TIME);
        m.addProperty("pos", 12345L);
        m.addProperty("heartbeat", true);

        JsonObject back = Proto.decode(Proto.encode(m));
        assertNotNull(back);
        assertEquals(Proto.S_TIME, back.get(Proto.OP).getAsString());
        assertEquals(12345L, back.get("pos").getAsLong());
        assertTrue(back.get("heartbeat").getAsBoolean());
    }

    @Test
    void framingIsByteIdenticalForTheSamePayload() {
        // 跨版本同房靠的就是这一条：Proto 自己写 varint 分帧，不借用任何 Minecraft 的缓冲
        // 辅助方法，所以 Fabric / Paper / Forge / NeoForge、1.20.1 到 26.2 产出的帧逐字节相同。
        JsonObject a = Proto.msg(Proto.S_PLAY);
        a.addProperty("pos", 0L);
        JsonObject b = Proto.msg(Proto.S_PLAY);
        b.addProperty("pos", 0L);
        assertArrayEquals(Proto.encode(a), Proto.encode(b));
    }

    @Test
    void varintHeaderPrecedesPayload() {
        JsonObject m = Proto.msg("x");
        byte[] frame = Proto.encode(m);
        int payloadLen = m.toString().getBytes(StandardCharsets.UTF_8).length;
        // 短帧的长度头是单字节
        assertTrue(payloadLen < 128, "这条测试假设短帧");
        assertEquals(payloadLen, frame[0] & 0xFF);
        assertEquals(payloadLen + 1, frame.length);
    }

    @Test
    void bareJsonWithoutHeaderIsAccepted() {
        // 经桥接/代理进来的帧有时会丢掉长度头，decode 必须容忍，否则那些客户端整个不可用。
        byte[] bare = "{\"op\":\"hello\"}".getBytes(StandardCharsets.UTF_8);
        JsonObject back = Proto.decode(bare);
        assertNotNull(back);
        assertEquals("hello", back.get(Proto.OP).getAsString());
    }

    @Test
    void malformedFramesDecodeToNull() {
        assertNull(Proto.decode(null));
        assertNull(Proto.decode(new byte[0]));
        assertNull(Proto.decode(new byte[]{0x05, 'n', 'o', 'p', 'e'}));
    }

    @Test
    void sourceTokenRules() {
        assertTrue(Proto.isValidSource("netease"));
        assertTrue(Proto.isValidSource(Proto.SOURCE_QQ));
        assertTrue(Proto.isValidSource(Proto.SOURCE_KUGOU));
        assertTrue(Proto.isValidSource(Proto.SOURCE_GD));
        assertTrue(Proto.isValidSource("my_platform2"));

        // 令牌要参与拼 source:songId 这个身份键，所以不许出现分隔符、大写和空串。
        assertFalse(Proto.isValidSource(null));
        assertFalse(Proto.isValidSource(""));
        assertFalse(Proto.isValidSource("NetEase"));
        assertFalse(Proto.isValidSource("net:ease"));
        assertFalse(Proto.isValidSource("net-ease"));
        assertFalse(Proto.isValidSource("0123456789abcdefg"));   // 17 位，超上限
    }

    @Test
    void neteaseStaysTheDefaultToken() {
        // 老客户端不发 source 字段，缺省必须继续是网易云，否则历史点歌全被判成无效平台。
        assertEquals("netease", Proto.SOURCE_NETEASE);
    }
}
