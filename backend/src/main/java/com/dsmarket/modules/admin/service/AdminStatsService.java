package com.dsmarket.modules.admin.service;

import com.dsmarket.modules.admin.dto.AdminStatsVO;

public interface AdminStatsService {

    /** 数据概览：统计卡片 + 最近订单 */
    AdminStatsVO getStats();
}
