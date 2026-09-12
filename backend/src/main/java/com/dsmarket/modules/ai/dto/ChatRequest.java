package com.dsmarket.modules.ai.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * SSE /chat 正式通道请求（REQ C1 §2 权威参数）。
 *
 * <p>context 为可选项，其"越权/不存在 → 忽略"规则在 {@code ChatScenario} 落地（服务端决定注不
 * 注入，不报错）。clientMsgId 仅作幂等（§2）。</p>
 *
 * <p><b>content 长度是分层的（C1 §2 权威，2026-09-10 落地）</b>，本类只守其中最外层的绝对上限：</p>
 * <ul>
 *   <li><b>绝对上限 4000</b>（本类 {@code @Size}）——超过即 400，<b>锚点命中也不豁免</b>，口径是防超长 DoS。</li>
 *   <li><b>AI 态 1~500</b>（{@code ai.chat.content-max}）——超 500 且未命中转人工锚点 → 400。
 *       由 {@code AiChatStreamService.open()} 在<b>分流判定之后</b>校验，因为锚点检测必须先于长度校验。</li>
 *   <li><b>锚点命中豁免 500</b>（C1 §2）——长申诉可达转人工口子；正文按 C4 §4.1 步骤 6
 *       截断 ≤{@code ai.support.user-content-max}(2000) 转存为首条人工消息（P3：不转存禁止）。
 *       截断落在 {@code ChatHumanRouterImpl.anchorContent}。</li>
 *   <li><b>已是人工态（PH）≤2000</b>——走人工通道，由 {@code AiSupportSessionServiceImpl.sendMessage}
 *       按人工通道上限校验（超出 400，不静默截断：买家已在人工对话里，该收到错误而非丢字）。</li>
 * </ul>
 *
 * <p>因此"锚点正文 ≤2000 截断"这条<b>是可达的</b>：content ∈ (2000, 4000] 且命中锚点时触发。</p>
 */
@Data
public class ChatRequest {

    @NotBlank(message = "内容不能为空")
    @Size(max = 4000, message = "内容过长（最多 4000 字）")
    private String content;

    /** 页面上下文（可选；其字段校验为"忽略"语义，见 ChatScenario） */
    private Context context;

    /** 幂等键（可选）：同 userId+clientMsgId 在 dedupTtl 内重复 → 忽略并回 200 done */
    @Size(max = 64, message = "clientMsgId 过长")
    private String clientMsgId;

    /** 服务端使用的原文（已 trim），Bean 校验只做上限，此处归一化后再取用 */
    public String normalizedContent() {
        return content == null ? "" : content.trim();
    }

    /** 当前页面上下文（REQ C1 §2 context.*） */
    @Data
    public static class Context {

        /** 所在页面（无则忽略） */
        private ChatPage page;

        /** 订单号：page=order_detail 时前端应带；不属于当前用户 → 忽略 */
        private String orderNo;

        /** 商品 id：page=product_detail 时前端应带；不存在/已下架 → 忽略 */
        private Long productId;
    }

    /** context.page 枚举值（与前端下发的字符串严格一致，REQ C1 §2） */
    public enum ChatPage {
        @JsonProperty("home") HOME,
        @JsonProperty("order_list") ORDER_LIST,
        @JsonProperty("order_detail") ORDER_DETAIL,
        @JsonProperty("product_detail") PRODUCT_DETAIL
    }
}
