package com.dsmarket.modules.ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dsmarket.common.domain.PageResult;
import com.dsmarket.common.exception.BusinessException;
import com.dsmarket.common.exception.ErrorCode;
import com.dsmarket.common.util.VectorText;
import com.dsmarket.modules.ai.dto.AiKnowledgeFormDTO;
import com.dsmarket.modules.ai.dto.AiKnowledgeQuery;
import com.dsmarket.modules.ai.dto.AiKnowledgeVO;
import com.dsmarket.modules.ai.entity.AiKnowledge;
import com.dsmarket.modules.ai.enums.AiKnowledgeCategory;
import com.dsmarket.modules.ai.enums.AiKnowledgeStatus;
import com.dsmarket.modules.ai.mapper.AiKnowledgeMapper;
import com.dsmarket.modules.ai.provider.EmbeddingClient;
import com.dsmarket.modules.ai.service.AiKnowledgeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 知识库后台服务实现（C2 切片 1）。
 *
 * <p>发布路径首次真实调用 {@link EmbeddingClient}（A1 的 DashScope 实现）：对 question 向量化 →
 * {@link VectorText} 格式化 → Mapper 原子写 embedding+status=published。embedding 不进实体。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiKnowledgeServiceImpl implements AiKnowledgeService {

    private static final String NOT_FOUND_MSG = "知识条目不存在";

    private final AiKnowledgeMapper knowledgeMapper;
    private final EmbeddingClient embeddingClient;

    @Override
    public PageResult<AiKnowledgeVO> adminPage(long page, long size, AiKnowledgeQuery query) {
        LambdaQueryWrapper<AiKnowledge> wrapper = new LambdaQueryWrapper<AiKnowledge>()
                .orderByDesc(AiKnowledge::getId);
        if (query != null) {
            if (isNotBlank(query.getCategory())) {
                wrapper.eq(AiKnowledge::getCategory, query.getCategory());
            }
            if (isNotBlank(query.getStatus())) {
                wrapper.eq(AiKnowledge::getStatus, query.getStatus());
            }
            if (isNotBlank(query.getKeyword())) {
                String like = "%" + query.getKeyword().trim() + "%";
                wrapper.and(w -> w.like(AiKnowledge::getQuestion, like)
                        .or().like(AiKnowledge::getAnswer, like));
            }
        }
        Page<AiKnowledge> result = knowledgeMapper.selectPage(new Page<>(page, size), wrapper);
        List<AiKnowledgeVO> vos = toVos(result.getRecords());
        return PageResult.of(vos, result.getTotal(), page, size);
    }

    @Override
    public AiKnowledgeVO detail(Long id) {
        AiKnowledge row = requireRow(id);
        boolean embedded = !knowledgeMapper.selectEmbeddedIds(List.of(id)).isEmpty();
        return toVo(row, embedded);
    }

    @Override
    @Transactional
    public Long create(AiKnowledgeFormDTO form) {
        AiKnowledge row = new AiKnowledge();
        applyForm(row, form);
        row.setStatus(AiKnowledgeStatus.DRAFT_VALUE);
        knowledgeMapper.insert(row);
        return row.getId();
    }

    @Override
    @Transactional
    public void update(Long id, AiKnowledgeFormDTO form) {
        AiKnowledge row = requireRow(id);
        applyForm(row, form);
        // 编辑已发布条目 → 回退 draft：已发布内容不得携陈旧向量存活（重发布即重向量化）
        if (AiKnowledgeStatus.PUBLISHED_VALUE.equals(row.getStatus())) {
            row.setStatus(AiKnowledgeStatus.DRAFT_VALUE);
        }
        knowledgeMapper.updateById(row);
    }

    @Override
    @Transactional
    public void publish(Long id) {
        AiKnowledge row = requireRow(id);
        if (AiKnowledgeStatus.PUBLISHED_VALUE.equals(row.getStatus())) {
            return; // 幂等：已发布不重复向量化
        }
        List<float[]> vectors = embeddingClient.embed(List.of(row.getQuestion()));
        if (vectors == null || vectors.isEmpty()) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR.getCode(), "知识发布失败：向量化返回为空，条目保持原状态");
        }
        int updated = knowledgeMapper.publishWithEmbedding(id, VectorText.of(vectors.get(0)));
        if (updated == 0) {
            throw new BusinessException(ErrorCode.NOT_FOUND.getCode(), NOT_FOUND_MSG);
        }
    }

    @Override
    @Transactional
    public void unpublish(Long id) {
        AiKnowledge row = requireRow(id);
        if (!AiKnowledgeStatus.PUBLISHED_VALUE.equals(row.getStatus())) {
            throw new BusinessException(ErrorCode.CONFLICT.getCode(), "仅已发布条目可停用");
        }
        row.setStatus(AiKnowledgeStatus.DISABLED_VALUE);
        knowledgeMapper.updateById(row);
    }

    @Override
    public void delete(Long id) {
        requireRow(id);
        knowledgeMapper.deleteById(id);
    }

    private AiKnowledge requireRow(Long id) {
        AiKnowledge row = knowledgeMapper.selectById(id);
        if (row == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND.getCode(), NOT_FOUND_MSG);
        }
        return row;
    }

    private void applyForm(AiKnowledge row, AiKnowledgeFormDTO form) {
        row.setCategory(form.getCategory());
        row.setQuestion(form.getQuestion().trim());
        row.setAnswer(form.getAnswer().trim());
        row.setFaq(Boolean.TRUE.equals(form.getFaq()));
        List<String> kws = form.getKeywords();
        if (kws == null || kws.isEmpty()) {
            row.setKeywords(null);
        } else {
            row.setKeywords(kws.stream().filter(k -> k != null && !k.isBlank())
                    .map(String::trim).distinct().toArray(String[]::new));
        }
    }

    private List<AiKnowledgeVO> toVos(List<AiKnowledge> rows) {
        if (rows == null || rows.isEmpty()) {
            return Collections.emptyList();
        }
        // 批量取已向量化 id，填充 embeddingFilled（避免 N+1）
        Set<Long> embedded = new HashSet<>(
                knowledgeMapper.selectEmbeddedIds(rows.stream().map(AiKnowledge::getId).toList()));
        List<AiKnowledgeVO> vos = new ArrayList<>(rows.size());
        for (AiKnowledge row : rows) {
            AiKnowledgeVO vo = baseVo(row);
            vo.setEmbeddingFilled(embedded.contains(row.getId()));
            vos.add(vo);
        }
        return vos;
    }

    private AiKnowledgeVO toVo(AiKnowledge row, boolean embeddingFilled) {
        AiKnowledgeVO vo = baseVo(row);
        vo.setEmbeddingFilled(embeddingFilled);
        return vo;
    }

    private AiKnowledgeVO baseVo(AiKnowledge row) {
        AiKnowledgeVO vo = new AiKnowledgeVO();
        vo.setId(row.getId());
        vo.setCategory(row.getCategory());
        AiKnowledgeCategory cat = AiKnowledgeCategory.fromValueOrNull(row.getCategory());
        vo.setCategoryName(cat == null ? row.getCategory() : cat.getDisplayName());
        vo.setQuestion(row.getQuestion());
        vo.setAnswer(row.getAnswer());
        String[] kws = row.getKeywords();
        vo.setKeywords(kws == null ? null : List.of(kws));
        vo.setFaq(row.getFaq());
        vo.setStatus(row.getStatus());
        AiKnowledgeStatus st = AiKnowledgeStatus.fromValueOrNull(row.getStatus());
        vo.setStatusName(st == null ? row.getStatus() : st.getDisplayName());
        vo.setCreatedAt(row.getCreatedAt());
        vo.setUpdatedAt(row.getUpdatedAt());
        return vo;
    }

    private boolean isNotBlank(String s) {
        return s != null && !s.isBlank();
    }
}
