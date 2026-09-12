package com.dsmarket.modules.ai.controller;

import com.dsmarket.common.domain.ApiResponse;
import com.dsmarket.common.domain.PageResult;
import com.dsmarket.modules.ai.dto.AiIssueVO;
import com.dsmarket.modules.ai.dto.IssueAdoptRequest;
import com.dsmarket.modules.ai.service.AiIssueService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 问题池后台管理（C3 F3~F5 / 主 REQ F7，权限矩阵：/api/v1/admin/ai/** 仅 ADMIN —— SecurityConfig 已锁）。
 *
 * <p>采纳 {@code POST /{id}/adopt}：写知识 draft + 同步向量化即发布；向量化失败保留草稿由 F6 定时兜底，
 * 返回 {@code published:false} 供前端提示"已受理，待自动发布"。忽略 {@code POST /{id}/ignore} 幂等。</p>
 */
@RestController
@RequestMapping("/api/v1/admin/ai/issues")
@RequiredArgsConstructor
public class AdminAiIssueController {

    private final AiIssueService issueService;

    @GetMapping
    public ApiResponse<PageResult<AiIssueVO>> page(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String source) {
        return ApiResponse.success(issueService.adminPage(page, size, status, source));
    }

    /** 采纳：写知识并同步向量化发布。返回 {ok, knowledgeId, published}；published=false=已受理待 F6 自动发布 */
    @PostMapping("/{id}/adopt")
    public ApiResponse<Map<String, Object>> adopt(@PathVariable Long id,
                                                  @Valid @RequestBody IssueAdoptRequest request) {
        return ApiResponse.success(issueService.adopt(id, request));
    }

    /** 忽略：pending→ignored；已处置幂等；不存在 404 */
    @PostMapping("/{id}/ignore")
    public ApiResponse<Void> ignore(@PathVariable Long id) {
        issueService.ignore(id);
        return ApiResponse.success();
    }
}
