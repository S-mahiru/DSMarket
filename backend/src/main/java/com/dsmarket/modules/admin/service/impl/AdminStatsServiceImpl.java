package com.dsmarket.modules.admin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dsmarket.common.enums.OrderStatusEnum;
import com.dsmarket.modules.admin.dto.AdminStatsVO;
import com.dsmarket.modules.admin.service.AdminStatsService;
import com.dsmarket.modules.order.entity.OrderInfo;
import com.dsmarket.modules.order.mapper.OrderInfoMapper;
import com.dsmarket.modules.order.service.OrderService;
import com.dsmarket.modules.product.mapper.ProductMapper;
import com.dsmarket.modules.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AdminStatsServiceImpl implements AdminStatsService {

    private final UserMapper userMapper;
    private final ProductMapper productMapper;
    private final OrderInfoMapper orderInfoMapper;
    private final OrderService orderService;

    @Override
    public AdminStatsVO getStats() {
        AdminStatsVO vo = new AdminStatsVO();
        vo.setUserCount(userMapper.selectCount(null));
        vo.setProductCount(productMapper.selectCount(null));
        vo.setOrderCount(orderInfoMapper.selectCount(null));
        vo.setPendingShipCount(orderInfoMapper.selectCount(new LambdaQueryWrapper<OrderInfo>()
                .eq(OrderInfo::getStatus, OrderStatusEnum.PAID.getValue())));
        vo.setTotalSalesAmount(orderInfoMapper.sumPaidAmount());
        vo.setRecentOrders(orderService.adminPage(null, null, 1, 5).getRecords());
        return vo;
    }
}
