package com.dsmarket.modules.ai.tool;

import com.dsmarket.modules.ai.model.ToolSpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 客服工具注册表：收集全部 {@link ChatTool} Bean 并按 name 索引。
 * Agent 编排据此给模型下发 tool 声明、并按其名字分发执行。
 */
@Slf4j
@Component
public class ToolRegistry {

    private final Map<String, ChatTool> byName = new ConcurrentHashMap<>();

    public ToolRegistry(List<ChatTool> tools) {
        for (ChatTool tool : tools) {
            byName.put(tool.name(), tool);
            log.info("[ai] 注册工具: {}", tool.name());
        }
    }

    public ChatTool get(String name) {
        return byName.get(name);
    }

    public boolean contains(String name) {
        return byName.containsKey(name);
    }

    /** 全部工具声明（随请求发给模型） */
    public List<ToolSpec> specs() {
        List<ToolSpec> specs = new ArrayList<>();
        for (ChatTool tool : byName.values()) {
            specs.add(ToolSpec.builder()
                    .name(tool.name())
                    .description(tool.description())
                    .parameters(tool.parameters())
                    .build());
        }
        return specs;
    }
}
