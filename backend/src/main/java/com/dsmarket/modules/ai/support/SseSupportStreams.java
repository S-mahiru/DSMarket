package com.dsmarket.modules.ai.support;

import com.dsmarket.modules.ai.config.AiProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 人工态双 SSE 通道的实现（C4 §4.7 买家通道 / §4.8 admin 通道）。
 *
 * <p>一个 Bean 承担两个角色（{@link SupportStreamRegistry} 连接簿记 + {@link SupportEventPublisher}
 * 事件语义），因为二者共用同一份连接表；分开成两个类只会让"往哪张表发"变成需要跨对象确认的事。
 * 而<b>依赖方按角色收窄</b>：控制器只拿 Registry（能登记连接、不能伪造事件），
 * 业务服务只拿 Publisher（能发事件、看不到 SseEmitter）。</p>
 *
 * <h3>三通道物理隔离怎么落</h3>
 * §4.7/§4.8 要求人工态两条通道与 C1 AI 态通道<b>互不混发</b>（验收 §8-5/§8-5b）。
 * 隔离在三个层面同时成立：①接口不同（本类只管 {@code /support/stream} 与
 * {@code /admin/ai/support/stream} 两条，C1 的 {@code /chat} 走自己的 SseChatStream）；
 * ②连接表不同（买家表与坐席表互不可见，故买家的 {@code human_message} 不可能广播到工作台）；
 * ③事件类型<b>白名单</b>——发错类型（如把 {@code delta} 塞进人工通道、把 {@code human_*} 塞进
 * admin 通道）直接抛异常，不静默丢弃。白名单是刻意选择"响亮失败"的：
 * 通道串味是硬规则违反，让它悄悄过去比炸掉更糟。</p>
 *
 * <p>断连处理：写失败即认为该连接已死，从表里摘除并 complete；多标签下只摘死的那一条，
 * 不影响同用户其它标签。注册时挂 onCompletion/onTimeout/onError 三个回调做自清理
 * —— 客户端正常关闭、超时、异常三条路径都能回收，避免连接表泄漏。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SseSupportStreams implements SupportStreamRegistry, SupportEventPublisher {

    /** 买家通道事件白名单（§4.7） */
    static final Set<String> BUYER_TYPES = Set.of(
            SupportEvents.HUMAN_STATUS, SupportEvents.HUMAN_MESSAGE,
            SupportEvents.HUMAN_OFFLINE_TIP, SupportEvents.HUMAN_CLOSE);

    /** admin 通道事件白名单（§4.8） */
    static final Set<String> ADMIN_TYPES = Set.of(
            SupportEvents.SESSION_NEW, SupportEvents.SESSION_STATUS,
            SupportEvents.MESSAGE_NEW, SupportEvents.SEAT_STATUS);

    /** 广播给全体坐席时**不得**出现的类型：seat_status 只与某一个坐席有关（§4.8 语义） */
    static final Set<String> ADMIN_BROADCAST_TYPES = Set.of(
            SupportEvents.SESSION_NEW, SupportEvents.SESSION_STATUS, SupportEvents.MESSAGE_NEW);

    private final ObjectMapper objectMapper;
    private final AiProperties properties;

    private final Map<Long, Set<SseEmitter>> buyerStreams = new ConcurrentHashMap<>();
    private final Map<Long, Set<SseEmitter>> adminStreams = new ConcurrentHashMap<>();

    // ------------------------------------------------------------ SupportStreamRegistry

    @Override
    public SseEmitter registerBuyer(Long userId) {
        return register(buyerStreams, userId, "buyer");
    }

    @Override
    public SseEmitter registerAdmin(Long adminId) {
        return register(adminStreams, adminId, "admin");
    }

    @Override
    public void pingAll() {
        // SSE 注释帧（": ping"）不是 data 事件，客户端事件解析不会看到它，
        // 目的是让中间代理/容器看到流量从而不掐掉长连接。
        ping(buyerStreams);
        ping(adminStreams);
    }

    @Override
    public int buyerConnectionCount() {
        return count(buyerStreams);
    }

    @Override
    public int adminConnectionCount() {
        return count(adminStreams);
    }

    // ------------------------------------------------------------ SupportEventPublisher

    @Override
    public void toBuyer(Long userId, String type, Map<String, Object> payload) {
        requireType(type, BUYER_TYPES, "买家通道");
        if (userId == null) {
            return;
        }
        dispatch(buyerStreams.get(userId), type, payload);
    }

    @Override
    public void toAdmins(String type, Map<String, Object> payload) {
        requireType(type, ADMIN_BROADCAST_TYPES, "admin 广播通道");
        adminStreams.values().forEach(set -> dispatch(set, type, payload));
    }

    @Override
    public void toAdmin(Long adminId, String type, Map<String, Object> payload) {
        requireType(type, ADMIN_TYPES, "admin 通道");
        if (adminId == null) {
            return;
        }
        dispatch(adminStreams.get(adminId), type, payload);
    }

    // ------------------------------------------------------------ 私有

    /**
     * 创建连接用的 SseEmitter。抽成可覆写的方法是为了让测试能注入记录型实现 ——
     * 否则"事件到底发出去了什么"只能靠起容器 + 真客户端才能观察到，
     * 而通道隔离这种硬规则恰恰最需要被单测盯死。
     */
    protected SseEmitter newEmitter() {
        return new SseEmitter(properties.getSupport().getStreamTimeout().toMillis());
    }

    private SseEmitter register(Map<Long, Set<SseEmitter>> table, Long key, String channel) {
        SseEmitter emitter = newEmitter();
        Set<SseEmitter> set = table.computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet());
        set.add(emitter);

        Runnable cleanup = () -> {
            set.remove(emitter);
            if (set.isEmpty()) {
                // 两参 remove：只在仍是同一个 set 时移除，避免与并发 register 竞态丢掉新连接
                table.remove(key, set);
            }
        };
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(e -> {
            log.debug("[ai][c4] {} 通道连接异常断开. key={} err={}", channel, key, e.getMessage());
            cleanup.run();
        });
        log.info("[ai][c4] {} 通道已连接. key={} 本 key 连接数={} 全通道={}",
                channel, key, set.size(), count(table));
        return emitter;
    }

    private void dispatch(Set<SseEmitter> set, String type, Map<String, Object> payload) {
        if (set == null || set.isEmpty()) {
            return;
        }
        String json;
        try {
            // §4.7/§4.8 的每个事件都以 type 区分（前端据此分派），type 由发送方给出而非载荷自带。
            // 拷一层再放 type：不改动调用方传入的 map，且保证 type 序列化在首位。
            Map<String, Object> frame = new LinkedHashMap<>();
            frame.put("type", type);
            frame.putAll(payload);
            json = objectMapper.writeValueAsString(frame);
        } catch (Exception e) {
            // 载荷序列化失败属代码缺陷（如塞了不可序列化对象）：记日志、不外抛打垮业务线程
            log.error("[ai][c4] 事件载荷序列化失败，本事件丢弃. type={}", type, e);
            return;
        }
        for (SseEmitter emitter : set) {
            try {
                emitter.send(SseEmitter.event()
                        .data(json, MediaType.APPLICATION_JSON));
            } catch (IOException | IllegalStateException e) {
                // 连接已断（或响应已关）：摘掉这一条，不影响同 key 的其它标签页
                log.debug("[ai][c4] 事件写出失败，摘除该连接. type={} err={}", type, e.getMessage());
                set.remove(emitter);
                completeQuietly(emitter);
            }
        }
    }

    private void ping(Map<Long, Set<SseEmitter>> table) {
        table.values().forEach(set -> {
            for (SseEmitter emitter : set) {
                try {
                    emitter.send(SseEmitter.event().comment("ping"));
                } catch (Exception e) {
                    set.remove(emitter);
                    completeQuietly(emitter);
                }
            }
        });
    }

    private void completeQuietly(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (Exception ignored) {
            // 已断开：complete 抛异常无意义
        }
    }

    private int count(Map<Long, Set<SseEmitter>> table) {
        return table.values().stream().mapToInt(Set::size).sum();
    }

    /** 事件类型白名单校验：越界即代码缺陷，响亮失败而非静默丢弃（隔离是硬规则） */
    private void requireType(String type, Set<String> allowed, String channel) {
        if (!allowed.contains(type)) {
            log.error("[ai][c4] {} 收到越界事件类型，拒绝发送（三通道隔离硬规则）. type={} 允许={}",
                    channel, type, allowed);
            throw new IllegalArgumentException("事件类型 " + type + " 不属于" + channel + "：" + allowed);
        }
    }
}
