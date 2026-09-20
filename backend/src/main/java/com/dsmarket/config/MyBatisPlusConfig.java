package com.dsmarket.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MyBatisPlusConfig {

    /**
     * 单页最大条数。公开接口（如 {@code /api/v1/products}，SecurityConfig 里是 permitAll）的
     * {@code size} 直接进到分页插件，不设上限时 {@code ?size=999999} 会变成一次匿名可发的全表扫描。
     * <p>
     * 100 的依据：现有全部调用点最大请求 20（前端 5/10/20，各 Controller 默认值 8~20），
     * 留 5 倍余量。取值方式是**静默截断**（超出即按 100 取），不是抛异常——这符合"封顶"而非"校验"的语义，
     * 也就不会把既有调用方打成 4xx。
     */
    private static final long MAX_PAGE_SIZE = 100L;

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.POSTGRE_SQL);
        pagination.setMaxLimit(MAX_PAGE_SIZE);
        interceptor.addInnerInterceptor(pagination);
        return interceptor;
    }
}
