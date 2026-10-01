package com.dsmarket.security;

import com.dsmarket.common.constant.RedisKeyConstant;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link JwtAuthenticationFilter} 的「用户状态校验 + 口令版本校验」集成测试
 * （真实过滤器链 / 独立 {@code dsmarket_test} 库）。
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
    /** 改密用例用的新口令（长度满足 {@code UpdatePasswordRequest} 的 6~32 位约束）。 */
    private static final String NEW_PASSWORD = "jwtflt-pass-456";

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
    /**
     * 用于手工签「修复前格式」的 token（见 {@code 无口令版本声明的旧格式token被拒}）。
     * 与 {@code JwtTokenProvider} 读的是同一个属性，故测试里手签的 token 验签必然通过。
     */
    @Value("${jwt.secret}")
    private String jwtSecret;

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
     * 本次修复的主用例：**先签发 token，再改口令**，那张 token 必须立刻失效（审计 §2.5）。
     *
     * <p>走真实改密接口 {@code PUT /api/v1/user/password}，而不是直接 {@code UPDATE dsm_user}
     * —— 要覆盖的是「接口改密 → 服务层写库 → 老 token 失效」这条完整链路，
     * 直接改库会把服务层可能存在的写入缺陷（比如口令压根没落库）绕过去。</p>
     *
     * <p><b>第三段（新口令签发的 token 可用）是承重的，不是凑数</b>：没有它，
     * 「过滤器把所有人都拒了」会冒充成「改密吊销生效」—— 只看中间那次 401 的话，两者一模一样。
     * 本项目实测过环境坏掉时全部已登录请求静默 401，正是这个形态。</p>
     */
    @Test
    void 改密后旧token立即失效_新口令签发的token可用() throws Exception {
        Long uid = createUser("pwdchange");
        String oldToken = login("pwdchange");

        // A：改密前，这枚 token 可用
        expectAuthenticated(oldToken, uid);

        // B：改口令 —— token 一个字节没动，改的是库里的口令哈希
        String body = objectMapper.writeValueAsString(
                Map.of("oldPassword", RAW_PASSWORD, "newPassword", NEW_PASSWORD));
        mockMvc.perform(put("/api/v1/user/password")
                        .header("Authorization", "Bearer " + oldToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        expectRejected(oldToken);

        // A'：新口令重新登录拿到的新 token 必须能用
        expectAuthenticated(login("pwdchange", NEW_PASSWORD), uid);
    }

    /**
     * 反向对照：改<b>昵称</b>不应影响已签发的 token。
     *
     * <p>守的是「版本号只跟口令走」。若哪天有人把实现换成 {@code updated_at} 之类
     * 「这行记录动过就失效」，改头像、改昵称都会把用户踢下线 —— 这条会红。</p>
     */
    @Test
    void 改昵称不影响已签发的token() throws Exception {
        Long uid = createUser("nick");
        String token = login("nick");
        expectAuthenticated(token, uid);

        String body = objectMapper.writeValueAsString(Map.of("nickname", PREFIX + "nick-renamed"));
        mockMvc.perform(put("/api/v1/user/profile")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        expectAuthenticated(token, uid);
    }

    /**
     * 本修复上线前签发的 token（签名有效、账号存在且状态正常，唯独没有 {@code pv} 声明）
     * 一律失效 —— 对外表现为一次性的「全体重新登录」。
     *
     * <p><b>这是刻意的取舍，不是副作用</b>：若放行无声明的 token，就等于给「改密吊销」
     * 留一个最长 24h（{@code jwt.expiration}）的失效盲区，而那正是本次要堵的东西。
     * 用一次全员重登换「改密即时生效」，是值得的。</p>
     *
     * <p><b>正向对照写在同一用例里</b>：同一账号走正常登录路径的 token 通过、手工签的这枚被拒，
     * 两者<b>唯一的自变量就是 pv 声明</b>。分开写两条用例的话，这一条红了你分不清
     * 是「声明校验生效」还是「账号没建好 / 密钥对不上」。</p>
     */
    @Test
    void 无口令版本声明的旧格式token被拒() throws Exception {
        Long uid = createUser("legacy");

        // 正向对照：同一个人、同一把密钥，走正常签发 ⇒ 通过
        expectAuthenticated(login("legacy"), uid);

        // 手工签一枚「修复前格式」的 token：除了没有 pv，其余与线上同款
        String legacy = Jwts.builder()
                .subject(String.valueOf(uid))
                .claim("username", PREFIX + "legacy")
                .claim("role", "USER")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 3600_000L))
                .signWith(Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8)))
                .compact();
        issuedTokens.add(legacy);

        expectRejected(legacy);
    }

    /**
     * 签名有效、但 {@code sub} 指向一个不存在的用户 → 拒绝。
     *
     * <p>这里用真实 {@link JwtTokenProvider} 签，模拟"攻击者拿到了密钥"（审计 §1.1 的场景）：
     * 验签这一关他过得去。修复前这种 token 会被直接授予 {@code ROLE_ADMIN}（角色也由 token 自带），
     * 现在至少要求该用户真实存在且状态正常。</p>
     *
     * <p>口令哈希传的是一串占位串：过滤器在校验 pv <b>之前</b>先判用户是否存在，
     * 故这条用例拒掉它的仍然是「查无此人」，与被测的口令版本机制无关。</p>
     */
    @Test
    void 签名有效但用户不存在的token被拒() throws Exception {
        String ghost = jwtTokenProvider.generateToken(
                999999999L, PREFIX + "ghost", "ADMIN", "$2a$10$not-a-real-bcrypt-hash-placeholder");
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
        return login(suffix, RAW_PASSWORD);
    }

    /** 同上，但指定口令（改密之后要用新口令重新登录才拿得到能用的 token）。 */
    private String login(String suffix, String password) throws Exception {
        String body = objectMapper.writeValueAsString(
                Map.of("username", PREFIX + suffix, "password", password));
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
