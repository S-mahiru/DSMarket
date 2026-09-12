package com.dsmarket.modules.ai.eval;

import com.dsmarket.modules.ai.sse.ChatStream;

import java.util.function.LongSupplier;

/**
 * {@link ChatStream} 录制装饰器 —— REQ-20260908-C5 §2 里 {@code eventSeq} / {@code firstDeltaMs} /
 * {@code suggestSent} 三个字段的采集点。
 *
 * <p><b>为什么用装饰器而不是在编排里手写记账</b>：事件是从 {@code AiChatStreamService} 的
 * 四个不同方法里发出去的（工具循环、正文循环、兜底分支、收尾），手写记账就得在那四处各加一行，
 * 且每加一个新事件类型（C4 的 suggest 就是这么加进来的）都可能漏记一处。
 * 套在通道外层后，<b>"凡是发出去的事件都被记下"是结构性成立的</b>，不依赖后续改动者的自觉。</p>
 *
 * <p><b>纯观察（§1"不改既有行为"的落点）</b>：本类只做"转发 + 记账"，不改变任何事件的
 * 顺序、内容、次数，也不吞异常、不加超时、不重试。{@code isCancelled}/{@code complete}
 * 原样透传 —— 它们参与编排的控制流，在这里做任何手脚都会真的改变对话行为。</p>
 *
 * <p><b>先转发、后记账</b>：记账的前提是"这次下发真的发生了"。若 delegate 抛异常
 * （如 SseEmitter 已关闭），该事件不该被计成已发出 —— 否则日志会声称发过一个买家没收到的
 * {@code done}，而 {@code replyKind} 会从 error 变成 ai。</p>
 */
public class RecordingChatStream implements ChatStream {

    private final ChatStream delegate;
    private final RoundTrace trace;
    /** 时延测量时钟（测试注入口；生产恒为 {@code System::currentTimeMillis}） */
    private final LongSupplier clock;

    public RecordingChatStream(ChatStream delegate, RoundTrace trace) {
        this(delegate, trace, System::currentTimeMillis);
    }

    RecordingChatStream(ChatStream delegate, RoundTrace trace, LongSupplier clock) {
        this.delegate = delegate;
        this.trace = trace;
        this.clock = clock;
    }

    @Override
    public void toolBegin(String tool, String label) {
        delegate.toolBegin(tool, label);
        trace.noteEvent("tool_begin");
    }

    @Override
    public void delta(String text) {
        delegate.delta(text);
        trace.markFirstContent(clock.getAsLong());
        trace.noteEvent("d");
        trace.appendAnswer(text);
    }

    /**
     * 兜底回复。它同样是"买家看到的第一段内容"，故与 delta 一起计入 {@code firstDeltaMs}
     * —— §2 原文"fallback 记上游时间"说的就是这个：兜底轮没有 delta，首包时延只能由
     * fallback 事件来标。
     */
    @Override
    public void fallback(String content) {
        delegate.fallback(content);
        trace.markFirstContent(clock.getAsLong());
        trace.noteEvent("fallback");
        // 兜底轮的正文就是买家的答复，标准一样：不记它，低置信/FAQ 那些轮就没法评对错 ——
        // 而 §3 恰恰要看"兜底率"与"答对率"的关系。
        trace.appendAnswer(content);
    }

    @Override
    public void suggest(String reason) {
        delegate.suggest(reason);
        trace.suggest(reason);
        trace.noteEvent("suggest");
    }

    @Override
    public void done(String replyId) {
        delegate.done(replyId);
        trace.replyId(replyId);
        trace.noteEvent("done");
    }

    @Override
    public void error(String code, String message) {
        delegate.error(code, message);
        trace.error(code);
        trace.noteEvent("error");
    }

    @Override
    public boolean isCancelled() {
        return delegate.isCancelled();
    }

    @Override
    public void complete() {
        delegate.complete();
    }
}
