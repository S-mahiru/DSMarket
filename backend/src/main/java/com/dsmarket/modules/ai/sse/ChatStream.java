package com.dsmarket.modules.ai.sse;

/**
 * AI 态 SSE 事件通道（REQ C1 §3 事件协议）。
 *
 * <p>传输无关的收尾：{@code [tool_begin*] (delta* | fallback [suggest]) done} 或
 * {@code [tool_begin*] error}。suggest 现由切片 3 的低置信短路触发（reason=LOW_CONF，
 * C4-F6 触发①；其余触发 EMOTION/UNRESOLVED 属 C4 未产生）。实现有两条：
 * {@link SseChatStream}（真实 SSE 传输）与测试录制实现（内存断言）。
 * {@link #isCancelled()} 供编排在写屏后判断客户端是否已断开，断开则不写回会话（REQ E10）。</p>
 */
public interface ChatStream {

    /** 工具执行开始（每次执行前发一个，可多次） */
    void toolBegin(String tool, String label);

    /** 正文流式片段（仅最终生成阶段） */
    void delta(String text);

    /** 兜底回复（非模型/降级，REQ E4；后接 [suggest] done） */
    void fallback(String content);

    /** 建议提示（fallback 后、done 前至多一条；现 reason=LOW_CONF，见 C2 §3.4 短路） */
    void suggest(String reason);

    /** 本轮正常结束，携带 replyId（唯一正常收尾事件） */
    void done(String replyId);

    /** 中断（仅流中故障；code∈{UPSTREAM_ERROR,TIMEOUT,TOO_MANY_TOOL_ROUNDS}，出现即终） */
    void error(String code, String message);

    /** 客户端是否已断开（断连后继续写会失败，编排据此提前停止） */
    default boolean isCancelled() {
        return false;
    }

    /** 关闭通道（幂等；编排在 finally 调用） */
    void complete();
}
