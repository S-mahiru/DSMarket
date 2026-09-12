package com.dsmarket.modules.ai.support;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 人工态 SSE 连接注册表（C4 §4.7/§4.8）。
 *
 * <p>与 C1 的 {@code /chat} 通道的根本差别：{@code /chat} 是<b>请求作用域</b>的一次性流
 * （一问一答即 complete），而人工通道是<b>常驻</b>通道 —— 买家打开抽屉期间、坐席打开工作台期间
 * 一直挂着，事件由业务动作（买家发消息/坐席接入/系统降级）从别的线程触发。
 * 因此必须有一份"userId/adminId → 活跃连接"的注册表，让业务线程找得到连接。
 * 这是 C1 没做过的基建（切片 2 新建）。</p>
 *
 * <p>同一 userId/adminId 可挂<b>多条</b>连接：买家多标签页、坐席多标签页并行
 * （§4.2 P5 决议：多标签共享同一在线键，不做顶号互斥），事件向该 key 下全部连接广播。</p>
 */
public interface SupportStreamRegistry {

    /** 登记一条买家连接（多标签累加）。返回的 SseEmitter 由控制器原样返回给客户端 */
    SseEmitter registerBuyer(Long userId);

    /** 登记一条坐席工作台连接（多标签累加） */
    SseEmitter registerAdmin(Long adminId);

    /** 向全部人工态连接发一次 keepalive 注释帧（不产生 data 事件，客户端忽略） */
    void pingAll();

    /** 当前买家连接数（诊断/自检用） */
    int buyerConnectionCount();

    /** 当前坐席连接数（诊断/自检用） */
    int adminConnectionCount();
}
