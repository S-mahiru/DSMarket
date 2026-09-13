package com.dsmarket.common.exception;

import com.dsmarket.common.domain.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 全局异常处理器的响应形态单测。
 *
 * <p><b>背景（2026-09-10 真机发现）</b>：{@code /chat} 是 SSE 端点，浏览器 EventSource 与
 * harness 都会带 {@code Accept: text/event-stream}。此时从控制器抛出的 {@code BusinessException}
 * 若走常规 {@code ResponseEntity<ApiResponse>} 返回，Spring 会因为挑不出能写 {@code application/json}
 * 的消息转换器而抛 {@code HttpMediaTypeNotAcceptableException}，**异常处理器自己失败** → 本该是
 * 400/409/429 的响应一律变成 500。</p>
 *
 * <p>这个 bug 是既有的（C1 的 429/409 全中招），但一直没被真机断言覆盖 —— 直到 C4 把内容长度
 * 校验从 Bean Validation 挪进 {@code open()}，才把一个**原本好用的 400** 也拖进了 500。</p>
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler(new ObjectMapper());

    private MockHttpServletRequest requestAccepting(String accept) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        if (accept != null) {
            req.addHeader(HttpHeaders.ACCEPT, accept);
        }
        return req;
    }

    @Test
    void sseClient_getsRealStatusAndJsonEnvelope_insteadOf500() throws Exception {
        MockHttpServletResponse resp = new MockHttpServletResponse();

        ResponseEntity<ApiResponse<Void>> ret = handler.handleBusinessException(
                new BusinessException(429, "发送太频繁，请稍后再试。"),
                requestAccepting(MediaType.TEXT_EVENT_STREAM_VALUE), resp);

        assertNull(ret, "已直接写响应体，不能再交给消息转换器（那就是抛 500 的那条路）");
        assertEquals(429, resp.getStatus(), "SSE 客户端的限流必须仍是 429，不能表现成 500");
        assertTrue(String.valueOf(resp.getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE),
                "信封仍是 JSON，只是绕开了内容协商；实际 contentType=" + resp.getContentType());
        assertTrue(resp.getContentAsString(StandardCharsets.UTF_8).contains("发送太频繁"),
                "错误消息要能读到，前端才解释得清");
    }

    @Test
    void jsonClient_keepsTheOriginalPath() throws Exception {
        MockHttpServletResponse resp = new MockHttpServletResponse();

        ResponseEntity<ApiResponse<Void>> ret = handler.handleBusinessException(
                new BusinessException(409, "并发"), requestAccepting(MediaType.APPLICATION_JSON_VALUE), resp);

        assertNotNull(ret, "接受 JSON 的客户端必须走原路径，响应形态一点不变");
        assertEquals(HttpStatus.CONFLICT, ret.getStatusCode());
    }

    @Test
    void missingAcceptHeader_isTreatedAsJsonClient() throws Exception {
        // 无 Accept 头 = 接受任何类型。不能因为"没有头"就切到降级路径，否则改变了既有行为
        ResponseEntity<ApiResponse<Void>> ret = handler.handleBusinessException(
                new BusinessException(400, "参数不对"), requestAccepting(null), new MockHttpServletResponse());

        assertNotNull(ret);
        assertEquals(HttpStatus.BAD_REQUEST, ret.getStatusCode());
    }

    @Test
    void wildcardAccept_isTreatedAsJsonClient() throws Exception {
        ResponseEntity<ApiResponse<Void>> ret = handler.handleBusinessException(
                new BusinessException(400, "参数不对"), requestAccepting("*/*"), new MockHttpServletResponse());

        assertNotNull(ret, "*/* 与 application/json 兼容，不该走降级路径");
    }

    @Test
    void alreadyStreamingResponse_stillReturnsNoBody_with500() throws Exception {
        // 既有护栏不能被本次修改破坏：流已开（Content-Type 已是 text/event-stream）时 body 写不进去，
        // 只能保 500 且不写。注意它与"Accept 只有 text/event-stream"是**两种不同情形**：
        // 前者响应类型已被占，后者只是客户端偏好 —— 前者写不了，后者能写。
        MockHttpServletResponse resp = new MockHttpServletResponse();
        resp.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);

        ResponseEntity<ApiResponse<Void>> ret = handler.handleBusinessException(
                new BusinessException(429, "限流"), requestAccepting(MediaType.TEXT_EVENT_STREAM_VALUE), resp);

        assertNotNull(ret, "流已开时不再自行写响应");
        assertEquals(500, ret.getStatusCode().value(), "保持既有行为：流中途的异常如实记 500");
        assertEquals("", resp.getContentAsString(StandardCharsets.UTF_8), "不试图往事件流里塞 JSON");
    }

    @Test
    void validationFailure_sseClient_getsOurEnvelope_notSpringsFallback() throws Exception {
        // 这条是"假 PASS"的照妖镜。真机上 /chat 收到 4001 字（超 @Size(max=4000)）时，
        // 探针只断言 status==400 就 PASS 了 —— 但日志里是一整段 "Failure in @ExceptionHandler"：
        // 我们的处理器根本没写出去，那个 400 是 Spring 的 DefaultHandlerExceptionResolver 兜底的
        // （它对 MethodArgumentNotValidException 的默认处理恰好也是 400）。
        // 故这里必须断言**信封内容**：只有我们的处理器真的生效，消息才读得到。
        MockHttpServletResponse resp = new MockHttpServletResponse();
        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(
                null, new BeanPropertyBindingResult(new Object(), "chatRequest"));

        ResponseEntity<ApiResponse<Void>> ret = handler.handleValidationException(
                ex, requestAccepting(MediaType.TEXT_EVENT_STREAM_VALUE), resp);

        assertNull(ret, "SSE 客户端必须走直接写信封这条路");
        assertEquals(400, resp.getStatus());
        assertTrue(resp.getContentAsString(StandardCharsets.UTF_8).contains("参数校验失败"),
                "信封里必须是我们的消息；空 body 等于被 Spring 兜底接管了，"
                        + "而兜底那条路没有消息、也没有 code。实际 body="
                        + resp.getContentAsString(StandardCharsets.UTF_8));
    }

    @Test
    void illegalArgument_sseClient_alsoGetsEnvelope() throws Exception {
        MockHttpServletResponse resp = new MockHttpServletResponse();

        ResponseEntity<ApiResponse<Void>> ret = handler.handleIllegalArgument(
                new IllegalArgumentException("参数不合法"), requestAccepting(MediaType.TEXT_EVENT_STREAM_VALUE), resp);

        assertNull(ret);
        assertEquals(400, resp.getStatus());
        assertTrue(resp.getContentAsString(StandardCharsets.UTF_8).contains("参数不合法"));
    }

    @Test
    void genericException_sseClient_alsoWritesEnvelopeWithoutNoise() throws Exception {
        // 500 这条的状态码本来就一样，但绕开协商能免掉日志里那段
        // "Failure in @ExceptionHandler" 噪音 —— 它会掩盖真正的错误
        MockHttpServletResponse resp = new MockHttpServletResponse();

        ResponseEntity<ApiResponse<Void>> ret = handler.handleException(
                new IllegalStateException("boom"),
                requestAccepting(MediaType.TEXT_EVENT_STREAM_VALUE), resp);

        assertNull(ret);
        assertEquals(500, resp.getStatus());
        assertTrue(resp.getContentAsString(StandardCharsets.UTF_8).contains("服务器内部错误"));
    }

    // ---- REQ-20260913-既有缺陷修复 B3：未匹配路径 → 404、方法不支持 → 405 ----

    @Test
    void missingPath_returns404WithOurEnvelope_not500() throws Exception {
        // 改动前这两类框架异常被兜底的 handleException(Exception.class) 吞成 500，
        // 因为 ExceptionHandlerExceptionResolver 的优先级高于 DefaultHandlerExceptionResolver。
        ResponseEntity<ApiResponse<Void>> ret = handler.handleNoResourceFound(
                new NoResourceFoundException(HttpMethod.GET, "api/v1/user/me"),
                requestAccepting(MediaType.APPLICATION_JSON_VALUE), new MockHttpServletResponse());

        assertNotNull(ret, "接受 JSON 的客户端走常规路径");
        assertEquals(HttpStatus.NOT_FOUND, ret.getStatusCode(), "未匹配路径必须是 404，不是 500");
        assertNotNull(ret.getBody());
        assertEquals(404, ret.getBody().getCode());
        assertEquals(ErrorCode.NOT_FOUND.getMessage(), ret.getBody().getMessage());
    }

    @Test
    void missingPath_doesNotEchoFrameworkMessage() throws Exception {
        // NoResourceFoundException 的消息形如 "No static resource api/v1/user/me." ——
        // 回显它等于告诉调用方哪些路径存在、用的什么框架。
        ResponseEntity<ApiResponse<Void>> ret = handler.handleNoResourceFound(
                new NoResourceFoundException(HttpMethod.GET, "api/v1/user/me"),
                requestAccepting(MediaType.APPLICATION_JSON_VALUE), new MockHttpServletResponse());

        assertNotNull(ret.getBody());
        String message = String.valueOf(ret.getBody().getMessage());
        assertFalse(message.contains("No static resource"),
                "不得回显框架原始消息，实际 message=" + message);
        assertFalse(message.contains("api/v1/user/me"),
                "更不得回显请求路径，实际 message=" + message);
    }

    @Test
    void methodNotAllowed_returns405_not404andNot500() throws Exception {
        // 405 与 404 必须分得开：方法不对 = "资源存在，只是不接受这个动词"
        ResponseEntity<ApiResponse<Void>> ret = handler.handleMethodNotSupported(
                new HttpRequestMethodNotSupportedException("DELETE"),
                requestAccepting(MediaType.APPLICATION_JSON_VALUE), new MockHttpServletResponse());

        assertNotNull(ret);
        assertEquals(HttpStatus.METHOD_NOT_ALLOWED, ret.getStatusCode());
        assertNotNull(ret.getBody());
        assertEquals(405, ret.getBody().getCode(), "ErrorCode 里必须有 405，否则信封会说谎");
        assertEquals(ErrorCode.METHOD_NOT_ALLOWED.getMessage(), ret.getBody().getMessage());
    }

    @Test
    void missingPath_sseClient_stillGetsEnvelope_notBlankResponse() throws Exception {
        // 与其他四个处理器同一条护栏：SSE 客户端 Accept 不接受 JSON 时，
        // 绕开内容协商直接写信封，而不是让 Spring 兜底成"状态码对但没信封"。
        MockHttpServletResponse resp = new MockHttpServletResponse();

        ResponseEntity<ApiResponse<Void>> ret = handler.handleNoResourceFound(
                new NoResourceFoundException(HttpMethod.GET, "api/v1/user/me"),
                requestAccepting(MediaType.TEXT_EVENT_STREAM_VALUE), resp);

        assertNull(ret, "已直接写响应体，不能再交给消息转换器");
        assertEquals(404, resp.getStatus());
        assertTrue(resp.getContentAsString(StandardCharsets.UTF_8).contains(ErrorCode.NOT_FOUND.getMessage()),
                "信封要读得到，实际 body=" + resp.getContentAsString(StandardCharsets.UTF_8));
    }
}
