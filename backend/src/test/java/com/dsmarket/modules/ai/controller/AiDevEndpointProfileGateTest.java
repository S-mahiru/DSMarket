package com.dsmarket.modules.ai.controller;

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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AI 模块<b>调试 / 装配自检端点</b>的 profile 门禁测试（审计 12-readiness-audit §1.5）。
 *
 * <p>覆盖 {@code AiDevController} 的四个端点：{@code GET /meta}、{@code POST /dev/chat}、
 * {@code POST /dev/knowledge-search}、{@code POST /dev/knowledge-routes}。
 * 该类带 {@code @Profile("dev")}，本测试跑在 {@code test} 档案下，故四个端点应当<b>全部不存在</b>。
 * 同批一并回归旧路径 {@code GET /api/v1/ai/meta} —— 它随 {@code meta} 迁走，现在也该是 404。</p>
 *
 * <h3>为什么必须带真实 token，而不能直接匿名打</h3>
 * <p>{@code SecurityConfig} 的 {@code anyRequest().authenticated()} 在<b>路由之前</b>生效，
 * 匿名访问任何 {@code /api/v1/**} 都是 <b>401</b> —— 端点存在与不存在<b>不可区分</b>。
 * 这正是本项目踩过的坑：{"未登录时 404 探针永远不会响"}。所以本类先走真实登录接口拿 token。</p>
 *
 * <h3>为什么必须有正向对照</h3>
 * <p>本类断言的全是拒绝类结果。若只写它们，那么「令牌链坏了，所有请求都 404/401」
 * 与「门禁生效，端点确实没了」会给出<b>同一片绿</b>。{@code 正向对照…} 那条用<b>同一枚 token</b>
 * 打一个非 dev 门禁的受保护接口并要求 200 —— 它绿，才说明下面那些 404 是端点缺失造成的。</p>
 *
 * <h3>本测试的敏感性是怎么证明的（反向突变，已执行）</h3>
 * <p>摘掉 {@code AiDevController} 类上的 {@code @Profile("dev")} 后重跑：本类四个 404 用例
 * <b>全部转红</b>（收到 200/400 而非 404）。这一步同时是<b>正向方向</b>的证据 ——
 * 它证明 controller 本身、{@code @GetMapping("/meta")} 的路径、以及
 * {@code @RequestMapping("/api/v1/ai/dev")} 的前缀都是对的，端点在装配时确实可达。</p>
 *
 * <p>之所以绕这一下、而不是直接起一个 {@code dev} 档的上下文来做正向：
 * {@code @ActiveProfiles({"test","dev"})} 会让 {@code application-dev.yml} 参与装配，
 * 而它把数据源指向<b>真·开发库 {@code dsmarket}</b>，测试会写进真库；
 * 且 {@code DataInitializer}（同为 {@code @Profile("dev")} 的 {@code CommandLineRunner}）
 * 会往库里播种固定口令的 admin。两个副作用都不能接受。</p>
 *
 * <p>夹具以 {@code AIDEV-} 前缀命名（落笔前已全仓 grep 确认未被其它夹具占用），
 * {@code @BeforeEach} / {@code @AfterEach} 按前缀清理，不依赖库的初始状态与执行顺序。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AiDevEndpointProfileGateTest {

    private static final String PREFIX = "AIDEV-";
    private static final String RAW_PASSWORD = "aidev-pass-123";

    /** 正向对照落点：受保护、且<b>不</b>带 dev 门禁，与认证链是否健康无关地被任何改动波及。 */
    private static final String CONTROL = "/api/v1/user/profile";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private BCryptPasswordEncoder passwordEncoder;

    private Long userId;
    private String token;

    @BeforeEach
    void setUp() throws Exception {
        jdbc.update("DELETE FROM dsm_user WHERE username LIKE ?", PREFIX + "%");
        userId = createUser();
        token = login();
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM dsm_user WHERE username LIKE ?", PREFIX + "%");
    }

    // ── 正向对照 ────────────────────────────────────────────────────────────

    /**
     * 承重条：同一枚 token 打普通受保护接口必须 200 且认得出是这个账号。
     * 没有它，下面所有 404 都无法排除"环境坏了 / 令牌链全拒"。
     */
    @Test
    void 正向对照_同一枚token能过普通受保护接口() throws Exception {
        mockMvc.perform(get(CONTROL).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value(userId));
    }

    // ── 门禁：四个端点都必须在非 dev 档案下消失 ──────────────────────────────

    /** 本批新收口的那个。它迁入 dev 命名空间后为 {@code /api/v1/ai/dev/meta}。 */
    @Test
    void 装配自检meta在非dev档案下不存在() throws Exception {
        expectGetAbsent("/api/v1/ai/dev/meta");
    }

    /** 上一批收口的三个。此前只有真机 A/B 证据，本类把它们的门禁第一次固化成回归测试。 */
    @Test
    void 调试端点chat在非dev档案下不存在() throws Exception {
        expectPostAbsent("/api/v1/ai/dev/chat");
    }

    @Test
    void 调试端点knowledgeSearch在非dev档案下不存在() throws Exception {
        expectPostAbsent("/api/v1/ai/dev/knowledge-search");
    }

    @Test
    void 调试端点knowledgeRoutes在非dev档案下不存在() throws Exception {
        expectPostAbsent("/api/v1/ai/dev/knowledge-routes");
    }

    /**
     * 旧路径回归：{@code meta} 迁走后 {@code GET /api/v1/ai/meta} 必须不再存在。
     *
     * <p>这条挡的是"迁移时图省事，把 {@code @GetMapping("/meta")} 留在了 {@code AiController} 上"
     * 这类半途而废 —— 那种情况下端点仍在生产路径上，而上面那条 dev 路径的 404 照样绿。</p>
     */
    @Test
    void 旧路径meta已不再存在() throws Exception {
        expectGetAbsent("/api/v1/ai/meta");
    }

    // ── 夹具与断言 ───────────────────────────────────────────────────────────

    /**
     * 断言路径<b>未被映射</b>。
     *
     * <p>状态码与响应体<b>一起断言</b>：只看 404 的话，区分不出这是
     * {@code GlobalExceptionHandler} 的 {@code NoResourceFoundException} 分支（未匹配到处理器），
     * 还是别的环节碰巧也回了 404（本项目的错误响应断言纪律）。</p>
     */
    private void expectGetAbsent(String path) throws Exception {
        mockMvc.perform(get(path).header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value("资源不存在"));
    }

    private void expectPostAbsent(String path) throws Exception {
        mockMvc.perform(post(path).header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value("资源不存在"));
    }

    private Long createUser() {
        String username = PREFIX + "probe";
        jdbc.update("INSERT INTO dsm_user (username, password, role, status, deleted) VALUES (?, ?, 'USER', 1, 0)",
                username, passwordEncoder.encode(RAW_PASSWORD));
        return jdbc.queryForObject("SELECT id FROM dsm_user WHERE username = ?", Long.class, username);
    }

    /** 走真实登录接口拿 token —— 不手工造，确保拿到的是线上同款。 */
    private String login() throws Exception {
        String body = objectMapper.writeValueAsString(
                Map.of("username", PREFIX + "probe", "password", RAW_PASSWORD));
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();
        return objectMapper
                .readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("data").path("token").asText();
    }
}
