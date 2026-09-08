package com.dsmarket.modules.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.dsmarket.modules.order.dto.AdminOrderRow;
import com.dsmarket.modules.order.entity.OrderInfo;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;

public interface OrderInfoMapper extends BaseMapper<OrderInfo> {

    /** 管理端订单分页联查（LEFT JOIN 用户表取买家名，支持状态/关键字过滤） */
    @Select("""
            <script>
            SELECT o.id, o.order_no, o.user_id, u.username AS buyer_name,
                   o.total_amount, o.shipping_fee, o.actual_amount, o.payment_amount,
                   o.status, o.payment_time, o.delivery_time, o.created_at
            FROM dsm_order_info o
            LEFT JOIN dsm_user u ON u.id = o.user_id
            WHERE o.deleted = 0
            <if test="status != null"> AND o.status = #{status} </if>
            <if test="keyword != null and keyword != ''">
              AND (o.order_no ILIKE '%' || #{keyword} || '%'
                   OR u.username ILIKE '%' || #{keyword} || '%')
            </if>
            ORDER BY o.id DESC
            </script>
            """)
    IPage<AdminOrderRow> selectAdminPage(IPage<AdminOrderRow> page,
                                         @Param("status") Integer status,
                                         @Param("keyword") String keyword);

    /** 累计实付金额（已付款/已发货/已收货/已完成） */
    @Select("SELECT COALESCE(SUM(payment_amount), 0) FROM dsm_order_info " +
            "WHERE deleted = 0 AND status IN (1, 2, 3, 4)")
    BigDecimal sumPaidAmount();
}
