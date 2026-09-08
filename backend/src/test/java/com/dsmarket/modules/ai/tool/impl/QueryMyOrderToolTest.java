package com.dsmarket.modules.ai.tool.impl;

import com.dsmarket.modules.ai.tool.ToolResult;
import com.dsmarket.modules.order.entity.OrderInfo;
import com.dsmarket.modules.order.mapper.OrderInfoMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * QueryMyOrderTool 单元测试：F2 订单消歧语义 + 数据权限（他人订单同折叠）。
 * 不依赖 DB，mock mapper。
 */
@ExtendWith(MockitoExtension.class)
class QueryMyOrderToolTest {

    @Mock
    private OrderInfoMapper orderInfoMapper;

    @InjectMocks
    private QueryMyOrderTool tool;

    private final ObjectMapper om = new ObjectMapper();

    private OrderInfo order(String orderNo, LocalDateTime createdAt, int status, String total) {
        OrderInfo o = new OrderInfo();
        o.setOrderNo(orderNo);
        o.setUserId(100L);
        o.setCreatedAt(createdAt);
        o.setStatus(status);
        o.setTotalAmount(new BigDecimal(total));
        o.setActualAmount(new BigDecimal(total));
        return o;
    }

    private ObjectNode args() {
        return om.createObjectNode();
    }

    private ToolResult exec(Long userId, ObjectNode a) {
        return tool.execute(userId, a);
    }

    @Test
    void latest_true_returnsMostRecent() {
        OrderInfo old = order("A1", LocalDateTime.of(2026, 8, 1, 10, 0), 4, "100");
        OrderInfo recent = order("A2", LocalDateTime.of(2026, 9, 1, 10, 0), 0, "200");
        when(orderInfoMapper.selectList(any())).thenReturn(List.of(recent, old));

        ToolResult r = exec(100L, args().put("latest", true));
        assertTrue(r.isOk());
        assertTrue(r.getContent().contains("A2"), "应返回最近一单 A2，实际=" + r.getContent());
        assertFalse(r.getContent().contains("A1"));
    }

    @Test
    void orderNo_owned_returnsDetail() {
        OrderInfo o = order("ORD2026", LocalDateTime.of(2026, 9, 2, 9, 30), 1, "399.00");
        when(orderInfoMapper.selectList(any())).thenReturn(List.of(o));

        ToolResult r = exec(100L, args().put("orderNo", "ORD2026"));
        assertTrue(r.isOk());
        assertTrue(r.getContent().contains("ORD2026"));
        assertTrue(r.getContent().contains("已付款"), "应映射状态文案，实际=" + r.getContent());
        assertTrue(r.getContent().contains("399"), "应含金额");
    }

    @Test
    void orderNo_othersOrder_foldsToNotFound() {
        // 他人订单号：WHERE user_id=本人 → 查无此行，与不存在同折叠（侧信道）
        OrderInfo mine = order("MINE1", LocalDateTime.of(2026, 9, 2, 9, 30), 1, "50");
        when(orderInfoMapper.selectList(any())).thenReturn(List.of(mine));

        ToolResult r = exec(100L, args().put("orderNo", "OTHERS_ORDER"));
        assertTrue(r.isOk());
        assertTrue(r.getContent().contains("没有找到"), "他人订单不应暴露任何存在性信息");
    }

    @Test
    void bothMissing_noOrder_returnsNotFound() {
        when(orderInfoMapper.selectList(any())).thenReturn(new ArrayList<>());
        ToolResult r = exec(100L, args());
        assertTrue(r.isOk());
        assertTrue(r.getContent().contains("没有找到"));
    }

    @Test
    void bothMissing_singleOrder_returnsIt() {
        OrderInfo o = order("ONLY1", LocalDateTime.of(2026, 9, 2, 9, 30), 3, "88");
        when(orderInfoMapper.selectList(any())).thenReturn(List.of(o));

        ToolResult r = exec(100L, args());
        assertTrue(r.isOk());
        assertTrue(r.getContent().contains("ONLY1"), "唯一一笔应直接返回而非列候选");
    }

    @Test
    void bothMissing_manyOrders_returnsCandidatesAndAsksClarify() {
        List<OrderInfo> orders = new ArrayList<>();
        for (int i = 1; i <= 7; i++) {
            orders.add(order("N" + i, LocalDateTime.of(2026, 9, 1, 10, i), 0, String.valueOf(i * 10)));
        }
        when(orderInfoMapper.selectList(any())).thenReturn(orders);

        ToolResult r = exec(100L, args());
        assertTrue(r.isOk());
        assertTrue(r.getContent().contains("共找到 7 笔"), "应告知总数，实际=" + r.getContent());
        assertTrue(r.getContent().contains("前 5"), "应只展示前 5 候选");
        assertTrue(r.getContent().contains("确认是哪一笔"), "不应替用户静默选最近一单");
        assertFalse(r.getContent().contains("N6"), "第 6 笔不应出现在截断候选中");
    }

    @Test
    void nullUserId_denies() {
        ToolResult r = exec(null, args().put("latest", true));
        assertFalse(r.isOk());
    }
}
