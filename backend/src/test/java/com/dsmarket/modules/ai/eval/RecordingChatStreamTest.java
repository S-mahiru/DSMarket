package com.dsmarket.modules.ai.eval;

import com.dsmarket.modules.ai.sse.ChatStream;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 录制装饰器单测（REQ-20260908-C5 §2 的 eventSeq / firstDeltaMs / suggestSent 采集点）。
 *
 * <p>两条主张各测一面：<b>该记的记全了</b>（事件序列、首包时延、建议标记），
 * 以及<b>不该动的没动</b>（§1"留痕不得改变事件协议"—— 转发必须逐字逐序，控制流方法原样透传）。</p>
 */
class RecordingChatStreamTest {

    /** 转发目标替身：只记下被调用过什么 */
    static class Spy implements ChatStream {
        final List<String> calls = new ArrayList<>();
        boolean cancelled;

        @Override
        public void toolBegin(String tool, String label) {
            calls.add("tool_begin:" + tool + ":" + label);
        }

        @Override
        public void delta(String text) {
            calls.add("delta:" + text);
        }

        @Override
        public void fallback(String content) {
            calls.add("fallback:" + content);
        }

        @Override
        public void suggest(String reason) {
            calls.add("suggest:" + reason);
        }

        @Override
        public void done(String replyId) {
            calls.add("done:" + replyId);
        }

        @Override
        public void error(String code, String message) {
            calls.add("error:" + code);
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public void complete() {
            calls.add("complete");
        }
    }

    private RoundTrace trace() {
        return trace(2000);
    }

    private RoundTrace trace(int answerMax) {
        return new RoundTrace("h", "q", 1_000L, 40, answerMax);
    }

    @Test
    void assemblesAnswer_fromDeltas_inOrder() {
        RoundTrace t = trace();
        RecordingChatStream rec = new RecordingChatStream(new Spy(), t, () -> 1_000L);

        rec.toolBegin("search_knowledge", "…");
        rec.delta("您的订单 ");
        rec.delta("DSM2026 ");
        rec.delta("已发货。");

        assertEquals("您的订单 DSM2026 已发货。", t.getAnswer(),
                "§3 的答对率要评的是拼好的正文，不是 delta 分片序列");
        assertEquals(0, t.getAnswerOverflow());
    }

    @Test
    void fallbackContent_alsoBecomesAnswer() {
        RoundTrace t = trace();
        RecordingChatStream rec = new RecordingChatStream(new Spy(), t, () -> 1_000L);

        rec.fallback("（自动回复）客服正忙，请稍后再试。");

        assertEquals("（自动回复）客服正忙，请稍后再试。", t.getAnswer(),
                "兜底轮的正文就是买家看到的答复，不记它就没法评低置信轮的对错");
    }

    @Test
    void noContentEvents_leavesAnswerEmpty_notNull() {
        RoundTrace t = trace();
        RecordingChatStream rec = new RecordingChatStream(new Spy(), t, () -> 1_000L);

        rec.toolBegin("search_knowledge", "…");
        rec.error("UPSTREAM_ERROR", "上游 502");

        assertEquals("", t.getAnswer(), "纯 error 轮记空串 —— 与 replyId 同理，别让离线脚本判两种空");
    }

    @Test
    void answerOverCap_isTruncatedButOverflowIsCounted() {
        RoundTrace t = trace(5);
        RecordingChatStream rec = new RecordingChatStream(new Spy(), t, () -> 1_000L);

        rec.delta("一二三");
        rec.delta("四五六七");

        assertEquals("一二三四五", t.getAnswer(), "留痕只留前 answer-max 个字符");
        assertEquals(2, t.getAnswerOverflow(),
                "溢出必须计数：不计数则日志看不出'被截过'，标注者会把截断读成模型答了一半");
    }

    @Test
    void truncation_neverSplitsSurrogatePair() {
        // 😀 = U+1F600 = 一对代理（2 个 char）。answerMax=1 正好切在它中间。
        RoundTrace t = trace(1);
        RecordingChatStream rec = new RecordingChatStream(new Spy(), t, () -> 1_000L);

        rec.delta("😀好");

        assertEquals("", t.getAnswer(),
                "宁可少留一个字符，也不留下孤立的高位代理 —— 那只会在 JSON 里变成 U+FFFD");
        assertEquals(3, t.getAnswerOverflow(), "被让出的那个字符计入溢出，不凭空消失");
    }

