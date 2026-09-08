package com.dsmarket.modules.ai.tool.impl;

import com.dsmarket.modules.ai.search.KnowledgeSearchResult;
import com.dsmarket.modules.ai.search.KnowledgeSegment;
import com.dsmarket.modules.ai.service.KnowledgeSearchService;
import com.dsmarket.modules.ai.tool.ChatTool;
import com.dsmarket.modules.ai.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * search_knowledge：检索知识库（C2 双路引擎）供模型基于政策/流程片段作答（REQ C2 §6）。
 *
 * <p>通用契约走 {@link #execute(Long, JsonNode)}（ToolRunner 折叠语义：ok=false 一律折 FOLD_TEXT，
 * 不泄漏 LOW_CONF/NO_ARG 侧信道细节）。SSE 编排额外走 <b>typed {@link #run(JsonNode)}</b> 短路判定：
 * <ul>
 *   <li>{@code COVERED} → 高置信，content=≤3 片段渲染文本，正常喂回模型 → 模型组织正文；</li>
 *   <li>{@code NO_ARG} → 模型没给 query，content=引导话术（让模型向用户澄清要查的具体问题）；</li>
 *   <li>{@code NOT_COVERED} → 引擎低置信/冲突/双空 → SSE 编排<b>不喂回模型自由发挥</b>，
 *       改走 FAQ 兜底（fallback→suggest(LOW_CONF)→done，见 AiChatStreamService）。</li>
 * </ul></p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SearchKnowledgeTool implements ChatTool {

    /** NO_ARG 时回填给模型的话术（引导模型向用户澄清，非最终答复） */
    public static final String NO_ARG_TOOL_TEXT = "用户没有给出要查询的具体知识问题。请向用户澄清他想查哪类政策/流程（如退换货、退款时效、发货、支付、售后等）。";

    private final KnowledgeSearchService knowledgeSearchService;

    /** typed 检索结果三态：COVERED / NO_ARG / NOT_COVERED。 */
    public enum KnowledgeOutcomeType {
        /** 高置信有片段（content=渲染文本，非空） */
        COVERED,
        /** 模型未给 query（content=引导澄清话术） */
        NO_ARG,
        /** 低置信/冲突/双路空（content=null，走 FAQ 兜底） */
        NOT_COVERED
    }

    /** run() 的判别结果：type + 供 TOOL 回填的 content（NOT_COVERED 时 content=null）。 */
    public record KnowledgeToolResult(KnowledgeOutcomeType type, String content) {

        public static KnowledgeToolResult covered(String content) {
            return new KnowledgeToolResult(KnowledgeOutcomeType.COVERED, content);
        }

        public static KnowledgeToolResult noArg() {
            return new KnowledgeToolResult(KnowledgeOutcomeType.NO_ARG, NO_ARG_TOOL_TEXT);
        }

        public static KnowledgeToolResult notCovered() {
            return new KnowledgeToolResult(KnowledgeOutcomeType.NOT_COVERED, null);
        }
    }

    @Override
    public String name() {
        return "search_knowledge";
    }

    @Override
    public String description() {
        return "检索商城知识库，获取退换货/退款/发货/支付/发票/售后等政策与流程的标准回答。"
                + "用户问的是商城政策、规则、办理流程（怎么办、多久、能不能）时调用；"
                + "query 用用户原意精炼（去掉客套，≤200 字）。"
                + "基于返回的知识片段作答；若没有相关片段请如实告知用户“暂时没有查到该政策”，不要编造。";
    }

    @Override
    public JsonNode parameters() {
        ObjectNode props = JsonNodeFactory.instance.objectNode();
        props.putObject("query")
                .put("type", "string")
                .put("description", "用户想查的知识问题（精炼，≤200 字）");
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        schema.set("properties", props);
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public ToolResult execute(Long userId, JsonNode args) {
        if (userId == null) {
            return ToolResult.builder().ok(false).error("缺少用户身份，工具拒绝执行").build();
        }
        KnowledgeToolResult r = run(args);
        return switch (r.type()) {
            case COVERED -> ToolResult.builder().ok(true).content(r.content()).build();
            // NO_ARG/NOT_COVERED 都走 ok=false → ToolRunner 折通用 FOLD_TEXT（侧信道一致，不分细节）
            case NO_ARG -> ToolResult.builder().ok(false).error("NO_ARG").build();
            case NOT_COVERED -> ToolResult.builder().ok(false).error("LOW_CONF").build();
        };
    }

    /**
     * typed 检索（供 SSE 编排短路判定；execute 亦委托本方法后折叠）。
     *
     * @param args 模型参数（含可选的 query 字段）
     * @return COVERED(content=片段渲染文本) / NO_ARG(content=澄清引导) / NOT_COVERED(content=null)
     */
    public KnowledgeToolResult run(JsonNode args) {
        String query = args == null ? null : trimToNull(args.path("query").asText(null));
        if (query == null) {
            return KnowledgeToolResult.noArg();
        }
        KnowledgeSearchResult r = knowledgeSearchService.search(query);
        if (!r.isCovered() || r.getSegments() == null || r.getSegments().isEmpty()) {
            // 引擎低置信/冲突/双路空 → 空 top-k，由 C1 走 FAQ 兜底（REQ §3.4 / C2 §3.6 R4/R6/R9）
            return KnowledgeToolResult.notCovered();
        }
        return KnowledgeToolResult.covered(render(r.getSegments()));
    }

    /** 片段渲染文本（COVERED 喂回模型的正文）：每条「【类目】question\nanswer」，≤3 条用空行分隔。 */
    private String render(List<KnowledgeSegment> segments) {
        StringBuilder sb = new StringBuilder();
        for (KnowledgeSegment s : segments) {
            if (sb.length() > 0) {
                sb.append("\n\n");
            }
            sb.append('【').append(s.getCategoryName() == null ? s.getCategory() : s.getCategoryName()).append('】');
            sb.append(s.getQuestion()).append('\n').append(s.getAnswer());
        }
        return sb.toString();
    }

    private String trimToNull(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        return s.trim();
    }
}
