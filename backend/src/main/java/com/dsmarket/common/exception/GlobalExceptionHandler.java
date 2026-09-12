package com.dsmarket.common.exception;

import com.dsmarket.common.domain.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    /** 仅在"客户端不接受 JSON"时才用到的降级写盘路径（见 {@link #acceptsJson}） */
    private final ObjectMapper objectMapper;

    /**
     * 业务异常按 code 映射 HTTP 状态（此前固定 400，无法表达 401/403/409/429 等语义）。
     * 例：AI 单飞行并发需真实 HTTP 409 + code=409（REQ C1 E12，开流前判定用 HTTP 状态码）。
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException e,
                                                                    HttpServletRequest request,
                                                                    HttpServletResponse response)
            throws IOException {
        if (isStreaming(response)) {
            return noBody(response, e);
        }
        return respond(e, resolveHttpStatus(e.getCode()), e.getCode(), e.getMessage(), request, response);
    }

    private HttpStatus resolveHttpStatus(int code) {
        return switch (code) {
            case 401 -> HttpStatus.UNAUTHORIZED;
            case 403 -> HttpStatus.FORBIDDEN;
            case 404 -> HttpStatus.NOT_FOUND;
            case 409 -> HttpStatus.CONFLICT;
            case 429 -> HttpStatus.TOO_MANY_REQUESTS;
            case 502 -> HttpStatus.BAD_GATEWAY;       // 模型上游 HTTP/解析失败
            case 504 -> HttpStatus.GATEWAY_TIMEOUT;   // 模型上游超时（预留）
            case 500 -> HttpStatus.INTERNAL_SERVER_ERROR;
            default -> HttpStatus.BAD_REQUEST;
        };
    }

    /**
     * Bean Validation 失败（{@code @Valid} 拒收）。
     *
     * <p><b>2026-09-10 真机发现</b>：本方法此前返回裸 {@code ApiResponse}，在 SSE 端点上与
     * {@code handleBusinessException} 犯的是同一个错 —— 写不出去、自己抛
     * {@code HttpMediaTypeNotAcceptableException}，再由 Spring 的
     * {@code DefaultHandlerExceptionResolver} 兜底。当时客户端**碰巧**仍拿到 400（Spring 对
     * {@code MethodArgumentNotValidException} 的默认处理就是 400），所以看着像好的：
     * 探针 D3（4001 字 + 锚点）断言 status==400 是 PASS 的，但服务端日志里躺着一整段
     * "Failure in @ExceptionHandler" 栈，**我们自己的错误消息一个字都没送到客户端**。</p>
     *
     * <p>教训：断言只看状态码时，"我们的处理器生效了" 与 "Spring 兜底碰巧同码" 不可区分。
     * 故 {@code GlobalExceptionHandlerTest} 里对校验失败也断言信封内容，不只断言状态码。</p>
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidationException(MethodArgumentNotValidException e,
                                                                       HttpServletRequest request,
                                                                       HttpServletResponse response)
            throws IOException {
        if (isStreaming(response)) {
            return noBody(response, e);
        }
        FieldError fieldError = e.getBindingResult().getFieldError();
        String message = fieldError != null ? fieldError.getDefaultMessage() : "参数校验失败";
        return respond(e, HttpStatus.BAD_REQUEST, 400, message, request, response);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArgument(IllegalArgumentException e,
                                                                   HttpServletRequest request,
                                                                   HttpServletResponse response)
            throws IOException {
        if (isStreaming(response)) {
            return noBody(response, e);
        }
        return respond(e, HttpStatus.BAD_REQUEST, 400, e.getMessage(), request, response);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleException(Exception e, HttpServletRequest request,
                                                             HttpServletResponse response) throws IOException {
        if (isStreaming(response)) {
            return noBody(response, e);
        }
        log.error("服务器内部错误", e);
        return respond(e, HttpStatus.INTERNAL_SERVER_ERROR, 500, "服务器内部错误", request, response);
    }

    /**
     * 全类唯一的"异常 → HTTP 响应"出口。
     *
     * <p>抽出来是因为<b>漏过一次</b>：最初只给 {@code handleBusinessException} 加了绕开内容协商的
     * 分支，{@code handleValidationException} 没加，于是同一个 bug 换个入口又出现一遍
     * （见 {@link #handleValidationException} 的注释）。四个处理器共用一条出口，就不会再漏。</p>
     *
     * <p>调用方负责先做 {@code isStreaming} 早返回 —— 那一支的语义不同（不写 body），
     * 不适合混进这里。</p>
     */
    private ResponseEntity<ApiResponse<Void>> respond(Exception cause, HttpStatus status, int code, String message,
                                                      HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        ApiResponse<Void> body = ApiResponse.error(code, message);
        if (!acceptsJson(request)) {
            // 客户端只收事件流（EventSource 一律发 Accept: text/event-stream），Spring 挑不出能写
            // JSON 的消息转换器 → 处理器自己抛 HttpMediaTypeNotAcceptableException，响应要么变成
            // 500，要么被 Spring 兜底成"状态码对但没信封"的空响应。注意此时响应**尚未提交**
            // （否则 isStreaming 已拦下），状态码仍可自由设置，故如实写下状态码与信封，只是绕开
            // 内容协商。
            log.warn("异常响应（客户端 Accept 不接受 JSON，直接写信封绕过内容协商）: status={} msg={}",
                    status.value(), message);
            writeJson(response, status, body);
            return null;
        }
        log.warn("异常响应: status={} msg={}", status.value(), message);
        return ResponseEntity.status(status).body(body);
    }

    /**
     * 客户端是否接受 JSON 信封。
     *
     * <p>用 {@code application/json.isCompatibleWith(t)} 一次判完：它同时覆盖
     * {@code application/json}、{@code application/*}、全通配类型与缺省（无 Accept 头 =
     * 接受任何类型），而对 {@code text/event-stream} 返回 false —— 正是要拦的那一种。</p>
     *
     * <p>注：此处刻意不把全通配类型写成字面量，因为 javadoc 里出现 <code>&#42;/&#42;</code>
     * 中的星号斜杠会把注释提前闭合，后面的中文会被当成 Java 代码（已踩过一次编译错误）。</p>
     *
     * <p>解析不了的头一律按"接受 JSON"处理，走原来的路径：这个判断只该用来**绕开**已知的
     * 协商失败，不该因为一个畸形头就改变正常客户端的响应形态。</p>
     */
    private boolean acceptsJson(HttpServletRequest request) {
        String accept = request.getHeader(HttpHeaders.ACCEPT);
        if (accept == null || accept.isBlank()) {
            return true;
        }
        try {
            return MediaType.parseMediaTypes(accept).stream()
                    .anyMatch(t -> MediaType.APPLICATION_JSON.isCompatibleWith(t));
        } catch (InvalidMediaTypeException ex) {
            return true;
        }
    }

    /** 直接写 JSON 信封（不复用消息转换器，故不受 Accept 头约束） */
    private void writeJson(HttpServletResponse response, HttpStatus status, ApiResponse<Void> body)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getWriter(), body);
    }

    /**
     * 该响应的信封是否已经注定写不出去（流式响应，或已提交）。
     *
     * <p>流式端点在<b>进入控制器之前</b>就把 Content-Type 预置成了 {@code text/event-stream}
     * （由 {@code produces} 决定），此时任何 JSON 信封都没有可用的消息转换器，硬写会抛
     * {@code HttpMessageNotWritableException} 并在日志里吐一段栈。</p>
     *
     * <p>而流式请求的异常绝大多数是<b>客户端断开后的收尾异常</b>（C4 的买家人工通道与工作台通道
     * 是常驻连接，每次关抽屉/关工作台都会断开），把它记成 ERROR + 栈等于用噪音淹没真错误 ——
     * 既是假警报，又让人不敢信任日志。故此处只记一行 debug，不回 body。</p>
     */
    private boolean isStreaming(HttpServletResponse response) {
        String contentType = response.getContentType();
        return response.isCommitted()
                || (contentType != null && contentType.startsWith(MediaType.TEXT_EVENT_STREAM_VALUE));
    }

    /** 流式/已提交响应：如实保留 500 状态码，但不试图写 JSON body（写了也写不进去） */
    private ResponseEntity<ApiResponse<Void>> noBody(HttpServletResponse response, Exception e) {
        log.debug("[sse] 流式响应异常收尾（客户端多半已断开，不回 JSON 信封）: {}", e.getMessage());
        // 注意：这里不能写成 status(isCommitted() ? getStatus() : HttpStatus.INTERNAL_SERVER_ERROR)。
        // 条件表达式的两个分支是 int 与 HttpStatus，没有公共类型，javac 会两个重载都匹配不上而编译失败。
        HttpStatusCode status = response.isCommitted()
                ? HttpStatusCode.valueOf(response.getStatus())
                : HttpStatus.INTERNAL_SERVER_ERROR;
        return ResponseEntity.status(status).build();
    }
}
