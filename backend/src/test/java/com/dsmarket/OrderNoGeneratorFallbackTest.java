package com.dsmarket;

import com.dsmarket.modules.order.util.OrderNoGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Redis 宕机降级集成测试：把 Redis 指向一个无监听的端口（6399），模拟真实宕机。
 * 验证订单号生成走 DB 降级路径（真实装配 + 真实 DB count），且不抛异常。
 */
@SpringBootTest(properties = {
        "spring.data.redis.host=127.0.0.1",
        "spring.data.redis.port=6399"
})
@ActiveProfiles("test")
class OrderNoGeneratorFallbackTest {

    @Autowired
    private OrderNoGenerator orderNoGenerator;

    @Test
    void generate_fallsBackToDb_whenRedisUnavailable() {
        String orderNo = orderNoGenerator.generate();

        assertNotNull(orderNo);
        assertTrue(orderNo.startsWith("DSM"));
        assertEquals(19, orderNo.length()); // DSM + 8位日期 + 8位序号
    }
}
