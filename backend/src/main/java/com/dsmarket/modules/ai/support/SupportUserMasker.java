package com.dsmarket.modules.ai.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dsmarket.modules.user.entity.User;
import com.dsmarket.modules.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * 买家标识脱敏（C4 §7 数据最小化：工作台默认只展示脱敏用户信息）。
 *
 * <p>抽成独立组件而不是各自私有方法，是因为同一条规则有<b>两个出口</b>：
 * 工作台列表快照（{@code GET …/sessions}）与 admin 流的 {@code session_new} 事件
 * —— 两处若各写一份，早晚会漂移成"列表里脱敏、事件里漏出全名"。
 * 规则只有一处实现，就不会有第二个版本。</p>
 *
 * <p>规则：保留首尾字符、中间以 {@code ***} 代替（{@code aie2e_bot → a***t}）。
 * 查不到用户（已删除）→ {@code 用户#{id}}，不因为缺名字就让工作台那一行空白。</p>
 */
@Component
@RequiredArgsConstructor
public class SupportUserMasker {

    private final UserMapper userMapper;

    /** 单个用户脱敏；{@code userId} 为 null 时返回占位串而非抛错（事件载荷容忍缺值） */
    public String mask(Long userId) {
        if (userId == null) {
            return "未知用户";
        }
        return maskName(userMapper.selectById(userId), userId);
    }

    /** 批量脱敏（工作台三列表一次取回，避免 N+1）；缺失的用户各自回落为 {@code 用户#{id}} */
    public Map<Long, String> maskAll(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> masked = new HashMap<>();
        for (User u : userMapper.selectList(new LambdaQueryWrapper<User>().in(User::getId, userIds))) {
            masked.put(u.getId(), maskName(u, u.getId()));
        }
        return masked;
    }

    /** 取脱敏名；列表已在 {@link #maskAll} 取过时用它兜底，省掉二次查询 */
    public String maskOrFallback(Map<Long, String> masked, Long userId) {
        return masked.getOrDefault(userId, "用户#" + userId);
    }

    private String maskName(User user, Long userId) {
        String username = user == null ? null : user.getUsername();
        if (username == null || username.isBlank()) {
            return "用户#" + userId;
        }
        String u = username.trim();
        if (u.length() == 1) {
            return u.charAt(0) + "***";
        }
        return u.charAt(0) + "***" + u.charAt(u.length() - 1);
    }
}
