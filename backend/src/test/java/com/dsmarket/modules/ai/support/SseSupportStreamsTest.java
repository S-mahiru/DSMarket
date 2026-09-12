package com.dsmarket.modules.ai.support;

import com.dsmarket.modules.ai.config.AiProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SseSupportStreams 单测（C4 §4.7/§4.8 三通道隔离 + 多标签广播 + 断连自清理）。
 *
 * <p>这是切片 2 最该被盯死的一处：买家通道与 admin 通道"互不混发"是 REQ 的硬规则
 * （验收 §8-5/§8-5b），而混发一旦溜过去，前端只会表现为"多了些不认识的事件"，
 * 很难在真机 sanity 里被发现。所以这里逐条压白名单。</p>
 *
 * <p>记录型 emitter 通过覆写 {@code newEmitter()} 注入 —— 注册表自己 new SseEmitter，
 * 不给这个接缝的话"到底发出去了什么帧"就只能靠起容器观察。</p>
 */
class SseSupportStreamsTest {

    private static final long USER = 7L;
    private static final long ADMIN = 9L;

    /** 记录发出去的数据帧；{@code dead=true} 模拟客户端已断开（写即抛） */
    static class RecordingEmitter extends SseEmitter {
        final List<String> frames = new ArrayList<>();
        boolean dead;

        RecordingEmitter() {
            super(60_000L);
        }

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            if (dead) {
                throw new IOException("客户端已断开");
            }
            for (ResponseBodyEmitter.DataWithMediaType d : builder.build()) {
                if (d.getData() != null) {
                    frames.add(String.valueOf(d.getData()));
                }
            }
        }

