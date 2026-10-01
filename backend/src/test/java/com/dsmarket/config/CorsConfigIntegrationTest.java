package com.dsmarket.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CORS 配置的集成测试（审计 §2.6）。
 *
 * <p>落点选 {@code /api/v1/categories}：`permitAll`、GET、无副作用，且确实在
 * {@code addMapping("/api/**")} 的覆盖范围内 —— 换个不在映射里的路径，下面的断言
 * 会因为"压根没走 CORS"而恒真。</p>
 *
 * <p><b>正反两条必须成对</b>：只断言"未授权来源不回显"的话，一个把 CORS 关掉的实现
 * （或路径写错、映射没生效）也能通过；只断言"授权来源回显"的话，一个反射任意 Origin
 * 的实现同样能通过。两条一起才排得掉这两种。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CorsConfigIntegrationTest {

    /** 与 {@code application-dev.yml} 的 {@code web.cors.allowed-origins} 取值一致。 */
    private static final String ALLOWED_ORIGIN = "http://localhost:5173";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 已授权来源_回显该来源且允许携带凭据() throws Exception {
        mockMvc.perform(get("/api/v1/categories").header("Origin", ALLOWED_ORIGIN))
                .andExpect(status().isOk())
                // 必须是"原样回显这一个来源"，不是 `*` —— 带凭据时 `*` 本来就是非法组合
                .andExpect(header().string("Access-Control-Allow-Origin", ALLOWED_ORIGIN))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
    }

    @Test
    void 未授权来源_不回显任何放行头() throws Exception {
        mockMvc.perform(get("/api/v1/categories").header("Origin", "http://evil.example"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }
}
