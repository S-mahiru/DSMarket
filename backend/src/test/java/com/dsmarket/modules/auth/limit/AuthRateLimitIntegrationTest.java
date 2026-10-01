package com.dsmarket.modules.auth.limit;

import com.dsmarket.common.constant.RedisKeyConstant;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 登录 / 注册限流重做（审计 §2.4）—— 走真实 {@code DispatcherServlet}。
 *
 * <p>阈值在这里被显式下调（{@code account-limit=3} 等），而不是靠默认值：
 * 默认值属于"策略"，本类要证的是"机制"。{@code base-block-seconds=1} 是为了让
 * "封锁会过期"这一条能在测试里等到，不必睡 60 秒。</p>
 *
 * <p><b>本类同时是信任边界的集成证明</b>：{@code trust-proxy-header=true} 下，
 * 每个用例都用不同的 {@code X-Real-IP} 值分桶。若该头被忽略（所有请求都落回
 * MockMvc 的 {@code remoteAddr}=127.0.0.1），{@link #攻击者打满账号不影响受害者从其他IP登录}
 * 会立刻转红 —— 因为受害者的那次登录会落进攻击者的桶里。</p>
 *
 * <p><b>用例设计上的两条纪律</b>（本项目踩过的坑）：</p>
 * <ul>
 *   <li>「什么都 429」必须能与「拦截生效」区分开 ⇒ 每条拒绝类断言都配正向对照
 *       （{@link #失败未达阈值时用正确口令仍能登录}、{@link #封锁到期后自动解除}、
 *       {@link #攻击者打满账号不影响受害者从其他IP登录}、{@link #注册按来源IP限流} 里的换 IP 注册）。</li>
 *   <li>只断言状态码会放过假 PASS（处理器失败但 Spring 兜底碰巧同码）⇒ 429 一律连
 *       {@code message} 一起断言。</li>
 * </ul>
 */
@SpringBootTest(properties = {
        "security.client-ip.trust-proxy-header=true",
        "security.auth-rate-limit.account-limit=3",
        "security.auth-rate-limit.ip-limit=5",
        "security.auth-rate-limit.register-limit=2",
        "security.auth-rate-limit.base-block-seconds=1",
        "security.auth-rate-limit.max-block-seconds=8"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthRateLimitIntegrationTest {

    private static final String PREFIX = "RATELIM_";
    private static final String RAW_PASSWORD = "ratelim-pass-123";

    private static final String IP_A = "10.1.1.1";
    private static final String IP_B = "10.2.2.2";
    private static final String IP_C = "10.3.3.3";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private BCryptPasswordEncoder passwordEncoder;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private AuthRateLimitProperties properties;

    /**
     * 先证本类的前提成立：阈值确实被本类的 {@code properties} 下调了。
     *
     * <p>没有这一条，下面所有"达阈值即封锁"的用例都可以因为<b>阈值根本没生效</b>
     * （落回默认 5/20，或落回测试 profile 的 1000000）而以另一种方式变红，
     * 而红的原因与要验的机制无关 —— 本项目已有"先证基线干净"的纪律。</p>
     */
    @Test
    void 测试前提_阈值已按本类声明下调() {
        assertThat(properties.isEnabled()).isTrue();
        assertThat(properties.getAccountLimit()).isEqualTo(3);
        assertThat(properties.getIpLimit()).isEqualTo(5);
        assertThat(properties.getRegisterLimit()).isEqualTo(2);
        assertThat(properties.getBaseBlockSeconds()).isEqualTo(1L);
    }

    @BeforeEach
    void setUp() {
        cleanRedis();
        cleanUsers();
        jdbc.update("INSERT INTO dsm_user (username, password, role, status, deleted) VALUES (?, ?, 'USER', 1, 0)",
                PREFIX + "victim", passwordEncoder.encode(RAW_PASSWORD));
    }

    @AfterEach
    void tearDown() {
        cleanRedis();
        cleanUsers();
    }

    // ── 失败才计数：反向账号锁定 DoS ────────────────────────────────────────

    /**
     * 核心修正：失败<b>未达</b>阈值时，正确的口令必须能登进去。
     *
     * <p>修复前的实现把每次尝试都计入（含成功的），于是攻击者只要对某个已知用户名连发
     * 5 条垃圾请求，真正的用户就被挡在门外 —— 而这一步<b>不需要猜中任何口令</b>。
     * 本用例在旧实现下会拿到 429。</p>
     */
    @Test
    void 失败未达阈值时用正确口令仍能登录() throws Exception {
        assertThat(login(PREFIX + "victim", "wrong-1", IP_A).getResponse().getStatus()).isEqualTo(400);
        assertThat(login(PREFIX + "victim", "wrong-2", IP_A).getResponse().getStatus()).isEqualTo(400);

        MvcResult result = login(PREFIX + "victim", RAW_PASSWORD, IP_A);

        assertThat(result.getResponse().getStatus())
                .as("阈值是 3，只失败 2 次；第 3 次带对口令必须放行")
                .isEqualTo(200);
    }

    /** 达阈值后即封锁，且拒绝形态是 <b>429 + 可读文案</b>（只断状态码会放过 Spring 兜底同码的假 PASS）。 */
    @Test
    void 达阈值后同账号同IP被拒且是429() throws Exception {
        for (int i = 0; i < 3; i++) {
            login(PREFIX + "victim", "wrong-" + i, IP_A);
        }

        MvcResult result = login(PREFIX + "victim", RAW_PASSWORD, IP_A);

        assertThat(result.getResponse().getStatus()).isEqualTo(429);
        assertThat(objectMapper.readTree(body(result)).path("message").asText())
                .as("必须是我们限流器写的文案，不是别的 429")
                .contains("登录失败次数过多");
    }

    /**
     * 反向锁定 DoS 已解：攻击者的失败只锁住"攻击者 IP × 该账号"这一格。
     *
     * <p>同时是信任边界的证明 —— 两次请求只有 {@code X-Real-IP} 不同，
     * 若该头被忽略，两次会落进同一个桶，受害者那次必红。</p>
     */
    @Test
    void 攻击者打满账号不影响受害者从其他IP登录() throws Exception {
        for (int i = 0; i < 3; i++) {
            login(PREFIX + "victim", "wrong-" + i, IP_A);
        }

        assertThat(login(PREFIX + "victim", RAW_PASSWORD, IP_A).getResponse().getStatus())
                .as("攻击者自己被封，这是预期")
                .isEqualTo(429);
        assertThat(login(PREFIX + "victim", RAW_PASSWORD, IP_B).getResponse().getStatus())
                .as("受害者从自己的 IP 必须照常登录 —— 否则账号维度就是全局锁，攻击者可无差别锁人")
                .isEqualTo(200);
    }

    /**
     * 成功登录<b>不</b>消耗 IP 额度：连登 7 次（ip-limit=5）也不该被封。
     *
     * <p>这条抓的是"每次尝试都计数"那种实现。它与"只对失败计数"在<b>纯失败</b>序列上
     * 完全等价（都是第 limit 次失败后拦第 limit+1 次），所以只在成功路径上分得开 ——
     * 而 IP 桶刻意不因成功清零，成功一计数，同一出口下的正常用户就会把自己人全锁死。</p>
     *
     * <p>这条是<b>被反向突变逼出来的</b>：没有它时，把实现改回"每次尝试都计数"，
     * 全类用例照样绿。</p>
     */
    @Test
    void 成功登录不把自己的IP送进封锁() throws Exception {
        for (int i = 0; i < 7; i++) {
            assertThat(login(PREFIX + "victim", RAW_PASSWORD, IP_B).getResponse().getStatus())
                    .as("第 " + (i + 1) + " 次正常登录；ip-limit=5，只要成功不计入就永远到不了")
                    .isEqualTo(200);
        }
    }

    // ── IP 维度：密码喷洒 ───────────────────────────────────────────────────

    /**
     * 密码喷洒（固定弱口令、遍历用户名）此前<b>完全不触发</b>任何限流：
     * 计数维度是裸用户名，每个受害者各占一个桶，各自永远只有 1 次。
     *
     * <p>本用例里每个用户名只失败 1 次，账号维度永远到不了阈值 ——
     * 唯一能拦下它的是 IP 维度。旧实现下最后一次登录会是 200。</p>
     */
    @Test
    void 同一IP遍历用户名会被IP维度拦下() throws Exception {
        List<String> sprayed = List.of("spray_a", "spray_b", "spray_c", "spray_d", "spray_e");
        for (String name : sprayed) {
            assertThat(login(PREFIX + name, "Common@123", IP_C).getResponse().getStatus())
                    .as("轮换用户名，每个账号自己都只有 1 次失败")
                    .isEqualTo(400);
        }

        MvcResult result = login(PREFIX + "victim", RAW_PASSWORD, IP_C);

        assertThat(result.getResponse().getStatus())
                .as("换到一个真实存在的账号也必须被同一个 IP 桶拦下")
                .isEqualTo(429);
    }

    // ── 退避曲线 ────────────────────────────────────────────────────────────

    /** 封锁是<b>临时</b>的：到点自动解除。没有这条，"永久封锁"也能让上面几条全绿。 */
    @Test
    void 封锁到期后自动解除() throws Exception {
        for (int i = 0; i < 3; i++) {
            login(PREFIX + "victim", "wrong-" + i, IP_A);
        }
        assertThat(login(PREFIX + "victim", RAW_PASSWORD, IP_A).getResponse().getStatus()).isEqualTo(429);

        Thread.sleep(1300); // base-block-seconds=1

        assertThat(login(PREFIX + "victim", RAW_PASSWORD, IP_A).getResponse().getStatus())
                .as("首次达阈值只封 1 秒，等过就该放行")
                .isEqualTo(200);
    }

    /**
     * 递增退避：封锁时长随失败次数增长，而不是永远一个固定值。
     *
     * <p>直接量 Redis 里封锁键的 TTL —— 这是"退避真的变长了"唯一的直接证据；
     * 只断言"被拒"的话，固定 1 秒与翻倍到 2 秒完全不可区分。</p>
     *
     * <p><b>用毫秒量</b>：秒级读数会把 997 毫秒浮成 0（实测），
     * 那样两个断言都会变成"≈0 就是 ≈0"，这正是实现里踩过的那个坑。
     * 断言给的是区间而非等值 —— 读取发生在写入后若干毫秒，卡死等值会引入假红。</p>
     */
    @Test
    void 封锁时长随失败次数递增() throws Exception {
        for (int i = 0; i < 3; i++) {
            login(PREFIX + "victim", "wrong-" + i, IP_A);
        }
        assertThat(maxBlockTtlMillis())
                .as("第 3 次失败 → base × 2^0 = 1 秒")
                .isBetween(900L, 1000L);

        Thread.sleep(1300);
        login(PREFIX + "victim", "wrong-again", IP_A);

        assertThat(maxBlockTtlMillis())
                .as("第 4 次失败 → base × 2^1 = 2 秒")
                .isBetween(1900L, 2000L);
    }

    // ── 注册限流 ────────────────────────────────────────────────────────────

    /**
     * {@code /api/v1/auth/register} 是 {@code permitAll} 且此前<b>完全无限流</b>，
     * 可以无限批量注册。
     *
     * <p>末句换 IP 仍能注册是正向对照：证明被拦的原因是"这个来源用完了额度"，
     * 而不是"注册功能坏了"。</p>
     */
    @Test
    void 注册按来源IP限流() throws Exception {
        assertThat(register("reg_one", IP_A).getResponse().getStatus()).isEqualTo(200);
        assertThat(register("reg_two", IP_A).getResponse().getStatus()).isEqualTo(200);

        MvcResult blocked = register("reg_three", IP_A);
        assertThat(blocked.getResponse().getStatus()).isEqualTo(429);
        assertThat(objectMapper.readTree(body(blocked)).path("message").asText())
                .contains("注册过于频繁");

        assertThat(register("reg_four", IP_B).getResponse().getStatus())
                .as("换一个来源 IP 必须仍能注册")
                .isEqualTo(200);
    }

    // ── 夹具 ────────────────────────────────────────────────────────────────

    private MvcResult login(String username, String password, String clientIp) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("username", username);
        payload.put("password", password);
        return mockMvc.perform(post("/api/v1/auth/login")
                        .header("X-Real-IP", clientIp)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andReturn();
    }

    private MvcResult register(String nameSuffix, String clientIp) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("username", PREFIX + nameSuffix);
        payload.put("password", RAW_PASSWORD);
        return mockMvc.perform(post("/api/v1/auth/register")
                        .header("X-Real-IP", clientIp)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andReturn();
    }

    private String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    /**
     * 当前存在的封锁键中最大的剩余 TTL（毫秒）—— 账号桶与 IP 桶同时存在时，取更长的那个。
     *
     * <p>单位必须与实现一致取毫秒：{@code getExpire(key, SECONDS)} 是向下取整的，
     * 1 秒的封锁会被读成 0，断言"递增"就变成了"0 与 0 相等"。</p>
     */
    private long maxBlockTtlMillis() {
        Set<String> keys = stringRedisTemplate.keys(RedisKeyConstant.RATE_LIMIT_LOGIN + "b:*");
        if (keys == null || keys.isEmpty()) {
            return 0L;
        }
        return keys.stream().mapToLong(key -> {
            Long ttl = stringRedisTemplate.getExpire(key, TimeUnit.MILLISECONDS);
            return ttl == null ? 0L : ttl;
        }).max().orElse(0L);
    }

    /**
     * 清掉本类用到的全部限流键。
     *
     * <p><b>必须写在脚本里而不是靠人记得</b>：Redis 跨测试轮次不重置，
     * 残留的失败计数会让下一次运行从更高的起点开始，表现成"时绿时红"。
     * 本类每个用例都改了阈值，残留对别的用例同样是污染源。</p>
     */
    private void cleanRedis() {
        Set<String> keys = new HashSet<>();
        for (String pattern : List.of(RedisKeyConstant.RATE_LIMIT_LOGIN + "*",
                RedisKeyConstant.RATE_LIMIT_REGISTER + "*")) {
            Set<String> found = stringRedisTemplate.keys(pattern);
            if (found != null) {
                keys.addAll(found);
            }
        }
        if (!keys.isEmpty()) {
            stringRedisTemplate.delete(keys);
        }
    }

    private void cleanUsers() {
        jdbc.update("DELETE FROM dsm_user WHERE username LIKE ?", PREFIX + "%");
    }
}
