package com.dsmarket.modules.ai.context;

import com.dsmarket.modules.ai.dto.ChatRequest;
import com.dsmarket.modules.order.mapper.OrderInfoMapper;
import com.dsmarket.modules.product.entity.Product;
import com.dsmarket.modules.product.mapper.ProductMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * DbChatScenario：归属/上架校验后才注入，越权/缺失一律忽略（REQ C1 §2）。
 */
class DbChatScenarioTest {

    private static final long USER = 100L;

    private ChatRequest.Context ctx(ChatRequest.ChatPage page) {
        ChatRequest.Context c = new ChatRequest.Context();
        c.setPage(page);
        return c;
    }

    private ChatRequest.Context orderCtx(String orderNo) {
        ChatRequest.Context c = ctx(ChatRequest.ChatPage.ORDER_DETAIL);
        c.setOrderNo(orderNo);
        return c;
    }

    @Test
    void nullContext_orNullPage_returnsEmpty() {
        OrderInfoMapper order = mock(OrderInfoMapper.class);
        ProductMapper product = mock(ProductMapper.class);
        DbChatScenario s = new DbChatScenario(order, product);
        assertEquals("", s.resolve(USER, null));
        assertEquals("", s.resolve(USER, ctx(ChatRequest.ChatPage.HOME)));
        assertEquals("", s.resolve(USER, ctx(ChatRequest.ChatPage.ORDER_LIST)));
    }

    @Test
    void orderDetail_owned_injectsScenario() {
        OrderInfoMapper order = mock(OrderInfoMapper.class);
        when(order.selectCount(any())).thenReturn(1L);
        ProductMapper product = mock(ProductMapper.class);
        DbChatScenario s = new DbChatScenario(order, product);

        String line = s.resolve(USER, orderCtx("O20260908X"));
        assertTrue(line.contains("O20260908X"), "归属校验通过应注入订单号");
    }

    @Test
    void orderDetail_notOwned_ignored() {
        OrderInfoMapper order = mock(OrderInfoMapper.class);
        when(order.selectCount(any())).thenReturn(0L); // 他人/不存在 → 0
        DbChatScenario s = new DbChatScenario(order, mock(ProductMapper.class));
        assertEquals("", s.resolve(USER, orderCtx("O20260908X")), "越权/不存在订单不得注入");
    }

    @Test
    void orderDetail_blankOrTooLongOrderNo_ignored() {
        DbChatScenario s = new DbChatScenario(mock(OrderInfoMapper.class), mock(ProductMapper.class));
        assertEquals("", s.resolve(USER, orderCtx("   ")));
        assertEquals("", s.resolve(USER, orderCtx(null)));
        assertEquals("", s.resolve(USER, orderCtx("O".repeat(200))));
    }

    @Test
    void productDetail_onSale_injects() {
        Product p = new Product();
        p.setName("深海蓝牙耳机");
        p.setStatus(1);
        ProductMapper product = mock(ProductMapper.class);
        when(product.selectById(eq(9L))).thenReturn(p);
        DbChatScenario s = new DbChatScenario(mock(OrderInfoMapper.class), product);

        ChatRequest.Context c = ctx(ChatRequest.ChatPage.PRODUCT_DETAIL);
        c.setProductId(9L);
        String line = s.resolve(USER, c);
        assertTrue(line.contains("深海蓝牙耳机"), "上架商品应注入，实际=" + line);
    }

    @Test
    void productDetail_missingOrOffShelf_ignored() {
        ProductMapper product = mock(ProductMapper.class);
        when(product.selectById(eq(1L))).thenReturn(null);
        Product off = new Product();
        off.setName("已下架");
        off.setStatus(0);
        when(product.selectById(eq(2L))).thenReturn(off);
        DbChatScenario s = new DbChatScenario(mock(OrderInfoMapper.class), product);

        ChatRequest.Context c1 = ctx(ChatRequest.ChatPage.PRODUCT_DETAIL);
        c1.setProductId(1L);
        ChatRequest.Context c2 = ctx(ChatRequest.ChatPage.PRODUCT_DETAIL);
        c2.setProductId(2L);
        ChatRequest.Context c3 = ctx(ChatRequest.ChatPage.PRODUCT_DETAIL); // 缺 productId
        assertEquals("", s.resolve(USER, c1), "不存在 → 忽略");
        assertEquals("", s.resolve(USER, c2), "已下架 → 忽略");
        assertEquals("", s.resolve(USER, c3), "缺 productId → 忽略");
    }

    @Test
    void dbError_degradesToEmpty_notBlocking() {
        OrderInfoMapper order = mock(OrderInfoMapper.class);
        when(order.selectCount(any())).thenThrow(new RuntimeException("db down"));
        DbChatScenario s = new DbChatScenario(order, mock(ProductMapper.class));
        assertEquals("", s.resolve(USER, orderCtx("O20260908X")), "DB 异常 → 降级不注入，不抛错");
    }
}
