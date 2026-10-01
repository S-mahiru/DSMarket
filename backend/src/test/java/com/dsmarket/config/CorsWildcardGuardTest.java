package com.dsmarket.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

/**
 * 误配成通配来源时<b>绝不能静默反射任意 Origin</b>（审计 §2.6）。
 *
 * <p>这条测的是"有人把 {@code web.cors.allowed-origins} 填成 {@code *}"这一种配置。
 * 危险的正是它的<b>静默</b>：{@code allowedOriginPatterns("*")} + {@code allowCredentials(true)}
 * 是 Spring 允许的组合，会老老实实把任意站点反射进 {@code Access-Control-Allow-Origin}
 * 并附上 {@code Access-Control-Allow-Credentials: true}，启动日志里一个字都没有。</p>
 *
 * <p><b>断言只写"不回显"，不写"报什么错"</b>：本测试钉的是
 * <b>「任意来源拿到放行头」这件事不会发生</b>，而"具体以什么方式不发生"是实现细节 ——
 * 写进断言等于把实现细节当成需求。</p>
 *
 * <p><b>2026-09-21 实测（用一次性断言探到，随后撤掉）</b>，两个事实与审计 §2.6 的原文不符，
 * 记在这里免得后人照抄：</p>
 * <ul>
 *   <li>Spring <b>不会</b>在启动期拒绝该组合 —— 上下文照常起来，转换发生在<b>请求期</b>；
 *       审计写的"启动时直接失败"不成立。</li>
 *   <li>请求期的表现是 <b>400</b>（不是 500）：Spring 抛的是 {@code IllegalArgumentException}，
 *       被 {@code GlobalExceptionHandler#handleIllegalArgument} 接住并按其既定做法回显了文案。</li>
 * </ul>
 * <p>⇒ 准确的说法是：**从「静默反射任意来源」变成「带 Origin 的请求一律 400」**。
 * 比原来响亮得多，但仍不是"启动期就报错" —— 一个不带 Origin 的请求（如 curl）不会触发它。</p>
 */
@SpringBootTest(properties = "web.cors.allowed-origins=*")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CorsWildcardGuardTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 通配来源下_任意Origin都拿不到放行头() throws Exception {
        mockMvc.perform(get("/api/v1/categories").header("Origin", "http://evil.example"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }
}
