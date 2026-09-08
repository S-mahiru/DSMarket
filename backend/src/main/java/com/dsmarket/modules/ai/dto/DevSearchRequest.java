package com.dsmarket.modules.ai.dto;

import lombok.Data;

/**
 * 知识检索调试请求（C2 切片 2 临时端点，非最终 C1 契约）。
 */
@Data
public class DevSearchRequest {

    /** 用户问句；空/缺省由服务层当作空 query 处理（返回 covered=false），不校验。 */
    private String query;
}
