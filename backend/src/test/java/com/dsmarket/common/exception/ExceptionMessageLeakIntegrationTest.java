package com.dsmarket.common.exception;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 异常原文不得回传给客户端（审计 §2.8）。
 *
 * <p><b>为什么必须走真实 DispatcherServlet</b>：本条的机制是
 * 「Spring 按异常继承深度挑最具体的 {@code @ExceptionHandler}」——
 * {@code NumberFormatException} 是 {@code IllegalArgumentException} 的子类，
 * 两个处理器同时存在时该挑谁，是<b>框架的行为</b>，不是我们方法里的逻辑。
 * 直接调 {@code handleNumberFormat(...)} 的单测把 {@code @ExceptionHandler} 注解删掉照样全绿，
 * 因而证明不了任何事（本项目已有的教训）。</p>
 *
 * <p>落点 {@code POST /api/v1/cart}：{@code CartController} 用
 * {@code Long.valueOf(String.valueOf(body.get("productId")))} 手解 Map，
 * 缺字段时 {@code String.valueOf(null)} 得到字符串 {@code "null"}，
 * 于是抛的正是 JDK 的 {@code For input string: "null"} —— 此前这句话会原样回给客户端。</p>
 *
 * <p>需登录，故本类自带夹具（前缀 {@code EXLEAK-}，已全仓 grep 确认未被占用）。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ExceptionMessageLeakIntegrationTest {

    private static final String PREFIX = "EXLEAK-";
    private static final String RAW_PASSWORD = "exleak-pass-123";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private BCryptPasswordEncoder passwordEncoder;

    private String token;

    @BeforeEach
    void setUp() throws Exception {
        jdbc.update("DELETE FROM dsm_user WHERE username LIKE ?", PREFIX + "%");
        jdbc.update("INSERT INTO dsm_user (username, password, role, status, deleted) VALUES (?, ?, 'USER', 1, 0)",
                PREFIX + "cart", passwordEncoder.encode(RAW_PASSWORD));
        token = login();
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM dsm_user WHERE username LIKE ?", PREFIX + "%");
    }

    /**
     * 正向对照：请求体完整时走的是业务逻辑，<b>不是</b>解析异常。
     *
     * <p>没有它，「整条链坏了 / 什么都回 400」会冒充成"原文没外泄"。</p>
     *
     * <p><b>这条对照与主用例的状态码是同一个 400</b>，所以区分二者<b>只能靠 message</b>：
     * 这里是业务文案"商品已下架或不存在"，主用例是兜底文案"参数格式错误"。
     * 状态码相同反而更利 —— 它证明两条路径确实是两个不同的处理器在应答，
     * 而不是"所有异常都被同一个兜底吞了、碰巧没吐出原文"。</p>
     */
    @Test
    void 正向对照_请求体完整时走业务判定而非解析异常() throws Exception {
        MvcResult result = postCart(Map.of("productId", 999999999L, "quantity", 1));

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(objectMapper.readTree(body(result)).path("message").asText())
                .as("走业务判定的文案与解析失败的兜底文案必须能区分开")
                .isEqualTo("商品已下架或不存在");
    }

    /** 修复的主用例：缺 {@code productId} → 400，且信封里没有 JDK 的解析原文。 */
    @Test
    void 缺productId不泄露JDK解析原文() throws Exception {
        MvcResult result = postCart(Map.of("quantity", 1));

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        String body = body(result);
        assertThat(body)
                .as("原文只该进服务端日志，不该出现在信封里")
                .doesNotContain("For input string")
                .doesNotContain("NumberFormatException")
                .doesNotContain("java.lang");
        assertThat(objectMapper.readTree(body).path("message").asText()).isEqualTo("参数格式错误");
    }

    /** 同上，但传的是非数字字符串 —— 两种入口都要堵。 */
    @Test
    void 非数字productId不泄露JDK解析原文() throws Exception {
        MvcResult result = postCart(Map.of("productId", "abc", "quantity", 1));

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(body(result))
                .doesNotContain("For input string")
                .doesNotContain("java.lang");
    }

    // ── 夹具 ────────────────────────────────────────────────────────────────

    private MvcResult postCart(Map<String, Object> payload) throws Exception {
        return mockMvc.perform(post("/api/v1/cart")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andReturn();
    }

    private String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private String login() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("username", PREFIX + "cart", "password", RAW_PASSWORD))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(body(result)).path("data").path("token").asText();
    }
}
