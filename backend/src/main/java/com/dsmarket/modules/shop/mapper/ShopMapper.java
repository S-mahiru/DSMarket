package com.dsmarket.modules.shop.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.dsmarket.modules.shop.dto.ShopAdminRow;
import com.dsmarket.modules.shop.entity.Shop;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface ShopMapper extends BaseMapper<Shop> {

    /** 管理端店铺分页联查（LEFT JOIN 用户表取商家用户名，支持状态/关键词过滤） */
    @Select("""
            <script>
            SELECT s.id, s.user_id, s.shop_name, s.logo, s.description,
                   s.status, s.audit_remark, s.created_at, s.updated_at,
                   u.username AS owner_username
            FROM dsm_shop s
            LEFT JOIN dsm_user u ON u.id = s.user_id
            WHERE s.deleted = 0
            <if test="status != null"> AND s.status = #{status} </if>
            <if test="keyword != null and keyword != ''">
              AND (s.shop_name ILIKE '%' || #{keyword} || '%'
                   OR u.username ILIKE '%' || #{keyword} || '%')
            </if>
            ORDER BY s.id DESC
            </script>
            """)
    IPage<ShopAdminRow> selectAdminPage(IPage<ShopAdminRow> page,
                                        @Param("status") Integer status,
                                        @Param("keyword") String keyword);
}
