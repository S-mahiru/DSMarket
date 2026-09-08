package com.dsmarket.modules.order.service;

import com.dsmarket.common.domain.PageResult;
import com.dsmarket.modules.order.dto.AdminOrderListVO;
import com.dsmarket.modules.order.dto.CreateOrderRequest;
import com.dsmarket.modules.order.dto.OrderCreateResult;
import com.dsmarket.modules.order.dto.OrderDetailVO;
import com.dsmarket.modules.order.dto.OrderListVO;

public interface OrderService {

    /** 创建订单（事务：库存校验→扣库存→生成订单→清空购物车） */
    OrderCreateResult create(Long userId, CreateOrderRequest request);

    /** 订单列表（状态筛选 + 分页） */
    PageResult<OrderListVO> list(Long userId, Integer status, long page, long size);

    /** 订单详情（时间线 + 商品快照 + 地址快照） */
    OrderDetailVO detail(Long userId, String orderNo);

    /** 取消订单（仅待付款，恢复库存） */
    void cancel(Long userId, String orderNo, String reason);

    /** 确认收货（仅已发货） */
    void receive(Long userId, String orderNo);

    /** 管理端：全部订单分页（状态/关键字过滤，含买家名） */
    PageResult<AdminOrderListVO> adminPage(Integer status, String keyword, long page, long size);

    /** 管理端：发货（仅已付款 → 已发货 + 发货时间） */
    void ship(String orderNo);
}
