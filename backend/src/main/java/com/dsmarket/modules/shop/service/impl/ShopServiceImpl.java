package com.dsmarket.modules.shop.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dsmarket.common.domain.PageResult;
import com.dsmarket.common.enums.ShopStatusEnum;
import com.dsmarket.common.enums.UserRoleEnum;
import com.dsmarket.common.exception.BusinessException;
import com.dsmarket.common.exception.ErrorCode;
import com.dsmarket.modules.shop.dto.ApplyShopRequest;
import com.dsmarket.modules.shop.dto.AuditShopRequest;
import com.dsmarket.modules.shop.dto.CloseShopRequest;
import com.dsmarket.modules.shop.dto.ShopAdminRow;
import com.dsmarket.modules.shop.dto.ShopAdminVO;
import com.dsmarket.modules.shop.dto.ShopPublicVO;
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

        // 放行条件**只看 2（已驳回）**：那是"申请没过、改了再来"。
        // 已开通(1) 与 已关闭(3) 一律拒绝 —— 后者是 REQ-20260913-店铺关闭能力 Q2 的拍板
        // （关闭即终局），它同时也是"关闭不会被一次重新申请复活"的实现点。
        Shop existing = findByUserId(userId);
        if (existing != null && !ShopStatusEnum.REJECTED.is(existing.getStatus())) {
            throw new BusinessException(ErrorCode.CONFLICT.getCode(),
                    ShopStatusEnum.CLOSED.is(existing.getStatus())
                            ? "店铺已被关闭，不能重新申请入驻"
                            : "已提交过入驻申请，请耐心等待审核");
        }

        // 未入驻 → 新建；被驳回 → 覆盖旧申请并重置为待审核
        Shop shop = existing != null ? existing : new Shop();
        if (existing == null) {
            shop.setUserId(userId);
        }
        shop.setShopName(request.getShopName());
        shop.setLogo(request.getLogo());
        shop.setDescription(request.getDescription());
        shop.setStatus(ShopStatusEnum.PENDING.getValue());
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
    public ShopPublicVO getPublicShop(Long shopId) {
        // selectById 带 @TableLogic：已软删的店铺直接返回 null。于是"不存在"与"已软删"合流成同一个
        // 404，不给访客区分的机会（§8.3）；未开通（含待审核 0、已驳回 2、已关闭 3）同样 404。
        // 新增的 3 不需要任何改动就自动落进这里 —— 这正是方案甲"判据不用动"的好处。
        Shop shop = shopMapper.selectById(shopId);
        if (shop == null || !ShopStatusEnum.OPEN.is(shop.getStatus())) {
            throw new BusinessException(ErrorCode.NOT_FOUND.getCode(), "店铺不存在或已关闭");
        }
        return ShopPublicVO.from(shop);
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
        if (!ShopStatusEnum.PENDING.is(shop.getStatus())) {
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

    @Override
    @Transactional
    public void closeShop(Long shopId, CloseShopRequest request) {
        Shop shop = shopMapper.selectById(shopId);
        if (shop == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND.getCode(), "店铺不存在");
        }
        // 只允许 1 → 3。待审核(0) 该走驳回、已驳回(2) 本来就不可见、已关闭(3) 重复关闭按 Q4 拍板返 409。
        // 用"非 OPEN 一律拒绝"而不是"逐个列举非法状态"：将来新增状态时默认被挡，而不是默认放行。
        if (!ShopStatusEnum.OPEN.is(shop.getStatus())) {
            throw new BusinessException(ErrorCode.CONFLICT.getCode(),
                    ShopStatusEnum.CLOSED.is(shop.getStatus())
                            ? "该店铺已关闭"
                            : "只有已开通的店铺才能关闭");
        }

        shop.setStatus(ShopStatusEnum.CLOSED.getValue());
        // audit_remark 的语义是"最近一次管理动作的备注"，与 apply 重新提交时清空旧备注同一套约定：
        // 无条件写入，理由缺省即清空 —— 避免把上一条"审核通过"的备注误读成"关闭理由"。
        shop.setAuditRemark(request == null ? null : request.getAuditRemark());
        shopMapper.updateById(shop);

        // 以下三件事**刻意不做**，改动时请勿"顺手补上"：
        //   1. 不降 user.role —— Q3 拍板不降（与"驳回不降 role"的既有约定一致）；
        //   2. 不下架 dsm_product —— 可见性由读侧判据（ProductVisibility）在查询时决定，
        //      写侧级联覆盖不了"本次改动之前就已关闭"的店铺（REQ-20260913 §12.2 A6）；
        //   3. 不动任何订单 —— 已成交订单不受影响（§9 E6、§11 第 3 条）。
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
