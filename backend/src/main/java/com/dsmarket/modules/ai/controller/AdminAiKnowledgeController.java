package com.dsmarket.modules.ai.controller;

import com.dsmarket.common.domain.ApiResponse;
import com.dsmarket.common.domain.PageResult;
import com.dsmarket.modules.ai.dto.AiKnowledgeFormDTO;
import com.dsmarket.modules.ai.dto.AiKnowledgeQuery;
import com.dsmarket.modules.ai.dto.AiKnowledgeVO;
import com.dsmarket.modules.ai.service.AiKnowledgeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 知识库后台管理（C2 F6 / 主 REQ F6，权限矩阵：/api/v1/admin/ai/** 仅 ADMIN —— SecurityConfig 已锁）。
 *
 * <p>发布/停用是显式状态动作（发布需成功向量化），编辑已发布条目由服务端回退 draft。</p>
 */
@RestController
@RequestMapping("/api/v1/admin/ai/knowledge")
@RequiredArgsConstructor
public class AdminAiKnowledgeController {

    private final AiKnowledgeService knowledgeService;

    @GetMapping
    public ApiResponse<PageResult<AiKnowledgeVO>> page(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String status) {
        AiKnowledgeQuery query = new AiKnowledgeQuery();
        query.setKeyword(keyword);
        query.setCategory(category);
        query.setStatus(status);
        return ApiResponse.success(knowledgeService.adminPage(page, size, query));
    }

    @GetMapping("/{id}")
    public ApiResponse<AiKnowledgeVO> detail(@PathVariable Long id) {
        return ApiResponse.success(knowledgeService.detail(id));
    }

    @PostMapping
    public ApiResponse<Long> create(@Valid @RequestBody AiKnowledgeFormDTO form) {
        return ApiResponse.success(knowledgeService.create(form));
    }

    @PutMapping("/{id}")
    public ApiResponse<Void> update(@PathVariable Long id, @Valid @RequestBody AiKnowledgeFormDTO form) {
        knowledgeService.update(id, form);
        return ApiResponse.success();
    }

    /** 发布：同步向量化成功才置 published；失败 → HTTP 502 且条目保持原状态 */
    @PostMapping("/{id}/publish")
    public ApiResponse<Void> publish(@PathVariable Long id) {
        knowledgeService.publish(id);
        return ApiResponse.success();
    }

    /** 停用：published→disabled */
    @PostMapping("/{id}/unpublish")
    public ApiResponse<Void> unpublish(@PathVariable Long id) {
        knowledgeService.unpublish(id);
        return ApiResponse.success();
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        knowledgeService.delete(id);
        return ApiResponse.success();
    }
}
