package com.dsmarket.modules.ai.tool.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dsmarket.common.enums.OrderStatusEnum;
import com.dsmarket.modules.ai.tool.ChatTool;
import com.dsmarket.modules.ai.tool.ToolResult;
import com.dsmarket.modules.order.entity.OrderInfo;
import com.dsmarket.modules.order.mapper.OrderInfoMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * query_my_order：查询"当前登录用户自己的"订单（REQ F2 订单消歧）。
 *
 * <p>数据权限：查询条件写死 {@code user_id = 服务端注入的 userId}，模型/前端都无法改——
 * 他人订单号在本查询中不存在 → 与"订单不存在"同折叠为 NOT_FOUND（侧信道防护）。</p>
 *
 * <p>F2 语义：latest=true → 最近一单；给了 orderNo → 本人该单；两者都缺时
 * 0 笔→NOT_FOUND、恰好 1 笔→该单、≥2 笔→返回候选清单（≤5）让用户澄清，
 * <b>不静默替用户答最近一单</b>。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QueryMyOrderTool implements ChatTool {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final int CANDIDATE_LIMIT = 5;
    private static final String NOT_FOUND = "没有找到相关订单。请确认后重试，或联系人工客服。";

    private final OrderInfoMapper orderInfoMapper;

    @Override
    public String name() {
        return "query_my_order";
    }

    @Override
    public String description() {
        return "查询当前登录用户自己的订单（订单号、下单时间、状态、金额）。"
                + "用户指明要看最近/最新一单时把 latest 设为 true；"
                + "用户只给订单号时传 orderNo；"
                + "两者都不给且存在多笔订单时，本工具会返回候选清单，你需要把候选展示给用户让其确认是哪一笔，不要替用户猜。";
    }

    @Override
    public JsonNode parameters() {
        ObjectNode props = JsonNodeFactory.instance.objectNode();
        props.putObject("orderNo")
                .put("type", "string")
                .put("description", "要查询的订单号（可选）");
        props.putObject("latest")
                .put("type", "boolean")
                .put("description", "是否查询最近一单（可选，默认 false）");
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        schema.set("properties", props);
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public ToolResult execute(Long userId, JsonNode args) {
        if (userId == null) {
            return ToolResult.builder().ok(false).error("缺少用户身份，工具拒绝执行").build();
        }
        String orderNo = args.path("orderNo").asText(null);
        if (orderNo != null) {
            orderNo = orderNo.trim();
            if (orderNo.isEmpty()) {
                orderNo = null;
            }
        }
        boolean latest = args.path("latest").asBoolean(false);

        // 本人最近订单（上界截断，够判单/取最近即可；权限已在 where 内）
        List<OrderInfo> recent = orderInfoMapper.selectList(
                new LambdaQueryWrapper<OrderInfo>()
                        .eq(OrderInfo::getUserId, userId)
                        .orderByDesc(OrderInfo::getCreatedAt)
                        .last("LIMIT 20"));

        if (latest) {
            if (recent.isEmpty()) {
                return ok(NOT_FOUND);
            }
            return ok(describeDetail(recent.get(0)));
        }
        if (orderNo != null) {
            for (OrderInfo o : recent) {
                if (orderNo.equals(o.getOrderNo())) {
                    return ok(describeDetail(o));
                }
            }
            return ok(NOT_FOUND);
        }
        // 两者都缺
        if (recent.isEmpty()) {
            return ok(NOT_FOUND);
        }
        if (recent.size() == 1) {
            return ok(describeDetail(recent.get(0)));
        }
        StringBuilder sb = new StringBuilder("共找到 ").append(recent.size()).append(" 笔订单，为免答错请确认是哪一笔：\n");
        int shown = 0;
        for (OrderInfo o : recent) {
            if (shown >= CANDIDATE_LIMIT) {
                break;
            }
            sb.append(shown + 1).append(". ").append(candidateLine(o)).append("\n");
            shown++;
        }
        if (recent.size() > CANDIDATE_LIMIT) {
            sb.append("…（仅列出前 ").append(CANDIDATE_LIMIT).append(" 笔，可直接提供订单号精确定位）\n");
        }
        sb.append("需要最近一笔订单可直接说\"查我的最近订单\"。");
        return ok(sb.toString());
    }

    // ------------------------------------------------------------ 渲染

    private ToolResult ok(String text) {
        return ToolResult.builder().ok(true).content(text).build();
    }

    private String describeDetail(OrderInfo o) {
        StringBuilder sb = new StringBuilder();
        sb.append("订单号：").append(o.getOrderNo()).append("\n");
        sb.append("下单时间：").append(ts(o.getCreatedAt())).append("\n");
        sb.append("状态：").append(statusText(o)).append("\n");
        sb.append("订单金额：¥").append(money(o.getTotalAmount()));
        if (o.getActualAmount() != null) {
            sb.append("\n实付金额：¥").append(money(o.getActualAmount()));
        }
        return sb.toString();
    }

    private String candidateLine(OrderInfo o) {
        return "订单号 " + o.getOrderNo() + "｜" + ts(o.getCreatedAt()) + " 下单｜"
                + statusText(o) + "｜金额 ¥" + money(o.getTotalAmount());
    }

    private String statusText(OrderInfo o) {
        if (o.getStatus() == null) {
            return "未知";
        }
        try {
            return OrderStatusEnum.fromValue(o.getStatus()).getDisplayName();
        } catch (IllegalArgumentException e) {
            return "未知";
        }
    }

    private String money(BigDecimal v) {
        return v == null ? "-" : v.stripTrailingZeros().toPlainString();
    }

    private String ts(LocalDateTime t) {
        return t == null ? "-" : FMT.format(t);
    }
}
