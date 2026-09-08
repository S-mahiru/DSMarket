package com.dsmarket.modules.ai.model;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 工具声明（映射 OpenAI tools[].function），随请求发给模型。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolSpec {

    private String name;
    private String description;

    /** JSON Schema（function.parameters）；无参工具可置 null */
    private JsonNode parameters;
}
