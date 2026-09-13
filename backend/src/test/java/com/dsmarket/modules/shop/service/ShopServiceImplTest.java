package com.dsmarket.modules.shop.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.dsmarket.common.domain.PageResult;
import com.dsmarket.common.enums.ShopStatusEnum;
import com.dsmarket.common.enums.UserRoleEnum;
import com.dsmarket.common.exception.BusinessException;
import com.dsmarket.modules.shop.dto.ApplyShopRequest;
import com.dsmarket.modules.shop.dto.AuditShopRequest;
import com.dsmarket.modules.shop.dto.CloseShopRequest;
import com.dsmarket.modules.shop.dto.ShopAdminRow;
import com.dsmarket.modules.shop.dto.ShopVO;
import com.dsmarket.modules.shop.entity.Shop;
import com.dsmarket.modules.shop.mapper.ShopMapper;
import com.dsmarket.modules.shop.service.impl.ShopServiceImpl;
import com.dsmarket.modules.user.entity.User;
import com.dsmarket.modules.user.mapper.UserMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 店铺 Service 层单元测试：入驻申请（重复申请/驳回重提清备注）、我的店铺、审核（通过升商家/驳回/重复审核）。
 */
@ExtendWith(MockitoExtension.class)
class ShopServiceImplTest {

    @Mock
    private ShopMapper shopMapper;
    @Mock
    private UserMapper userMapper;

    @InjectMocks
    private ShopServiceImpl service;

    private User user(Long id, String role) {
        User u = new User();
        u.setId(id);
        u.setUsername("u" + id);
        u.setRole(role);
        return u;
    }

    private Shop shop(Long id, Long userId, Integer status, String remark) {
        Shop s = new Shop();
        s.setId(id);
        s.setUserId(userId);
        s.setShopName("Test Shop");
        s.setStatus(status);
        s.setAuditRemark(remark);
        return s;
    }

    // ---------- 入驻申请 ----------

