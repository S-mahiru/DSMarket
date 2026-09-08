package com.dsmarket.modules.shop.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dsmarket.common.domain.PageResult;
import com.dsmarket.common.enums.UserRoleEnum;
import com.dsmarket.common.exception.BusinessException;
import com.dsmarket.common.exception.ErrorCode;
import com.dsmarket.modules.shop.dto.ApplyShopRequest;
import com.dsmarket.modules.shop.dto.AuditShopRequest;
import com.dsmarket.modules.shop.dto.ShopAdminRow;
import com.dsmarket.modules.shop.dto.ShopAdminVO;
import com.dsmarket.modules.shop.dto.ShopVO;
import com.dsmarket.modules.shop.entity.Shop;
import com.dsmarket.modules.shop.mapper.ShopMapper;
import com.dsmarket.modules.shop.service.ShopService;
import com.dsmarket.modules.user.entity.User;
import com.dsmarket.modules.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ShopServiceImpl implements ShopService {

    private final ShopMapper shopMapper;
    private final UserMapper userMapper;

    @Override
    @Transactional
    public ShopVO apply(Long userId, ApplyShopRequest request) {
        User user = requireUser(userId);
        if (UserRoleEnum.ADMIN.getValue().equals(user.getRole())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), "管理员无需入驻");
        }

        Shop existing = findByUserId(userId);
        if (existing != null && !Integer.valueOf(2).equals(existing.getStatus())) {
            throw new BusinessException(ErrorCode.CONFLICT.getCode(), "已提交过入驻申请，请耐心等待审核");
        }

        // 未入驻 → 新建；被驳回 → 覆盖旧申请并重置为待审核
        Shop shop = existing != null ? existing : new Shop();
        if (existing == null) {
            shop.setUserId(userId);
        }
        shop.setShopName(request.getShopName());
        shop.setLogo(request.getLogo());
        shop.setDescription(request.getDescription());
        shop.setStatus(0);
        shop.setAuditRemark(null);

        if (existing == null) {
            shopMapper.insert(shop);
        } else {
            // auditRemark 配置了 updateStrategy=ALWAYS，updateById 会连同 null 一起写入（清空旧审核备注）
            shopMapper.updateById(shop);
        }
        return ShopVO.from(shop);
    }

    @Override
    public ShopVO getMine(Long userId) {
        Shop shop = findByUserId(userId);
        return shop == null ? null : ShopVO.from(shop);
    }

    @Override
    public PageResult<ShopAdminVO> adminPage(Integer status, String keyword, long page, long size) {
        IPage<ShopAdminRow> p = shopMapper.selectAdminPage(new Page<>(page, size), status,
                StringUtils.hasText(keyword) ? keyword : null);
        List<ShopAdminVO> vos = p.getRecords().stream().map(ShopAdminVO::from).toList();
        return PageResult.of(vos, p.getTotal(), page, size);
    }

    @Override
    @Transactional
    public void adminAudit(Long shopId, AuditShopRequest request) {
        if (request.getStatus() == null || (request.getStatus() != 1 && request.getStatus() != 2)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST.getCode(), "审核结果参数错误");
        }
        Shop shop = shopMapper.selectById(shopId);
        if (shop == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND.getCode(), "店铺不存在");
        }
        if (!Integer.valueOf(0).equals(shop.getStatus())) {
            throw new BusinessException(ErrorCode.CONFLICT.getCode(), "该申请已处理，不能重复审核");
        }

        shop.setStatus(request.getStatus());
        shop.setAuditRemark(request.getAuditRemark());
        shopMapper.updateById(shop);

        // 通过 → 开通商家身份
        if (request.getStatus() == 1) {
            User user = requireUser(shop.getUserId());
            user.setRole(UserRoleEnum.MERCHANT.getValue());
            userMapper.updateById(user);
        }
    }

    private Shop findByUserId(Long userId) {
        return shopMapper.selectOne(new LambdaQueryWrapper<Shop>()
                .eq(Shop::getUserId, userId)
                .last("LIMIT 1"));
    }

    private User requireUser(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND.getCode(), "用户不存在");
        }
        return user;
    }
}
