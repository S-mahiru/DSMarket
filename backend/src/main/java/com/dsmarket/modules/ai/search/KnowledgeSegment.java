package com.dsmarket.modules.ai.search;

import com.dsmarket.modules.ai.entity.AiKnowledge;
import com.dsmarket.modules.ai.enums.AiKnowledgeCategory;
import lombok.Data;

/**
 * 喂给模型/输出的知识片段（C2-F6）：{category, question, answer} + 中文类目名。
 *
 * <p>最小事实单位：不含 score/keywords/faq/status —— F6 输出行"片段(question/answer/category)
 * 供模型组织语言"，其余字段不进 LLM 上下文。由 {@link #of(AiKnowledge)} 从检索行构造。</p>
 */
@Data
public class KnowledgeSegment {

    private Long id;
    private String category;
    private String categoryName;
    private String question;
    private String answer;

    /** 从检索返回的实体行构造（categoryName 中文展示，未知类目回退原值）。 */
    public static KnowledgeSegment of(AiKnowledge row) {
        KnowledgeSegment s = new KnowledgeSegment();
        s.id = row.getId();
        s.category = row.getCategory();
        AiKnowledgeCategory cat = AiKnowledgeCategory.fromValueOrNull(row.getCategory());
        s.categoryName = cat == null ? row.getCategory() : cat.getDisplayName();
        s.question = row.getQuestion();
        s.answer = row.getAnswer();
        return s;
    }
}