    @Test
    void apply_newUser_createsPendingShop() {
        when(userMapper.selectById(1L)).thenReturn(user(1L, UserRoleEnum.USER.getValue()));
        when(shopMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        ApplyShopRequest req = new ApplyShopRequest();
        req.setShopName("New Shop");
        req.setDescription("desc");

        ShopVO vo = service.apply(1L, req);

        assertEquals(0, vo.getStatus());
        assertEquals("待审核", vo.getStatusName());
        ArgumentCaptor<Shop> captor = ArgumentCaptor.forClass(Shop.class);
        verify(shopMapper).insert(captor.capture());
        assertEquals(1L, captor.getValue().getUserId());
        assertEquals("New Shop", captor.getValue().getShopName());
        verify(shopMapper, never()).update(any(Shop.class), any(Wrapper.class));
    }

    @Test
    void apply_alreadyPending_throwsConflict() {
        when(userMapper.selectById(1L)).thenReturn(user(1L, UserRoleEnum.USER.getValue()));
        when(shopMapper.selectOne(any(Wrapper.class))).thenReturn(shop(10L, 1L, 0, null));

        ApplyShopRequest req = new ApplyShopRequest();
        req.setShopName("Another Shop");

        BusinessException ex = assertThrows(BusinessException.class, () -> service.apply(1L, req));
        assertEquals(409, ex.getCode());
    }

    @Test
    void apply_afterReject_resubmitsAndClearsAuditRemark() {
        when(userMapper.selectById(1L)).thenReturn(user(1L, UserRoleEnum.USER.getValue()));
        when(shopMapper.selectOne(any(Wrapper.class))).thenReturn(shop(10L, 1L, 2, "old reject reason"));

        ApplyShopRequest req = new ApplyShopRequest();
        req.setShopName("Renamed Shop");

        ShopVO vo = service.apply(1L, req);

        assertEquals(0, vo.getStatus());
        verify(shopMapper, never()).insert(any(Shop.class));
        // 回归验证：被驳回后重新提交，updateById 必须把实体上的 auditRemark 清成 null
        // （依赖 Shop.auditRemark 的 @TableField(updateStrategy=ALWAYS) 使 null 也会被写入）
        ArgumentCaptor<Shop> shopCaptor = ArgumentCaptor.forClass(Shop.class);
        verify(shopMapper).updateById(shopCaptor.capture());
        assertEquals("Renamed Shop", shopCaptor.getValue().getShopName());
        assertEquals(0, shopCaptor.getValue().getStatus());
        assertNull(shopCaptor.getValue().getAuditRemark(), "重新提交必须清空旧审核备注");
    }

    @Test
    void apply_byAdmin_throwsBadRequest() {
        when(userMapper.selectById(1L)).thenReturn(user(1L, UserRoleEnum.ADMIN.getValue()));

        ApplyShopRequest req = new ApplyShopRequest();
        req.setShopName("Admin Shop");

        BusinessException ex = assertThrows(BusinessException.class, () -> service.apply(1L, req));
        assertEquals(400, ex.getCode());
        verify(shopMapper, never()).insert(any(Shop.class));
    }

    // ---------- 我的店铺 ----------

    @Test
    void getMine_noShop_returnsNull() {
        when(shopMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        assertNull(service.getMine(1L));
    }

    @Test
    void getMine_hasShop_returnsVO() {
        when(shopMapper.selectOne(any(Wrapper.class))).thenReturn(shop(10L, 1L, 1, null));
        ShopVO vo = service.getMine(1L);
        assertNotNull(vo);
        assertEquals(10L, vo.getId());
        assertEquals("已开通", vo.getStatusName());
    }

    // ---------- 管理端审核 ----------

    @Test
    void adminAudit_approve_upgradesUserToMerchant() {
        when(shopMapper.selectById(10L)).thenReturn(shop(10L, 1L, 0, null));
        when(userMapper.selectById(1L)).thenReturn(user(1L, UserRoleEnum.USER.getValue()));

        AuditShopRequest req = new AuditShopRequest();
        req.setStatus(1);
        req.setAuditRemark("ok");
        service.adminAudit(10L, req);

        ArgumentCaptor<Shop> shopCaptor = ArgumentCaptor.forClass(Shop.class);
        verify(shopMapper).updateById(shopCaptor.capture());
        assertEquals(1, shopCaptor.getValue().getStatus());
        assertEquals("ok", shopCaptor.getValue().getAuditRemark());

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).updateById(userCaptor.capture());
        assertEquals(UserRoleEnum.MERCHANT.getValue(), userCaptor.getValue().getRole());
    }

    @Test
    void adminAudit_reject_setsStatusAndRemark() {
        when(shopMapper.selectById(10L)).thenReturn(shop(10L, 1L, 0, null));

        AuditShopRequest req = new AuditShopRequest();
        req.setStatus(2);
        req.setAuditRemark("no match");
        service.adminAudit(10L, req);

        ArgumentCaptor<Shop> shopCaptor = ArgumentCaptor.forClass(Shop.class);
        verify(shopMapper).updateById(shopCaptor.capture());
        assertEquals(2, shopCaptor.getValue().getStatus());
        assertEquals("no match", shopCaptor.getValue().getAuditRemark());
        // 驳回不改变用户角色
        verify(userMapper, never()).updateById(any(User.class));
    }

    @Test
    void adminAudit_alreadyHandled_throwsConflict() {
        when(shopMapper.selectById(10L)).thenReturn(shop(10L, 1L, 1, null));

        AuditShopRequest req = new AuditShopRequest();
        req.setStatus(2);
        BusinessException ex = assertThrows(BusinessException.class, () -> service.adminAudit(10L, req));
        assertEquals(409, ex.getCode());
    }

    @Test
    void adminAudit_invalidStatus_throwsBadRequest() {
        AuditShopRequest req = new AuditShopRequest();
        req.setStatus(9);
        BusinessException ex = assertThrows(BusinessException.class, () -> service.adminAudit(10L, req));
        assertEquals(400, ex.getCode());
    }

    // ---------- 管理端关闭店铺（REQ-20260913-店铺关闭能力 §10 第 1、2 条）----------

    @Test
    void closeShop_openShop_setsClosedStatusAndRemark() {
        when(shopMapper.selectById(10L)).thenReturn(shop(10L, 1L, 1, "审核时的备注"));

        CloseShopRequest req = new CloseShopRequest();
        req.setAuditRemark("违规经营");
        service.closeShop(10L, req);

        ArgumentCaptor<Shop> captor = ArgumentCaptor.forClass(Shop.class);
        verify(shopMapper).updateById(captor.capture());
        assertEquals(ShopStatusEnum.CLOSED.getValue(), captor.getValue().getStatus());
        assertEquals("违规经营", captor.getValue().getAuditRemark());
    }

    /**
     * Q3 拍板：关闭**不降** role。
     *
     * <p>这条断言的价值在于它**能失败** —— 若有人"顺手"在 closeShop 里补一句
     * {@code user.setRole(USER)}，它会立刻转红。（同 `adminAudit_reject_setsStatusAndRemark`
     * 里那条 `never().updateById` 的写法。）</p>
     */
    @Test
    void closeShop_doesNotTouchUserRole() {
        when(shopMapper.selectById(10L)).thenReturn(shop(10L, 1L, 1, null));

        service.closeShop(10L, new CloseShopRequest());

        verify(userMapper, never()).updateById(any(User.class));
        verify(userMapper, never()).selectById(any());
    }

    /** 请求体整个省略（`@RequestBody(required=false)`）也要能关，且理由置空而非留旧值。 */
    @Test
    void closeShop_nullBody_stillClosesAndClearsRemark() {
        when(shopMapper.selectById(10L)).thenReturn(shop(10L, 1L, 1, "审核时的备注"));

        service.closeShop(10L, null);

        ArgumentCaptor<Shop> captor = ArgumentCaptor.forClass(Shop.class);
        verify(shopMapper).updateById(captor.capture());
        assertEquals(ShopStatusEnum.CLOSED.getValue(), captor.getValue().getStatus());
        assertNull(captor.getValue().getAuditRemark(), "缺省理由必须清空，不能把上一条审核备注留成「关闭理由」");
    }

    @Test
    void closeShop_notFound_throws404() {
        when(shopMapper.selectById(10L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.closeShop(10L, new CloseShopRequest()));
        assertEquals(404, ex.getCode());
        verify(shopMapper, never()).updateById(any(Shop.class));
    }

    /** Q4 拍板：重复关闭 = 409，不是幂等 200。 */
    @Test
    void closeShop_alreadyClosed_throws409AndSaysClosed() {
        when(shopMapper.selectById(10L)).thenReturn(shop(10L, 1L, 3, "上次关闭理由"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.closeShop(10L, new CloseShopRequest()));
        assertEquals(409, ex.getCode());
        assertEquals("该店铺已关闭", ex.getMessage());
        verify(shopMapper, never()).updateById(any(Shop.class));
    }

    /** §9 E3：关闭一个还在待审核的店铺 → 409，那是「驳回」的语义，该走审核端点。 */
    @Test
    void closeShop_pendingShop_throws409() {
        when(shopMapper.selectById(10L)).thenReturn(shop(10L, 1L, 0, null));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.closeShop(10L, new CloseShopRequest()));
        assertEquals(409, ex.getCode());
        assertEquals("只有已开通的店铺才能关闭", ex.getMessage());
    }

    @Test
    void closeShop_rejectedShop_throws409() {
        when(shopMapper.selectById(10L)).thenReturn(shop(10L, 1L, 2, "资料不全"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.closeShop(10L, new CloseShopRequest()));
        assertEquals(409, ex.getCode());
    }

    // ---------- 关闭后的重申请（§9 E8、§10 第 7 条）----------

    /**
     * Q2 拍板：关闭即终局 —— 被关闭的商家重新提交入驻申请必须被拒，**且不改动 `dsm_shop` 任何列**。
     *
     * <p>这条是"方案甲"的核心防线：若 `apply` 的放行条件被改回"看 2 或 3"，关闭就会被
     * 一次重新申请撤销（覆盖店名并重置为待审核）—— 那正是本需求要根治的坑。</p>
     */
    @Test
    void apply_afterClosed_throwsConflictAndWritesNothing() {
        when(userMapper.selectById(1L)).thenReturn(user(1L, UserRoleEnum.MERCHANT.getValue()));
        when(shopMapper.selectOne(any(Wrapper.class))).thenReturn(shop(10L, 1L, 3, "违规经营"));

        ApplyShopRequest req = new ApplyShopRequest();
        req.setShopName("换个名字再来");

        BusinessException ex = assertThrows(BusinessException.class, () -> service.apply(1L, req));
        assertEquals(409, ex.getCode());
        assertEquals("店铺已被关闭，不能重新申请入驻", ex.getMessage());
        verify(shopMapper, never()).insert(any(Shop.class));
        verify(shopMapper, never()).updateById(any(Shop.class));
    }

    // ---------- 状态名（§4.2 方案甲的核心：2 与 3 必须分开）----------

    @Test
    void statusName_separatesRejectedFromClosed() {
        assertEquals("已驳回", ShopVO.statusName(2), "2 只是「申请被驳回」，不该再带「关闭」字样");
        assertEquals("已关闭", ShopVO.statusName(3));
        assertEquals("待审核", ShopVO.statusName(0));
        assertEquals("已开通", ShopVO.statusName(1));
        assertEquals("未知", ShopVO.statusName(null));
        assertEquals("未知", ShopVO.statusName(99));
    }
}
