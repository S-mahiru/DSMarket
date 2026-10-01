package com.dsmarket.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 健康检查端点（审计 12-readiness-audit §2.7）。
 *
 * <p><b>为什么必须走真实 DispatcherServlet</b>：本条的机制全部是<b>框架行为</b>——
 * 「actuator 端点注册进了哪个 handler mapping」「Spring Security 按声明顺序挑中哪条规则」
 * 「{@code show-details} 由谁决定响应体长什么样」。直接 new 一个 controller 调方法的单测
 * 对这些一概看不见，删掉 {@code SecurityConfig} 里的放行、或把 {@code show-details} 改成
 * {@code always}，它照样全绿（本项目已有的教训：探针够不着被测层时，绿是假的）。</p>
 *
 * <p><b>三条承重断言各自防一种假绿</b>：</p>
 * <ol>
 *   <li>匿名 200 —— 监控/编排层<b>不可能持有 JWT</b>，要鉴权的健康检查等于没有。
 *       反向突变：摘掉 {@code SecurityConfig} 里那条 permitAll ⇒ 本条转红（401）。</li>
 *   <li>响应体不含组件细节 —— 该端点经 nginx {@code /healthz} 对公网开放，
 *       细节里的库类型/连接信息属于白送。反向突变：{@code show-details: never → always} ⇒ 本条转红。</li>
 *   <li>只放行了 health 这一个路径 —— 写成 {@code /actuator/**} 也满足第 1 条，
 *       所以第 1 条<b>证明不了</b>面被收窄了。这条断言的是"其余 actuator 端点匿名拿不到"。</li>
 * </ol>
 *
 * <p><b>正向对照（{@link #正向对照_普通业务接口匿名仍被拒}）不可省</b>：没有它，
 * 「整个应用忘了装鉴权、所有接口都匿名可达」会冒充成"健康检查配好了"。</p>
 *
 * <p><b>若第 1 条报 status 不是 UP</b>，先查 PostgreSQL / Redis 是否真的可达 ——
 * 那正是这个端点存在的意义，不是本测试不稳定。测试 profile 的库与 Redis 由
 * {@code deploy/init_test_db.sh} 与 compose 提供。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class HealthEndpointIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * 核心：监控拿不到 JWT，所以这个端点必须匿名可达，且后端活着时报告 UP。
     *
     * <p>断 {@code status} 字段而不只断状态码：{@code show-details} 配错、或端点被
     * 别的 JSON 处理器接管，状态码都还是 200。</p>
     */
    @Test
    void 健康检查匿名可达且报告UP() throws Exception {
        MvcResult result = mockMvc.perform(get("/actuator/health")).andReturn();

        assertThat(result.getResponse().getStatus())
                .as("匿名请求必须拿到 200 而不是 401 —— 编排层没有 JWT")
                .isEqualTo(200);
        assertThat(json(result).path("status").asText())
                .as("库与 Redis 都通时必须报 UP")
                .isEqualTo("UP");
    }

    /**
     * 正向对照：普通业务接口匿名访问仍是 401。
     *
     * <p>没有这一条，「鉴权整个没生效」与「健康检查被正确放行」在测试里是同一个样子。</p>
     */
    @Test
    void 正向对照_普通业务接口匿名仍被拒() throws Exception {
        assertThat(mockMvc.perform(get("/api/v1/cart")).andReturn().getResponse().getStatus())
                .as("健康检查放行不等于全局放行")
                .isEqualTo(401);
    }

    /**
     * 细节不外泄：响应体只有 {@code status} 一项。
     *
     * <p>断言的是<b>字段集合</b>而不只是"不含某几个词"：词表总会漏（库改了名、actuator 加了新组件），
     * 而"顶层只有 status"是个闭合判据 —— 任何新增的 {@code components} / {@code details}
     * 都会让它转红，不需要同步维护一份敏感词清单。</p>
     *
     * <p>同时保留词表断言作为第二道：它直接指向"公网能看到什么"这个后果，
     * 读失败信息的人不用先去理解 actuator 的响应结构。</p>
     */
    @Test
    void 健康检查不回显组件细节() throws Exception {
        MvcResult result = mockMvc.perform(get("/actuator/health")).andReturn();
        String body = body(result);
        JsonNode root = objectMapper.readTree(body);

        assertThat(root.fieldNames())
                .toIterable()
                .as("顶层只该有 status —— components/details 里是库类型与连接信息")
                .containsExactly("status");
        assertThat(body)
                .as("这些词一旦出现，说明细节开关被放开了")
                .doesNotContain("components", "redis", "PostgreSQL", "jdbc");
    }

    /**
     * 面被收窄到<b>一个</b>路径，而不是 {@code /actuator/**}。
     *
     * <p>{@code /actuator/env} 能读到运行时配置属性、{@code /actuator/heapdump} 能拖走整个堆，
     * 两者都由 actuator 提供，区别只在 {@code exposure.include} 里列没列。
     * 本类断言的是<b>安全层</b>这一道：即便将来有人往 include 里加了端点，
     * 匿名用户仍然拿不到（仍会撞在 {@code anyRequest().authenticated()} 上）。
     * 两道闸各管一段，这道不该只由 yml 那一份配置承担。</p>
     */
    @Test
    void 其余actuator端点不因本条而匿名可达() throws Exception {
        for (String path : new String[]{"/actuator/env", "/actuator/beans", "/actuator/heapdump"}) {
            assertThat(mockMvc.perform(get(path)).andReturn().getResponse().getStatus())
                    .as("%s 不该匿名可达 —— 放行的是 health 这一条精确路径，不是 /actuator/**", path)
                    .isEqualTo(401);
        }
    }

    // ── 夹具 ────────────────────────────────────────────────────────────────

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(body(result));
    }

    private String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}
