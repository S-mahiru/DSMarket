package com.dsmarket.common.exception;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * B3（404 / 405）的 <b>端到端</b> 验证 —— 走真实 DispatcherServlet，而不是直接调处理器方法。
 *
 * <p><b>为什么必须有这一个类</b>：{@code GlobalExceptionHandlerTest} 是直接调
 * {@code handler.handleNoResourceFound(...)}，它只能证明<b>方法体</b>写对了状态码与信封，
 * <b>证明不了 Spring 会把这两类框架异常路由到这个方法</b>。而"路由"恰恰是整个 B3 修复的全部内容 ——
 * 改动前它们被兜底的 {@code handleException(Exception.class)} 吞成 500
 * （{@code ExceptionHandlerExceptionResolver} 优先级高于 {@code DefaultHandlerExceptionResolver}）。
 *
 * <p>换句话说：把两个 {@code @ExceptionHandler} 注解整个删掉，{@code GlobalExceptionHandlerTest}
 * 仍然<b>全绿</b>（方法还在、直接调用照样返回 404），可线上立刻退回 500。
 * 这正是本项目吃过亏的那类"假 PASS"，故此处补一道只有注解真的生效才可能通过的探针。</p>
 *
 * <p>取路径的依据（{@code SecurityConfig.filterChain}）：{@code /api/v1/categories/**} 与
 * {@code /api/v1/products/**} 都是 permitAll，请求能穿过安全链抵达 MVC —— 否则会先被
 * {@code anyRequest().authenticated()} 拦成 401，压根到不了异常处理器，测的就不是 B3 了。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
// 把静态资源根指向一次性目录。**不能沿用默认的 ./uploads** —— 那是开发机上放真实上传文件的
// 地方，git 不携带它，测试依赖它就会在别处必红（见 createFixture）。
@TestPropertySource(properties = "web.upload.dir=" + GlobalExceptionHandlerWebTest.UPLOAD_DIR)
class GlobalExceptionHandlerWebTest {

    /**
     * 本类专用的静态资源夹具目录。
     *
     * <p>放 {@code target/} 下有两个好处：它已被 .gitignore（不会被误提交），
     * 且 {@code mvn clean} 即清空 —— 故本类不需要写清理逻辑，每次 {@code @BeforeEach} 重建即可。
     */
    static final String UPLOAD_DIR = "target/test-uploads";

    /** 夹具文件名。刻意不叫 iphone16.jpg —— 它只是几个字节，与任何真实商品图无关。 */
    private static final String FIXTURE = "fixture.jpg";

    @Autowired
    private MockMvc mockMvc;

    /**
     * 造出本类需要的那个静态资源文件。
     *
     * <p><b>为什么必须自己造</b>：本类原先请求 {@code /uploads/iphone16.jpg}，并在 Javadoc 里
     * 断言「该文件确实存在（V3 种子的商品图）」。那个断言在开发机上碰巧成立，但
     * {@code backend/uploads/} 是 <b>.gitignore</b> 的 —— 该文件<b>只存在于恰好往里放过图的机器上</b>。
     * CI 全新 clone 上没有它，于是这条用例必然 404 失败（2026-09-20 由 CI 首跑实测曝出，
     * 本地用一个空目录复现了逐字相同的失败）。</p>
     *
     * <p>这与 {@code ProductSearchIntegrationTest} 那条「测试依赖 V3 种子数据」是<b>同一类缺陷</b>：
     * 测试的绿依赖于 git 不携带的本地状态。修法也同理 —— <b>让测试自己掌握全部夹具</b>。</p>
     */
    @BeforeEach
    void createFixture() throws IOException {
        Path dir = Path.of(UPLOAD_DIR);
        Files.createDirectories(dir);
        Files.write(dir.resolve(FIXTURE), "fixture".getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 正向对照：这条必须 200。
     *
     * <p>没有它，"所有请求都 404" 会冒充成 "未匹配路径正确返回了 404" ——
     * 安全链配错、上下文没起来、路径写错，统统会被读成修复生效。</p>
     */
    @Test
    void knownPath_stillReturns200_provingTheStackIsActuallyWired() throws Exception {
        mockMvc.perform(get("/api/v1/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    /**
     * 未匹配路径 → 404（改动前是 500）。
     *
     * <p>路径取 3 段：{@code ProductController} 只映射到 {@code /api/v1/products/{id}}（2 段），
     * 故这条既过 permitAll 又确实无处理器可匹配。</p>
     */
    @Test
    void unmappedPath_throughRealDispatcherServlet_is404_not500() throws Exception {
        mockMvc.perform(get("/api/v1/products/1/definitely-not-a-real-subpath"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value(ErrorCode.NOT_FOUND.getMessage()))
                // 不得回显框架原始消息 / 请求路径：那等于告诉调用方哪些路径存在、用的什么框架
                .andExpect(content().string(not(containsString("No static resource"))))
                .andExpect(content().string(not(containsString("definitely-not-a-real-subpath"))));
    }

    /**
     * 路径匹配、方法不支持 → 405（改动前同样是 500）。
     *
     * <p>{@code CategoryController} 只有 {@code @GetMapping}，故 POST 到同一路径必然触发
     * {@code HttpRequestMethodNotSupportedException}；且 405 与 404 必须分得开 ——
     * "资源存在，只是不接受这个动词"和"资源不存在"是两回事。</p>
     */
    /**
     * E11 正向对照：<b>存在的</b>静态资源照常 200。
     *
     * <p>{@code NoResourceFoundException} 同时覆盖"API 路径写错"与"静态资源不存在"两种来源，
     * 故 §9 E11 把它标为"本切片最容易踩的坑"。这里必须证明新增的 404 处理器<b>只影响缺失的资源</b> ——
     * 若它连存在的文件也拦下，商品图会全站变叉。</p>
     *
     * <p><b>夹具由本类自己造</b>：{@link #createFixture()} 写在一次性目录 {@value #UPLOAD_DIR}
     * （{@code web.upload.dir} 已由本类的 {@code @TestPropertySource} 指过去），
     * 不再引用 {@code ./uploads} 下的真实图片 —— 原写法在 CI 上必红，原因见该方法。</p>
     */
    @Test
    void existingStaticResource_isStillServed_notHijackedByThe404Handler() throws Exception {
        mockMvc.perform(get("/uploads/" + FIXTURE))
                .andExpect(status().isOk());
    }

    /** E11 反向：<b>缺失的</b>静态资源 → 404（改动前同样是 500）。 */
    @Test
    void missingStaticResource_isNow404_not500() throws Exception {
        mockMvc.perform(get("/uploads/definitely-missing.jpg"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    /**
     * 安全链<b>先于</b> MVC 异常处理器 —— 故"未匹配路径返回 404"这条<b>只对 permitAll 前缀成立</b>。
     *
     * <p>§10 第 13 条把验收写法定为 {@code GET /api/v1/user/me → 404}。**带着未登录的 curl 跑不会得到 404**：
     * {@code /api/v1/user/**} 不在 permitAll 列表里（{@code SecurityConfig.java:40-47}），
     * 会先命中 {@code anyRequest().authenticated()} → 401，请求<b>根本到不了</b> MVC。
     * 只有带上有效 token 才会走到"无处理器 → 404"。</p>
     *
     * <p>固化这条是因为它容易在验收时被误判成"B3 没修好"。修 B3 不能、也不该改变鉴权顺序。</p>
     */
    @Test
    void authenticatedPath_withoutToken_is401_not404_securityRunsFirst() throws Exception {
        mockMvc.perform(get("/api/v1/user/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void unsupportedMethod_throughRealDispatcherServlet_is405_not404andNot500() throws Exception {
        mockMvc.perform(post("/api/v1/categories"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value(405))
                .andExpect(jsonPath("$.message").value(ErrorCode.METHOD_NOT_ALLOWED.getMessage()));
    }
}
