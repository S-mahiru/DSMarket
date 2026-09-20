package com.dsmarket.modules.ai.controller;

import com.dsmarket.common.domain.ApiResponse;
import com.dsmarket.common.util.SecurityUtils;
import com.dsmarket.modules.ai.dto.ChatTurnResult;
import com.dsmarket.modules.ai.dto.DevChatRequest;
import com.dsmarket.modules.ai.dto.DevSearchRequest;
import com.dsmarket.modules.ai.search.KnowledgeSearchResult;
import com.dsmarket.modules.ai.search.RouteSearchResult;
import com.dsmarket.modules.ai.service.AiSessionService;
import com.dsmarket.modules.ai.service.KnowledgeSearchService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 模块的<b>调试端点</b>，仅 {@code dev} profile 装配。URL 与拆分前完全一致
 * （{@code /api/v1/ai} + {@code /dev/*}），故 dev 下的既有用法零影响。
 *
 * <h3>为什么单独成一个类，而不是给三个方法各加 {@code @Profile("dev")}</h3>
 * <p><b>那是个静默无效的写法。</b>{@code @Profile} 是 {@code @Conditional} 的元注解，Spring 只在
 * <b>注册 bean 定义</b>时求值（类级 {@code @Component}、{@code @Bean} 方法）。标在普通
 * {@code @Component} 里的一个 {@code @PostMapping} <b>方法</b>上，没有任何代码路径去读它 ——
 * 注解在、映射照旧注册、端点照旧可达。结果是审计条目"看上去已修"、实际一点没变，
 * 属于典型的假修复。要让 profile 真正生效，条件必须落在<b>类</b>上。</p>
 *
 * <h3>为什么必须挡住</h3>
 * <p>（审计 12-readiness-audit §1.5）这三个端点此前落在 {@code SecurityConfig} 的
 * {@code anyRequest().authenticated()} 上 —— <b>任意普通 USER 都能调</b>，且
 * {@code /knowledge-search}、{@code /knowledge-routes} 直连 {@code knowledgeSearchService}，
 * <b>不经过 {@code ChatRateLimiter}</b>（限流只挂在 {@code AiChatStreamService.open()}）。
 * 那两条会真实调用 DashScope <b>付费</b> embedding，等于给出一个无需额度的成本放大面。</p>
 *
 * <p>本类只解决"生产环境不该有这个面"。<b>限流下沉到 service 层那一半仍未做</b> ——
 * dev 环境下限流绕过依旧存在（本地可接受），生产路径则由本类的 {@code @Profile} 整体移除。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/ai/dev")
@Profile("dev")
@RequiredArgsConstructor
public class AiDevController {

    private final AiSessionService sessionService;
    private final KnowledgeSearchService knowledgeSearchService;

    /**
     * M1b 调试：会话化多轮工具对话（每用户 Redis 单键记忆 + 单飞行 409）。
     * 最终契约将改为 SSE 通道。userId 由服务端安全上下文注入。
     */
    @PostMapping("/chat")
    public ApiResponse<ChatTurnResult> devChat(@Valid @RequestBody DevChatRequest request) {
        ChatTurnResult result = sessionService.chat(SecurityUtils.requireUserId(), request.getMessage());
        return ApiResponse.success(result);
    }

    /**
     * C2 切片 2 调试端点：双路检索（BM25+Dense → RRF → 置信闸 → F6 组织）。
     * body 缺省或 query 空 → covered=false（服务层语义 = 空 top-k）。非最终契约：
     * 正式入口是 C1 的 search_knowledge 工具（切片 3 接入）。
     */
    @PostMapping("/knowledge-search")
    public ApiResponse<KnowledgeSearchResult> devKnowledgeSearch(@RequestBody(required = false) DevSearchRequest request) {
        String query = request == null ? null : request.getQuery();
        return ApiResponse.success(knowledgeSearchService.search(query));
    }

    /**
     * C2 §7 效果评估数据源：两路原始 top-k（BM25/Dense 各自有序，Dense 含余弦距离），
     * 不融合不过闸不组织 —— 评估装置据此离线重算四档（①BM25 ②Dense ③RRF 等权 ④RRF 加权）
     * 与幅度闸阈值标定。body 复用 {@link DevSearchRequest}（query 字段）。生产路径不调用。
     */
    @PostMapping("/knowledge-routes")
    public ApiResponse<RouteSearchResult> devKnowledgeRoutes(@RequestBody(required = false) DevSearchRequest request) {
        String query = request == null ? null : request.getQuery();
        return ApiResponse.success(knowledgeSearchService.routes(query));
    }
}
