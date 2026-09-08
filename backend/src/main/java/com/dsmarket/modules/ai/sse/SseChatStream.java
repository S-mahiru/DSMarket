package com.dsmarket.modules.ai.sse;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

/**
 * SseEmitter 上的 {@link ChatStream} 传输实现（REQ C1 §3 事件语法）。
 *
 * <p>每个事件以一行 JSON 作为 SSE 的 {@code data}：{@code data: {json}\n\n}。
 * 载荷字段与 REQ §3 事件表一致（type + tool/label、delta、content、replyId、code/message）。</p>
 *
 * <p>断连语义：写入抛 IO/非法状态异常 → 置 {@code cancelled}，后续写自动忽略；
 * {@code complete()} 幂等，正常收尾与断连兜底都安全。</p>
 */
@Slf4j
public class SseChatStream implements ChatStream {

    private final SseEmitter emitter;
    private final ObjectMapper objectMapper;
    private volatile boolean cancelled;
    private volatile boolean completed;

    public SseChatStream(SseEmitter emitter, ObjectMapper objectMapper) {
        this.emitter = emitter;
        this.objectMapper = objectMapper;
    }

    @Override
    public void toolBegin(String tool, String label) {
        ObjectNode n = objectMapper.createObjectNode();
        n.put("tool", tool);
        if (label != null) {
            n.put("label", label);
        }
        send("tool_begin", n);
    }

    @Override
    public void delta(String text) {
        ObjectNode n = objectMapper.createObjectNode();
        n.put("delta", text);
        send("delta", n);
    }

    @Override
    public void fallback(String content) {
        ObjectNode n = objectMapper.createObjectNode();
        n.put("content", content);
        send("fallback", n);
    }

    @Override
    public void suggest(String reason) {
        ObjectNode n = objectMapper.createObjectNode();
        n.put("reason", reason);
        send("suggest", n);
    }

    @Override
    public void done(String replyId) {
        ObjectNode n = objectMapper.createObjectNode();
        n.put("replyId", replyId);
        send("done", n);
    }

    @Override
    public void error(String code, String message) {
        ObjectNode n = objectMapper.createObjectNode();
        n.put("code", code);
        n.put("message", message);
        send("error", n);
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    private void send(String type, ObjectNode payload) {
        if (cancelled || completed) {
            return;
        }
        payload.put("type", type);
        try {
            emitter.send(SseEmitter.event()
                    .data(objectMapper.writeValueAsString(payload), MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            // E10：客户端已断开（或响应已关），后续写无意义
            log.debug("[ai] SSE 写入失败，视为客户端断开: {}", e.getMessage());
            cancelled = true;
        }
    }

    @Override
    public void complete() {
        if (completed) {
            return;
        }
        completed = true;
        try {
            emitter.complete();
        } catch (Exception e) {
            log.debug("[ai] SSE complete 异常（通常为已断开）: {}", e.getMessage());
        }
    }
}
