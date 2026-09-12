-- ============================================
-- V10：商品归属店铺 —— dsm_product 加 shop_id（商家数据隔离的地基）
-- 前置：V1（dsm_product）、V4（dsm_shop）
-- 需求：REQ-20260912-角色权限模型与商家数据隔离 §4.3（P1-A）
--
-- 语义（已拍板 D2）：shop_id IS NULL = **平台自营**（ADMIN 在后台建的商品）；
--      非 NULL = 该商品属于对应店铺，只有该店铺的商家可改。
--      存量行**全部保持 NULL** —— 它们都是隔离上线前由 ADMIN 建的，正是"平台自营"。
--      本迁移因此**不写任何 UPDATE**（§4.3 异常行："不得修改任何存量数据"）。
--
-- 为何不用 NOT NULL + 一个"自营店铺"行：平台自营**没有**对应的 dsm_shop 行
--      —— dsm_shop.user_id 指向商家账号，平台侧不存在这个账号。硬造一行"自营店"
--      等于把平台伪装成一个商家；且 D3 已定 ADMIN 不共用商家入口。
--      用 NULL 表达"不属于任何店铺"更诚实，也让"自营商品不出现在商家侧"
--      这条验收（§10 第 2、9 条）退化成一次 `shop_id = ?` 就能保证的事。
--
-- 为何不加外键：本库既有约定是**不用 FK**（dsm_product_sku.product_id、dsm_shop.user_id、
--      dsm_order.user_id 都只建索引、不加约束）。隔离由服务层显式校验保证（D4）。
--
-- 幂等：可重复执行（ADD COLUMN IF NOT EXISTS + CREATE INDEX IF NOT EXISTS）
-- 破坏性：无。不删列、不改类型、不触碰存量行。
-- ============================================

ALTER TABLE dsm_product ADD COLUMN IF NOT EXISTS shop_id BIGINT;

COMMENT ON COLUMN dsm_product.shop_id IS
    '归属店铺ID：NULL=平台自营（ADMIN建）；非NULL=所属店铺商家自有';

CREATE INDEX IF NOT EXISTS idx_product_shop_id ON dsm_product (shop_id);
