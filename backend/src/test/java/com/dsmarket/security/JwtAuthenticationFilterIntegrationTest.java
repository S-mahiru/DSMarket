package com.dsmarket.security;

import com.dsmarket.common.constant.RedisKeyConstant;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link JwtAuthenticationFilter} 的「用户状态校验」集成测试（真实过滤器链 / 独立 {@code dsmarket_test} 库）。
 *
 * <p><b>为什么必须走 MockMvc 而不是直接调过滤器方法</b>：本次改动要验证的是
 * 「这个过滤器<b>会不会被框架调用</b>、以及它拒绝之后链上到底回什么状态码」。
 * 直接调 {@code doFilterInternal} 的单测验证不了后半段 —— 把 {@code SecurityConfig} 里的
 * {@code addFilterBefore} 整行删掉，那样的单测照样全绿。同理，状态码断言必须走真实
 * {@code DispatcherServlet}（本项目踩过「直接调方法的单测验证不了框架会不会调用它」）。</p>
 *
 * <p><b>每条「拒绝」都配一条正向对照</b>：否则「谓词写成了永假」「Redis 连不上导致全都 401」
 * 都能让拒绝类用例通过。本项目实测过 Redis 口令配错的表现就是<b>应用正常启动 + 全部已登录
 * 请求静默 401</b>，与 token 过期在客户端看来完全一样 —— 没有正向对照时，
 * 整类测试会因为环境坏了而"全绿"。</p>
 *
 * <p><b>A-B-A</b>：禁用那条用「有效 → 禁用 → 复原」三段，第三段是关键 ——
 * 它证明中间那次 401 是<b>状态位翻转</b>造成的，而不是 token 本身在某个环节被弄坏了。</p>
 *
 * <p>测试数据以 {@code JWTFLT-} 前缀命名（挑前缀前已全仓 grep 确认未被其它夹具占用），
 * {@code @BeforeEach} / {@code @AfterEach} 按前缀清理，不依赖库的初始状态与执行顺序。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class JwtAuthenticationFilterIntegrationTest {

    private static final String PREFIX = "JWTFLT-";
    private static final String RAW_PASSWORD = "jwtflt-pass-123";

    /** 已认证请求的落点：不在 permitAll 名单里，必然走 anyRequest().authenticated()。 */
    private static final String PROTECTED = "/api/v1/user/profile";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private BCryptPasswordEncoder passwordEncoder;
    @Autowired
    private JwtTokenProvider jwtTokenProvider;
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    /** 本用例签发过的 token。@AfterEach 用来清黑名单键 —— 否则每次跑都往共享 Redis 里留垃圾。 */
    private final List<String> issuedTokens = new ArrayList<>();

    @BeforeEach
    void cleanSlate() {
        jdbc.update("DELETE FROM dsm_user WHERE username LIKE ?", PREFIX + "%");
        issuedTokens.clear();
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM dsm_user WHERE username LIKE ?", PREFIX + "%");
        for (String token : issuedTokens) {
            redisTemplate.delete(RedisKeyConstant.TOKEN_BLACKLIST + token);
        }
        issuedTokens.clear();
    }

    // ── 用例 ────────────────────────────────────────────────────────────────

    /**
     * 正向对照：状态正常的账号，登录拿到的 token 能过受保护接口。
     * 没有这一条，下面所有 401 断言都无法排除"环境坏了/整条链全拒"。
     */
    @Test
    void 正常账号的token可以访问受保护接口() throws Exception {
        Long uid = createUser("healthy");
        String token = login("healthy");

        expectAuthenticated(token, uid);
    }

    /**
     * 本次修复的主用例：**先签发 token，再禁用账号**，那张 token 必须立刻失效。
     *
     * <p>顺序不能反 —— 先禁用再登录会走 {@code AuthServiceImpl} 已有的 403 分支，
     * 测到的是登录拦截，不是本过滤器的校验。（这正是修复前被漏掉的另一半。）</p>
     */
    @Test
    void 签发后被禁用同一个token立即失效_复原后恢复() throws Exception {
        Long uid = createUser("disabled");
        String token = login("disabled");

        // A：禁用前可用
        expectAuthenticated(token, uid);

        // B：仅翻转状态位，token 一个字节没动
        jdbc.update("UPDATE dsm_user SET status = 0 WHERE id = ?", uid);
        expectRejected(token);

        // A'：复原后同一个 token 又能用 —— 证明中间那次 401 确实是状态位造成的
        jdbc.update("UPDATE dsm_user SET status = 1 WHERE id = ?", uid);
        expectAuthenticated(token, uid);
    }

    /**
     * 账号被软删（{@code deleted = 1}）后，既有 token 同样失效。
     *
     * <p>这一条真正在测的是 {@code BaseMapper.selectById} 经 {@code @TableLogic} 自动附加的
     * {@code deleted = 0} 是否生效 —— 过滤器里没写这个条件，靠的是框架。若哪天有人绕过
     * MyBatis-Plus 写了自定义查询，这条会红。</p>
     */
    @Test
    void 签发后被软删同一个token立即失效() throws Exception {
        Long uid = createUser("softdel");
        String token = login("softdel");

        expectAuthenticated(token, uid);

        jdbc.update("UPDATE dsm_user SET deleted = 1 WHERE id = ?", uid);
        expectRejected(token);
    }

    /**
     * 回归：登出写黑名单这条既有路径没被本次改动破坏。
     * （本次动了 catch 结构，黑名单判定就在同一个 try 里，属于必须回归的范围。）
     */
    @Test
    void 登出后的token被拒() throws Exception {
        Long uid = createUser("logout");
        String token = login("logout");

        expectAuthenticated(token, uid);

        mockMvc.perform(post("/api/v1/auth/logout").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        expectRejected(token);
    }

    /**
     * 签名有效、但 {@code sub} 指向一个不存在的用户 → 拒绝。
     *
     * <p>这里用真实 {@link JwtTokenProvider} 签，模拟"攻击者拿到了密钥"（审计 §1.1 的场景）：
     * 验签这一关他过得去。修复前这种 token 会被直接授予 {@code ROLE_ADMIN}（角色也由 token 自带），
     * 现在至少要求该用户真实存在且状态正常。</p>
     */
    @Test
    void 签名有效但用户不存在的token被拒() throws Exception {
        String ghost = jwtTokenProvider.generateToken(999999999L, PREFIX + "ghost", "ADMIN");
        issuedTokens.add(ghost);

        expectRejected(ghost);
    }

    // ── 夹具与断言 ───────────────────────────────────────────────────────────

    private Long createUser(String suffix) {
        String username = PREFIX + suffix;
        jdbc.update("INSERT INTO dsm_user (username, password, role, status, deleted) VALUES (?, ?, 'USER', 1, 0)",
                username, passwordEncoder.encode(RAW_PASSWORD));
        return jdbc.queryForObject("SELECT id FROM dsm_user WHERE username = ?", Long.class, username);
    }

    /** 走真实登录接口拿 token —— 不手工造，确保拿到的是线上同款。 */
    private String login(String suffix) throws Exception {
        String body = objectMapper.writeValueAsString(
                Map.of("username", PREFIX + suffix, "password", RAW_PASSWORD));
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();
        String token = objectMapper
                .readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("data").path("token").asText();
        issuedTokens.add(token);
        return token;
    }

    /** 正向：能进受保护接口，且拿到的确实是这个账号自己的资料。 */
    private void expectAuthenticated(String token, Long expectedUserId) throws Exception {
        mockMvc.perform(get(PROTECTED).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                // 同时断言"认出来的是谁"：只看 200 的话，认证成了别人的账号也能通过
                .andExpect(jsonPath("$.data.id").value(expectedUserId));
    }

    /**
     * 反向：被拒。
     *
     * <p>状态码与响应体<b>一起断言</b> —— 只断 401 的话，区分不出这是
     * {@code SecurityConfig} 里那个 authenticationEntryPoint 给出的 401，
     * 还是别的环节碰巧也回了 401（本项目的错误响应断言纪律）。</p>
     */
    private void expectRejected(String token) throws Exception {
        mockMvc.perform(get(PROTECTED).header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("未登录或Token已过期"));
    }
}
