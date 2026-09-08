package com.dsmarket.modules.ai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 知识条目写入表单（F6 后台 create/update 共用）。
 *
 * <p>不含 status：状态机由服务端控制（create/编辑一律 draft，发布走独立动作）。
 * answer 上限 2000 / question 上限 500 为 C2 全写入路径统一校验（含 C3-F4 采纳，后续复用本 DTO）。</p>
 */
@Data
public class AiKnowledgeFormDTO {

    /** 类目：after_sale/shipping/payment/product_policy/account/other */
    @NotBlank(message = "类目不能为空")
    @Pattern(regexp = "after_sale|shipping|payment|product_policy|account|other",
            message = "类目不合法")
    private String category;

    @NotBlank(message = "标准问题不能为空")
    @Size(max = 500, message = "标准问题不能超过500字")
    private String question;

    @NotBlank(message = "标准答案不能为空")
    @Size(max = 2000, message = "标准答案不能超过2000字")
    private String answer;

    /** 可检索别名，单个 ≤32 */
    private List<@Size(max = 32, message = "别名单个不能超过32字") String> keywords;

    /** true=参与 FAQ 兜底精选 */
    private Boolean faq;
}
