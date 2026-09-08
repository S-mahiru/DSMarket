-- ============================================
-- DSMarket 数据库初始化脚本 V4 (PostgreSQL)
-- 新增店铺表（Phase 2 商家端）
-- ============================================

-- 10. 店铺表
CREATE TABLE dsm_shop (
    id              BIGSERIAL,
    user_id         BIGINT          NOT NULL,
    shop_name       VARCHAR(100)    NOT NULL,
    logo            VARCHAR(500),
    description     VARCHAR(500),
    status          SMALLINT        DEFAULT 0,
    audit_remark    VARCHAR(500),
    created_at      TIMESTAMP       DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP       DEFAULT CURRENT_TIMESTAMP,
    deleted         SMALLINT        DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_shop_user_id UNIQUE (user_id)
);
COMMENT ON TABLE dsm_shop IS '店铺表';
COMMENT ON COLUMN dsm_shop.id IS '店铺ID';
COMMENT ON COLUMN dsm_shop.user_id IS '商家用户ID';
COMMENT ON COLUMN dsm_shop.shop_name IS '店铺名称';
COMMENT ON COLUMN dsm_shop.logo IS '店铺Logo URL';
COMMENT ON COLUMN dsm_shop.description IS '店铺介绍';
COMMENT ON COLUMN dsm_shop.status IS '状态:0待审核 1已开通 2已关闭/驳回';
COMMENT ON COLUMN dsm_shop.audit_remark IS '审核备注';
