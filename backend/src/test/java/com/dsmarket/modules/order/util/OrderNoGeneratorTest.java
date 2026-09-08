package com.dsmarket.modules.order.util;

import com.dsmarket.common.constant.RedisKeyConstant;
import com.dsmarket.modules.order.mapper.OrderInfoMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 订单号生成器单元测试：Redis 正常路径 + Redis 宕机降级 DB 路径。
 */
@ExtendWith(MockitoExtension.class)
class OrderNoGeneratorTest {

    @Mock
    private RedisTemplate<String, Object> redisTemplate;
    @Mock
    private ValueOperations<String, Object> valueOperations;
    @Mock
    private OrderInfoMapper orderInfoMapper;

    @InjectMocks
    private OrderNoGenerator generator;

    @Test
    void generate_redisPath_returnsDatePrefixedSeq() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(anyString())).thenReturn(1L);

        String orderNo = generator.generate();

        assertTrue(orderNo.startsWith("DSM"));
        assertEquals(19, orderNo.length()); // DSM(3) + 8位日期 + 8位序号
        assertTrue(orderNo.endsWith("00000001"));
        // 首次序号设置 1 天过期
        verify(valueOperations).increment(startsWith(RedisKeyConstant.ORDER_SEQ));
        verify(redisTemplate).expire(anyString(), anyLong(), any());
    }

    @Test
    void generate_redisDown_fallsBackToDb() {
        // Redis 抛异常（宕机）→ 降级 DB count
        when(redisTemplate.opsForValue()).thenThrow(new RuntimeException("connection refused"));
        when(orderInfoMapper.selectCount(null)).thenReturn(41L);

        String orderNo = generator.generate();

        assertTrue(orderNo.startsWith("DSM"));
        assertTrue(orderNo.endsWith("00000042")); // count+1
        verify(orderInfoMapper).selectCount(null);
    }

    @Test
    void generate_incrementReturnsNull_fallsBackToDb() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(anyString())).thenReturn(null); // 异常场景
        when(orderInfoMapper.selectCount(null)).thenReturn(7L);

        String orderNo = generator.generate();

        assertTrue(orderNo.endsWith("00000008"));
    }
}
