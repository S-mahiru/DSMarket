package com.dsmarket.modules.shop.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.dsmarket.common.domain.PageResult;
import com.dsmarket.common.enums.UserRoleEnum;
import com.dsmarket.common.exception.BusinessException;
import com.dsmarket.modules.shop.dto.ApplyShopRequest;
import com.dsmarket.modules.shop.dto.AuditShopRequest;
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
}