    @Test
    void recordsEventSeq_inEmissionOrder() {
        Spy spy = new Spy();
        RoundTrace t = trace();
        RecordingChatStream rec = new RecordingChatStream(spy, t, () -> 1_000L);

        rec.toolBegin("search_knowledge", "正在检索知识库…");
        rec.delta("第一段");
        rec.delta("第二段");
        rec.suggest("LOW_CONF");
        rec.done("1");

        assertEquals(List.of("tool_begin", "d", "d", "suggest", "done"), t.getEventSeq(),
                "§2 eventSeq 记的是真实下发序，delta 折成 d");
        assertEquals(0, t.getEventSeqOverflow());
        assertEquals("1", t.getReplyId());
        assertEquals("LOW_CONF", t.getSuggestReason(), "suggestSent 的数据源");
    }

    @Test
    void firstContentLatency_measuredFromRoundStart_toFirstDelta() {
        AtomicLong now = new AtomicLong(1_000L);
        RoundTrace t = trace();
        RecordingChatStream rec = new RecordingChatStream(new Spy(), t, now::get);

        rec.toolBegin("search_knowledge", "…");
        now.set(1_240L);
        rec.delta("第一段");
        now.set(1_900L);
        rec.delta("第二段");

        assertEquals(240L, t.getFirstContentMs(), "首包时延只记第一次，后续 delta 不覆盖");
    }

    @Test
    void fallbackAlsoCountsAsFirstContent() {
        // §2 原文「fallback 记上游时间」：兜底轮没有 delta，首包时延只能由 fallback 事件来标。
        // 不把 fallback 计入的话，所有兜底轮的时延都是 -1，首包时延均值会系统性偏低。
        AtomicLong now = new AtomicLong(1_000L);
        RoundTrace t = trace();
        RecordingChatStream rec = new RecordingChatStream(new Spy(), t, now::get);

        now.set(1_500L);
        rec.fallback("（自动回复）…");

        assertEquals(500L, t.getFirstContentMs());
        assertEquals(List.of("fallback"), t.getEventSeq());
    }

    @Test
    void error_recordsCode() {
        RoundTrace t = trace();
        RecordingChatStream rec = new RecordingChatStream(new Spy(), t, () -> 1_000L);

        rec.error("TIMEOUT", "回复不完整，请稍后重试。");

        assertEquals("TIMEOUT", t.getErrorCode());
        assertEquals(List.of("error"), t.getEventSeq());
    }

    @Test
    void purePassthrough_forwardsEveryEventVerbatim() {
        Spy spy = new Spy();
        RecordingChatStream rec = new RecordingChatStream(spy, trace(), () -> 1_000L);

        rec.toolBegin("t", "标签");
        rec.delta("文本");
        rec.fallback("兜底");
        rec.suggest("UNRESOLVED");
        rec.done("7");
        rec.error("UPSTREAM_ERROR", "服务暂时不可用，请稍后重试。");
        rec.complete();

        assertEquals(List.of("tool_begin:t:标签", "delta:文本", "fallback:兜底",
                        "suggest:UNRESOLVED", "done:7", "error:UPSTREAM_ERROR", "complete"),
                spy.calls, "§1：留痕不得改变事件协议 —— 顺序、内容、次数逐字透传");
    }

    @Test
    void isCancelled_delegates_notCached() {
        // 参与编排控制流的不是留痕的账，必须是目标的实时状态。缓存/覆盖它都会真的改变对话行为。
        Spy spy = new Spy();
        RecordingChatStream rec = new RecordingChatStream(spy, trace(), () -> 1_000L);

        assertFalse(rec.isCancelled());
        spy.cancelled = true;
        assertTrue(rec.isCancelled(), "必须实时问目标，不得缓存");
    }

    @Test
    void toolBegin_isNotCountedAsFirstContent() {
        // 工具提示是"正在处理…"的过程提示，不是答复内容。把它算进首包时延会让所有工具轮
        // 的时延看起来都很好（工具一调就"有内容了"），把真正的生成等待时间藏起来。
        RoundTrace t = trace();
        RecordingChatStream rec = new RecordingChatStream(new Spy(), t, () -> 5_000L);

        rec.toolBegin("search_knowledge", "…");

        assertNull(t.getFirstContentMs());
    }
}
