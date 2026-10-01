package com.dsmarket.modules.product.service.impl;

import com.dsmarket.modules.product.service.ProductService;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code getFeatured(int limit)} 真正下发到数据库的那段 SQL（审计 §2.2）。
 *
 * <p><b>为什么不能断言返回条数</b>：本项目的库只有个位数商品，所以"返回条数 ≤ 20"
 * 这种断言<b>恒真</b> —— 上界夹没夹住它都会绿。库里的数据量够不着这个判据。</p>
 *
 * <p><b>为什么也不能断言 wrapper 里的字段</b>：MyBatis-Plus 的 {@code lastSql} 是
 * {@code AbstractWrapper} 上的 {@code protected} 字段，没有公开读取口。</p>
 *
 * <p>故这里挂一个 MyBatis 拦截器，抓 {@code StatementHandler#prepare} 拿到的
 * {@code BoundSql} —— 那是<b>真正要发给 PostgreSQL 的那段文本</b>。这也是本项目
 * 验「分页上限是否生效」时用过的同一把尺子（当时是看 SQL 参数里的 {@code 100(Long)}）。</p>
 *
 * <p>该拦截器只在<b>本测试类</b>的上下文里注册（{@code @Import} 一个 {@code @TestConfiguration}），
 * 不污染其它测试。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(ProductFeaturedLimitTest.SqlCaptureConfig.class)
class ProductFeaturedLimitTest {

    @Autowired
    private ProductService productService;

    @Autowired
    private SqlCaptureInterceptor sqlCapture;

    @BeforeEach
    void clear() {
        sqlCapture.statements.clear();
    }

    /** 只取"推荐位那次查询"的 SQL，避免同请求里其它语句混进来。 */
    private String featuredSql() {
        return sqlCapture.statements.stream()
                .filter(s -> s.contains("is_featured"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("没有抓到推荐位查询，实际抓到：" + sqlCapture.statements));
    }

    /** 正向对照：正常取值原样透传 —— 没有它，一个"永远拼 LIMIT 0"的实现也能让下面几条通过。 */
    @Test
    void 正常取值原样透传() {
        productService.getFeatured(5);
        assertThat(featuredSql()).contains("LIMIT 5");
    }

    /** 修复的主用例：负数此前拼出 `LIMIT -5`，PostgreSQL 直接报错 ⇒ 500。 */
    @Test
    void 负数被夹到0() {
        productService.getFeatured(-5);
        String sql = featuredSql();
        assertThat(sql).contains("LIMIT 0");
        assertThat(sql).doesNotContain("LIMIT -5");
    }

    /**
     * {@code limit = 0} 仍然是 {@code LIMIT 0}，<b>不被抬到 1</b>。
     *
     * <p>刻意如此：{@code LIMIT 0} 是合法 SQL，改动前就是这个行为（返回空列表）。
     * 夹到下界 1 会悄悄改掉一个本来正常的响应 —— 本次只修真正坏掉的那一种输入。</p>
     */
    @Test
    void 零保持为零不被抬高() {
        productService.getFeatured(0);
        assertThat(featuredSql()).contains("LIMIT 0");
    }

    /** 上界：超限一律夹到 20。 */
    @Test
    void 超限夹到20() {
        productService.getFeatured(100);
        assertThat(featuredSql()).contains("LIMIT 20");
    }

    // ── 夹具 ────────────────────────────────────────────────────────────────

    @TestConfiguration
    static class SqlCaptureConfig {
        @Bean
        SqlCaptureInterceptor sqlCaptureInterceptor() {
            return new SqlCaptureInterceptor();
        }
    }

    @Intercepts(@Signature(type = StatementHandler.class, method = "prepare",
            args = {Connection.class, Integer.class}))
    static class SqlCaptureInterceptor implements Interceptor {

        final List<String> statements = Collections.synchronizedList(new ArrayList<>());

        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            StatementHandler handler = (StatementHandler) invocation.getTarget();
            statements.add(handler.getBoundSql().getSql());
            return invocation.proceed();
        }
    }
}
