package com.dsmarket.modules.ai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 问题池采纳请求（C3-F4）。question 取问题池原问句（采集时已存），后台仅补充标准答案与类目。
 *
 * <p>answer 上限 2000 / 类目六枚举与 {@link AiKnowledgeFormDTO} 对齐（采纳写入知识库的行与手写录入同契约）。</p>
 */
@Data
public class IssueAdoptRequest {

    /** 标准答案（入库 trim） */
    @NotBlank(message = "采纳答案不能为空")
    @Size(max = 2000, message = "采纳答案不能超过2000字")
    private String answer;

    /** 类目：after_sale/shipping/payment/product_policy/account/other */
    @NotBlank(message = "类目不能为空")
    @Pattern(regexp = "after_sale|shipping|payment|product_policy|account|other",
            message = "类目不合法")
    private String category;
}
