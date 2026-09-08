package com.dsmarket.modules.ai.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * SSE /chat 正式通道请求（REQ C1 §2 权威参数）。
 *
 * <p>校验语义（E3，开流前 HTTP 400）：content trim 后 1~500 字；转人工锚点豁免超长属
 * C4 范围，本里程碑不实现 → 一律 400。context 为可选项，其"越权/不存在 → 忽略"规则在
 * {@code ChatScenario} 落地（服务端决定注不注入，不报错）。clientMsgId 仅作幂等（§2）。</p>
 */
@Data
public class ChatRequest {

    @NotBlank(message = "内容不能为空")
    @Size(max = 500, message = "内容过长（最多 500 字）")
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