        /** 只看 data 事件（带 type 的 JSON），忽略 keepalive 注释帧 */
        List<String> dataFrames() {
            return frames.stream().filter(f -> f.contains("\"type\"")).toList();
        }
    }

    /** 记录型通道：每次注册都给出可断言的 emitter，并按注册顺序留存 */
    static class RecordingStreams extends SseSupportStreams {
        final List<RecordingEmitter> created = new ArrayList<>();

        RecordingStreams(ObjectMapper mapper, AiProperties props) {
            super(mapper, props);
        }

        @Override
        protected SseEmitter newEmitter() {
            RecordingEmitter e = new RecordingEmitter();
            created.add(e);
            return e;
        }

        RecordingEmitter last() {
            return created.get(created.size() - 1);
        }
    }

    private RecordingStreams streams;

    @BeforeEach
    void setUp() {
        streams = new RecordingStreams(new ObjectMapper(), new AiProperties());
    }

    // ------------------------------------------------------------ 事件帧形态

    @Test
    void buyerEvent_carriesTypeField() {
        streams.registerBuyer(USER);

        streams.toBuyer(USER, SupportEvents.HUMAN_STATUS,
                SupportEvents.humanStatus(SupportEvents.STATE_HUMAN_ACTIVE, "已接入"));

        List<String> frames = streams.last().dataFrames();
        assertEquals(1, frames.size());
        // type 必须出现在帧里：§4.7 全靠它分派事件，缺了前端认不出这是什么
        assertTrue(frames.get(0).contains("\"type\":\"human_status\""), frames.get(0));
        assertTrue(frames.get(0).contains("\"state\":\"human_active\""), frames.get(0));
    }

    // ------------------------------------------------------------ 白名单（隔离硬规则）

    @Test
    void buyerChannel_rejectsAdminAndAiEventTypes() {
        streams.registerBuyer(USER);

        // admin 通道类型不得进买家通道（§4.7 硬规则）
        assertThrows(IllegalArgumentException.class, () -> streams.toBuyer(USER, SupportEvents.SESSION_NEW,
                SupportEvents.sessionNew(1L, "a***t", "USER_REQUEST", null, null)));
        assertThrows(IllegalArgumentException.class, () -> streams.toBuyer(USER, SupportEvents.MESSAGE_NEW,
                SupportEvents.messageNew(1L, 2L, "USER", "x", null)));
        // C1 AI 态事件更不得混入（三通道物理隔离）
        assertThrows(IllegalArgumentException.class, () -> streams.toBuyer(USER, "delta",
                SupportEvents.humanClose("x")));
        assertThrows(IllegalArgumentException.class, () -> streams.toBuyer(USER, "suggest",
                SupportEvents.humanClose("x")));
    }

    @Test
    void adminChannel_rejectsBuyerEventTypes() {
        streams.registerAdmin(ADMIN);

        for (String humanType : List.of(SupportEvents.HUMAN_STATUS, SupportEvents.HUMAN_MESSAGE,
                SupportEvents.HUMAN_OFFLINE_TIP, SupportEvents.HUMAN_CLOSE)) {
            assertThrows(IllegalArgumentException.class, () -> streams.toAdmins(humanType,
                    SupportEvents.humanClose("x")), humanType + " 不得出现在 admin 通道");
        }
        assertThrows(IllegalArgumentException.class, () -> streams.toAdmin(ADMIN, "delta",
                SupportEvents.seatStatus(ADMIN, "online", SupportEvents.REASON_LOGIN)));
    }

    @Test
    void seatStatus_mustBeSentToSingleAdmin_notBroadcast() {
        // §4.8：seat_status 是"本坐席"的在线态，广播给全体既无意义也误导
        assertThrows(IllegalArgumentException.class, () -> streams.toAdmins(SupportEvents.SEAT_STATUS,
                SupportEvents.seatStatus(ADMIN, "online", SupportEvents.REASON_LOGIN)));
    }

    @Test
    void rejection_doesNotLeakToOtherChannel() {
        streams.registerBuyer(USER);
        streams.registerAdmin(ADMIN);

        assertThrows(IllegalArgumentException.class, () -> streams.toBuyer(USER, SupportEvents.SESSION_NEW,
                SupportEvents.sessionNew(1L, "a***t", "USER_REQUEST", null, null)));

        // 被拒的事件不得"顺手"发到另一条通道上去
        assertTrue(((RecordingEmitter) streams.created.get(0)).frames.isEmpty());
        assertTrue(((RecordingEmitter) streams.created.get(1)).frames.isEmpty());
    }

    // ------------------------------------------------------------ 双通道互不可见

    @Test
    void buyerAndAdmin_channelsArePhysicallySeparate() {
        streams.registerBuyer(USER);
        streams.registerAdmin(ADMIN);
        RecordingEmitter buyerEmitter = streams.created.get(0);
        RecordingEmitter adminEmitter = streams.created.get(1);

        streams.toAdmins(SupportEvents.SESSION_STATUS,
                SupportEvents.sessionStatus(1L, "human_active", null, null));

        assertTrue(buyerEmitter.frames.isEmpty(), "买家不得收到 admin 通道事件");
        assertEquals(1, adminEmitter.dataFrames().size());

        streams.toBuyer(USER, SupportEvents.HUMAN_CLOSE, SupportEvents.humanClose("已结束"));

        assertEquals(1, buyerEmitter.dataFrames().size());
        assertEquals(1, adminEmitter.dataFrames().size(), "坐席不得收到 human_* 事件");
    }

    @Test
    void toBuyer_onlyReachesThatBuyer() {
        streams.registerBuyer(USER);
        streams.registerBuyer(USER + 1);
        RecordingEmitter mine = streams.created.get(0);
        RecordingEmitter other = streams.created.get(1);

        streams.toBuyer(USER, SupportEvents.HUMAN_CLOSE, SupportEvents.humanClose("已结束"));

        assertEquals(1, mine.dataFrames().size());
        assertTrue(other.frames.isEmpty(), "别的买家的连接不该收到");
    }

    // ------------------------------------------------------------ 多标签与断连

    @Test
    void multiTab_sameUser_bothReceive() {
        // §4.2 P5：多标签共享同一在线键、不做顶号互斥 —— 事件必须广播到全部标签页
        streams.registerBuyer(USER);
        streams.registerBuyer(USER);
        assertEquals(2, streams.buyerConnectionCount());

        streams.toBuyer(USER, SupportEvents.HUMAN_STATUS,
                SupportEvents.humanStatus(SupportEvents.STATE_PENDING_HUMAN, "等待接入"));

        assertEquals(1, streams.created.get(0).dataFrames().size());
        assertEquals(1, streams.created.get(1).dataFrames().size());
    }

    @Test
    void deadConnection_isDroppedOthersStillServed() {
        streams.registerBuyer(USER);
        streams.registerBuyer(USER);
        RecordingEmitter dead = streams.created.get(0);
        RecordingEmitter alive = streams.created.get(1);
        dead.dead = true;

        streams.toBuyer(USER, SupportEvents.HUMAN_CLOSE, SupportEvents.humanClose("已结束"));

        assertEquals(1, streams.buyerConnectionCount(), "写失败的连接应被摘除，避免连接表泄漏");
        assertEquals(1, alive.dataFrames().size(), "同用户其它标签页不受影响");
    }

    // ------------------------------------------------------------ keepalive

    @Test
    void pingAll_sendsCommentFrame_notDataEvent() {
        streams.registerBuyer(USER);
        streams.registerAdmin(ADMIN);

        streams.pingAll();

        for (RecordingEmitter e : streams.created) {
            assertFalse(e.frames.isEmpty(), "keepalive 应发出帧以保住长连接");
            assertTrue(e.dataFrames().isEmpty(),
                    "keepalive 必须是注释帧：发成 data 事件会污染 §4.7/§4.8 事件白名单");
        }
    }

    @Test
    void pingAll_deadConnectionIsDropped() {
        streams.registerBuyer(USER);
        streams.created.get(0).dead = true;

        streams.pingAll();

        assertEquals(0, streams.buyerConnectionCount());
    }
}
