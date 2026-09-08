package com.dsmarket.modules.order.util;

import com.dsmarket.common.constant.RedisKeyConstant;
import com.dsmarket.modules.order.mapper.OrderInfoMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;

/**
 * 订单号生成器：Redis INCR 原子递增 + 日期前缀。
 * 格式：DSM + yyyyMMdd + 8位序号（如 DSM20260816 00000001）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderNoGenerator {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.BASIC_ISO_DATE;

    private final RedisTemplate<String, Object> redisTemplate;
    private final OrderInfoMapper orderInfoMapper;

    public String generate() {
        String date = LocalDate.now().format(DATE_FMT);
        String key = RedisKeyConstant.ORDER_SEQ + date;
        Long seq = null;
        try {
            seq = redisTemplate.opsForValue().increment(key);
            if (seq != null && seq == 1L) {
                redisTemplate.expire(key, 1, TimeUnit.DAYS);
            }
        } catch (Exception e) {
            // Redis 宕机降级为 DB 方式
            log.warn("Redis 订单号生成失败，降级 DB 方式: {}", e.getMessage());
        }
        if (seq == null) {
            seq = orderInfoMapper.selectCount(null) + 1;
        }
        return "DSM" + date + String.format("%08d", seq);
    }
}
