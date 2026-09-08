package com.dsmarket.modules.ai.context;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dsmarket.modules.ai.dto.ChatRequest;
import com.dsmarket.modules.order.entity.OrderInfo;
import com.dsmarket.modules.order.mapper.OrderInfoMapper;
import com.dsmarket.modules.product.entity.Product;
import com.dsmarket.modules.product.mapper.ProductMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 场景解析的 DB 实现：仅在能"证明"页面对象对当前用户有效时才注入，否则忽略（REQ C1 §2）。
 *
 * <ul>
 *   <li>order_detail：orderNo 归属当前用户（写死 user_id）→ 注入订单场景；否则忽略；</li>
 *   <li>product_detail：商品存在且上架（status=1）→ 注入；否则忽略；</li>
 *   <li>home / order_list / 无 context → 不注入（无具象锚点）。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DbChatScenario implements ChatScenario {

    /** 订单号长度护栏：过长按无效忽略，防提示词注入 */
    private static final int ORDER_NO_MAX = 64;

    private final OrderInfoMapper orderInfoMapper;
    private final ProductMapper productMapper;

    @Override
    public String resolve(Long userId, ChatRequest.Context context) {
        if (context == null || context.getPage() == null) {
            return "";
        }
        try {
            return switch (context.getPage()) {
                case ORDER_DETAIL -> orderScenario(userId, context.getOrderNo());
                case PRODUCT_DETAIL -> productScenario(context.getProductId());
                case HOME, ORDER_LIST -> "";
            };
        } catch (Exception e) {
            // 上下文查证失败一律降级为不注入，不阻塞对话
            log.warn("[ai] 场景解析异常，忽略页面上下文. userId={} err={}", userId, e.getMessage());
            return "";
        }
    }

    private String orderScenario(Long userId, String orderNo) {
        if (orderNo == null) {
            return "";
        }
        String no = orderNo.trim();
        if (no.isEmpty() || no.length() > ORDER_NO_MAX) {
            return "";
        }
        Long owned = orderInfoMapper.selectCount(new LambdaQueryWrapper<OrderInfo>()
                .eq(OrderInfo::getUserId, userId)
                .eq(OrderInfo::getOrderNo, no));
        if (owned == null || owned == 0) {
            return ""; // 他人订单或不存在 → 忽略（不注入）
        }
        return "【页面上下文】用户当前停留在「订单详情」页，正在查看本人订单 " + no + "；"
                + "如用户问及该单，可用 query_my_order 传该订单号精确查询。";
    }

    private String productScenario(Long productId) {
        if (productId == null) {
            return "";
        }
        Product p = productMapper.selectById(productId);
        if (p == null || p.getStatus() == null || p.getStatus() != 1) {
            return ""; // 不存在/已下架 → 忽略
        }
        String name = p.getName() == null ? "商品" : p.getName();
        return "【页面上下文】用户当前停留在「商品详情」页，正在浏览在售商品「" + name + "」（id=" + productId + "）。";
    }
}
