package com.dsmarket.modules.admin.dto;

import com.dsmarket.modules.order.dto.AdminOrderListVO;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/** 管理端数据概览 */
@Data
public class AdminStatsVO {

    private long userCount;
    private long productCount;
    private long orderCount;
    private long pendingShipCount;
    private BigDecimal totalSalesAmount;
    private List<AdminOrderListVO> recentOrders;
}
