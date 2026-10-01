package com.dsmarket.modules.product.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /api/v1/products/featured} 的分页参数防御（审计 §2.2）。
 *
 * <p>该端点在 {@code permitAll} 名单里，所以这是一个<b>任何人都能触发</b>的 500 ——
 * 这才是本条被列为 P1 的原因，不是"分页不够优雅"。</p>
 *
 * <p>LIMIT 夹取的精确结果（0 / 20 各自对应什么输入）由
 * {@link com.dsmarket.modules.product.service.impl.ProductFeaturedLimitTest} 断言 SQL 来覆盖；
 * 这里只管端到端那一层：<b>坏输入不该打到 5xx</b>。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FeaturedParamIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 正常limit返回200() throws Exception {
        mockMvc.perform(get("/api/v1/products/featured").param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    /** 修复的主用例：`limit=-5` 曾拼出 `LIMIT -5`，PostgreSQL 报错 ⇒ 500。 */
    @Test
    void 负数limit不再打成500() throws Exception {
        mockMvc.perform(get("/api/v1/products/featured").param("limit", "-5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    /**
     * 非数字 limit 属于<b>客户端</b>的错误，不该是 500。
     *
     * <p>这一条与负数那条同族但成因不同：负数是在我们自己拼的 SQL 里炸的，
     * 非数字是在 Spring 绑定时就失败的（{@code MethodArgumentTypeMismatchException}）。
     * 断言写 {@code is4xxClientError} 而不是写死 400 —— 具体给 400 还是回落到默认值 200，
     * 不是本测试要钉的契约；要钉的是"畸形输入不得变成服务端错误"。</p>
     */
    @Test
    void 非数字limit是客户端错误而非500() throws Exception {
        mockMvc.perform(get("/api/v1/products/featured").param("limit", "abc"))
                .andExpect(status().is4xxClientError());
    }
}
