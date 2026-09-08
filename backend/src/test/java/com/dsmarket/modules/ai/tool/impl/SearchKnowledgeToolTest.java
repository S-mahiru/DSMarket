package com.dsmarket.modules.ai.tool.impl;

import com.dsmarket.modules.ai.search.KnowledgeSearchResult;
import com.dsmarket.modules.ai.search.KnowledgeSegment;
import com.dsmarket.modules.ai.service.KnowledgeSearchService;
import com.dsmarket.modules.ai.tool.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SearchKnowledgeTool（切片 3）：COVERED→ok=true 渲染片段 / NO_ARG→ok=false / NOT_COVERED→ok=false
 * （SSE 编排走 typed run() 短路；通用契约经 execute 折叠，不外泄 LOW_CONF 细节）。
 */
class SearchKnowledgeToolTest {

    private static final long USER = 100L;

    private KnowledgeSegment seg(long id, String cat, String name, String q, String a) {
        KnowledgeSegment s = new KnowledgeSegment();
        s.setId(id);
        s.setCategory(cat);
        s.setCategoryName(name);
        s.setQuestion(q);
        s.setAnswer(a);
        return s;
    }

    /** 覆盖检索 stub：covered=true，segments 逐个给出。 */
    private KnowledgeSearchService covered(List<KnowledgeSegment> segs) {
        return q -> {
            KnowledgeSearchResult r = new KnowledgeSearchResult();
            r.setQuery(q);
            r.setCovered(true);
            r.getSegments().addAll(segs);
            return r;
        };
    }

    private KnowledgeSearchService notCovered() {
        return q -> {
            KnowledgeSearchResult r = new KnowledgeSearchResult();
            r.setQuery(q);
            r.setCovered(false);
            return r;
        };
    }

    private ObjectNode args(String query) {
        ObjectMapper om = new ObjectMapper();
        ObjectNode n = om.createObjectNode();
        n.put("query", query);
        return n;
    }

    @Test
    void nameIsSearchKnowledge() {
        SearchKnowledgeTool t = new SearchKnowledgeTool(q -> null);
        assertEquals("search_knowledge", t.name());
        assertTrue(t.parameters().path("properties").has("query"), "parameters 须含 query 属性");
    }

    @Test
    void covered_okContentRendersSegments() {
        List<KnowledgeSegment> segs = List.of(
                seg(1, "after_sale", "售后服务", "退款多久到账", "1-3 个工作日原路退回"));
        SearchKnowledgeTool t = new SearchKnowledgeTool(covered(segs));
        String expected = "【售后服务】退款多久到账\n1-3 个工作日原路退回";

        ToolResult r = t.execute(USER, args("退款多久到账"));

        assertTrue(r.isOk());
        assertEquals(expected, r.getContent(), "片段渲染文本回填（非折叠话术）");
    }

    @Test
    void covered_multipleSegmentsJoinedByBlankLine() {
        List<KnowledgeSegment> segs = List.of(
                seg(1, "after_sale", "售后服务", "Q1", "A1"),
                seg(2, "shipping", "物流服务", "Q2", "A2"));
        SearchKnowledgeTool t = new SearchKnowledgeTool(covered(segs));
        String expected = "【售后服务】Q1\nA1\n\n【物流服务】Q2\nA2";

        assertEquals(expected, t.run(args("多问")).content());
    }

    @Test
    void notCovered_runTypeNotCovered_executeOkFalse() {
        SearchKnowledgeTool t = new SearchKnowledgeTool(notCovered());

        SearchKnowledgeTool.KnowledgeToolResult typed = t.run(args("预售能否退款"));
        assertEquals(SearchKnowledgeTool.KnowledgeOutcomeType.NOT_COVERED, typed.type());
        assertNull(typed.content());

        ToolResult r = t.execute(USER, args("预售能否退款"));
        assertFalse(r.isOk());
        assertEquals("LOW_CONF", r.getError(), "error 供审计：LOW_CONF（对外折 FOLD_TEXT）");
    }

    @Test
    void noArg_blankOrAbsentQuery_neverSearches() {
        // search 被调用即失败：NO_ARG 必须先返回，不触检索
        SearchKnowledgeTool t = new SearchKnowledgeTool(q -> {
            throw new AssertionError("NO_ARG 不应触发检索");
        });

        SearchKnowledgeTool.KnowledgeToolResult typed = t.run(null);
        assertEquals(SearchKnowledgeTool.KnowledgeOutcomeType.NO_ARG, typed.type());

        assertEquals(SearchKnowledgeTool.KnowledgeOutcomeType.NO_ARG, t.run(args("   ")).type(),
                "空白 query 亦判 NO_ARG");
        assertEquals(SearchKnowledgeTool.KnowledgeOutcomeType.NO_ARG, t.run(args("")).type());

        ToolResult r = t.execute(USER, args("  "));
        assertFalse(r.isOk());
        assertEquals("NO_ARG", r.getError());
    }

    @Test
    void nullUserId_executeRejectedBeforeSearch() {
        SearchKnowledgeTool t = new SearchKnowledgeTool(q -> {
            throw new AssertionError("缺身份时不应触检索");
        });
        ToolResult r = t.execute(null, args("退款"));
        assertFalse(r.isOk());
    }
}
