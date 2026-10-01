package com.dsmarket.common.exception;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 「客户端发来的东西不成形」这一族，一律不得变成 5xx（审计 §2.2 / §2.8）。
 *
 * <p>与 {@code GlobalExceptionHandlerTest} 的分工：那个类直接调处理器方法，
 * 验的是"处理器自己的逻辑"；本类走真实 {@code DispatcherServlet}，
 * 验的是<b>"这个异常到底会不会走到我们的处理器上"</b> —— 本项目踩过
 * 「直接调方法的单测验证不了框架会不会调用它」这一坑（删掉 {@code @ExceptionHandler}
 * 注解，那种单测照样全绿）。</p>
 *
 * <p>落点全部选 {@code permitAll} 的端点，故无需登录，也不依赖任何测试夹具数据。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MalformedRequestContractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    /** 正向对照：请求体正常时该端点按业务逻辑回 400（"用户名或密码错误"），不是 500。 */
    @Test
    void 正向对照_正常请求体走的是业务错误() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"nobody\",\"password\":\"whatever\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("用户名或密码错误"));
    }

    /**
     * 请求体是畸形 JSON（少一个引号）→ 客户端错误。
     *
     * <p>此前落到 {@code handleException} 兜底成 500 + "服务器内部错误"，
     * 而这完全是客户端的问题，服务端日志里却会记一条 ERROR。</p>
     */
    @Test
    void 畸形JSON请求体是客户端错误() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\": \"nobody\", \"password\": "))
                .andExpect(status().is4xxClientError());
    }

    /** 压根没有请求体（{@code @RequestBody} 默认必填）→ 客户端错误。 */
    @Test
    void 缺少请求体是客户端错误() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().is4xxClientError());
    }

    /** 参数类型不对（{@code int limit} 收到 "abc"）→ 客户端错误。 */
    @Test
    void 参数类型不匹配是客户端错误() throws Exception {
        mockMvc.perform(get("/api/v1/products/featured").param("limit", "abc"))
                .andExpect(status().is4xxClientError());
    }
}
