-- ============================================
-- DSMarket 黑海商城 - 数据库初始化脚本 V1 (PostgreSQL)
-- ============================================
-- 前置条件：数据库 `dsmarket` 已创建（如：CREATE DATABASE dsmarket;）
-- 说明：updated_at 由应用层 MyMetaObjectHandler 自动填充，无需数据库触发器。
-- 类型映射：BIGINT AUTO_INCREMENT → BIGSERIAL；TINYINT → SMALLINT；
--          DATETIME → TIMESTAMP；JSON → JSONB。

-- 1. 用户表
CREATE TABLE dsm_user (
    id              BIGSERIAL,
    username        VARCHAR(50)     NOT NULL,
    password        VARCHAR(255)    NOT NULL,
    email           VARCHAR(100),
    phone           VARCHAR(20),
    avatar          VARCHAR(500),
    nickname        VARCHAR(50),
    gender          SMALLINT        DEFAULT 0,
    role            VARCHAR(20)     DEFAULT 'USER',
    status          SMALLINT        DEFAULT 1,
    last_login_time TIMESTAMP,
    login_ip        VARCHAR(50),
    created_at      TIMESTAMP       DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP       DEFAULT CURRENT_TIMESTAMP,
    deleted         SMALLINT        DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_username UNIQUE (username),
    CONSTRAINT uk_email UNIQUE (email)
);
COMMENT ON TABLE dsm_user IS '用户表';
COMMENT ON COLUMN dsm_user.id IS '用户ID';
COMMENT ON COLUMN dsm_user.username IS '用户名';
COMMENT ON COLUMN dsm_user.password IS '密码(BCrypt)';
COMMENT ON COLUMN dsm_user.email IS '邮箱';
COMMENT ON COLUMN dsm_user.phone IS '手机号';
COMMENT ON COLUMN dsm_user.avatar IS '头像URL';
COMMENT ON COLUMN dsm_user.nickname IS '昵称';
COMMENT ON COLUMN dsm_user.gender IS '性别:0未知 1男 2女';
COMMENT ON COLUMN dsm_user.role IS '角色:USER/ADMIN';
COMMENT ON COLUMN dsm_user.status IS '状态:0禁用 1启用';
COMMENT ON COLUMN dsm_user.last_login_time IS '最后登录时间';
COMMENT ON COLUMN dsm_user.login_ip IS '登录IP';
COMMENT ON COLUMN dsm_user.deleted IS '软删除:0正常 1删除';

-- 2. 商品分类表
CREATE TABLE dsm_category (
    id          BIGSERIAL,
    name        VARCHAR(100)    NOT NULL,
    parent_id   BIGINT          DEFAULT 0,
    level       SMALLINT        DEFAULT 1,
    sort_order  INT             DEFAULT 0,
    icon        VARCHAR(500),
    image       VARCHAR(500),
    status      SMALLINT        DEFAULT 1,
    created_at  TIMESTAMP       DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP       DEFAULT CURRENT_TIMESTAMP,
    deleted     SMALLINT        DEFAULT 0,
    PRIMARY KEY (id)
);
CREATE INDEX idx_parent_id ON dsm_category (parent_id);
COMMENT ON TABLE dsm_category IS '商品分类表';
COMMENT ON COLUMN dsm_category.id IS '分类ID';
COMMENT ON COLUMN dsm_category.name IS '分类名称';
COMMENT ON COLUMN dsm_category.parent_id IS '父分类ID(0=根节点)';
COMMENT ON COLUMN dsm_category.level IS '层级:1/2/3';
COMMENT ON COLUMN dsm_category.sort_order IS '排序(升序)';
COMMENT ON COLUMN dsm_category.icon IS '图标URL';
COMMENT ON COLUMN dsm_category.image IS '分类图片';
COMMENT ON COLUMN dsm_category.status IS '状态:0隐藏 1显示';

-- 3. 商品表
CREATE TABLE dsm_product (
    id              BIGSERIAL,
    name            VARCHAR(255)    NOT NULL,
    title           VARCHAR(500),
    brief           VARCHAR(500),
    description     TEXT,
    category_id     BIGINT          NOT NULL,
    brand           VARCHAR(100),
    unit            VARCHAR(20)     DEFAULT '件',
    price           DECIMAL(10,2)   NOT NULL,
    original_price  DECIMAL(10,2),
    stock           INT             DEFAULT 0,
    sales           INT             DEFAULT 0,
    main_image      VARCHAR(500),
    sub_images      JSONB,
    detail_images   JSONB,
    has_sku         SMALLINT        DEFAULT 0,
    is_featured     SMALLINT        DEFAULT 0,
    status          SMALLINT        DEFAULT 1,
    weight          DECIMAL(10,2)   DEFAULT 0,
    sort_order      INT             DEFAULT 0,
    created_at      TIMESTAMP       DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP       DEFAULT CURRENT_TIMESTAMP,
    deleted         SMALLINT        DEFAULT 0,
    PRIMARY KEY (id)
);
CREATE INDEX idx_category_id ON dsm_product (category_id);
CREATE INDEX idx_product_status ON dsm_product (status);
CREATE INDEX idx_is_featured ON dsm_product (is_featured);
-- 全文搜索索引（PostgreSQL GIN + tsvector，中文分词 zhparser_config，配置见 V5__zhparser_fulltext.sql）
-- 注意：需要 zhparser 扩展 + zhparser_config 分词配置已存在（V5 脚本负责创建，保持幂等）
CREATE INDEX ft_search ON dsm_product USING GIN (
    to_tsvector('zhparser_config',
        coalesce(name, '') || ' ' || coalesce(title, '') || ' ' || coalesce(brief, ''))
);
COMMENT ON TABLE dsm_product IS '商品表';
COMMENT ON COLUMN dsm_product.id IS '商品ID';
COMMENT ON COLUMN dsm_product.name IS '商品名称';
COMMENT ON COLUMN dsm_product.title IS '卖点标题';
COMMENT ON COLUMN dsm_product.brief IS '商品简介';
COMMENT ON COLUMN dsm_product.description IS '商品描述(富文本)';
COMMENT ON COLUMN dsm_product.category_id IS '分类ID';
COMMENT ON COLUMN dsm_product.brand IS '品牌';
COMMENT ON COLUMN dsm_product.unit IS '单位';
COMMENT ON COLUMN dsm_product.price IS '售价';
COMMENT ON COLUMN dsm_product.original_price IS '原价';
COMMENT ON COLUMN dsm_product.stock IS '总库存';
COMMENT ON COLUMN dsm_product.sales IS '销量';
COMMENT ON COLUMN dsm_product.main_image IS '主图URL';
COMMENT ON COLUMN dsm_product.sub_images IS '副图列表';
COMMENT ON COLUMN dsm_product.detail_images IS '详情图列表';
COMMENT ON COLUMN dsm_product.has_sku IS '是否有SKU:0否 1是';
COMMENT ON COLUMN dsm_product.is_featured IS '是否推荐:0否 1是';
COMMENT ON COLUMN dsm_product.status IS '状态:0下架 1上架';
COMMENT ON COLUMN dsm_product.weight IS '重量(kg)';
COMMENT ON COLUMN dsm_product.sort_order IS '排序';

-- 4. 商品SKU表
CREATE TABLE dsm_product_sku (
    id              BIGSERIAL,
    product_id      BIGINT          NOT NULL,
    sku_code        VARCHAR(100)    NOT NULL,
    price           DECIMAL(10,2)   NOT NULL,
    original_price  DECIMAL(10,2),
    stock           INT             DEFAULT 0,
    specs           JSONB,
    image           VARCHAR(500),
    weight          DECIMAL(10,2),
    sort_order      INT             DEFAULT 0,
    status          SMALLINT        DEFAULT 1,
    created_at      TIMESTAMP       DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP       DEFAULT CURRENT_TIMESTAMP,
    deleted         SMALLINT        DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_sku_code UNIQUE (sku_code)
);
CREATE INDEX idx_sku_product_id ON dsm_product_sku (product_id);
COMMENT ON TABLE dsm_product_sku IS '商品SKU表';
COMMENT ON COLUMN dsm_product_sku.id IS 'SKU ID';
COMMENT ON COLUMN dsm_product_sku.product_id IS '商品ID';
COMMENT ON COLUMN dsm_product_sku.sku_code IS 'SKU编码';
COMMENT ON COLUMN dsm_product_sku.price IS 'SKU售价';
COMMENT ON COLUMN dsm_product_sku.original_price IS 'SKU原价';
COMMENT ON COLUMN dsm_product_sku.stock IS 'SKU库存';
COMMENT ON COLUMN dsm_product_sku.specs IS '规格属性';
COMMENT ON COLUMN dsm_product_sku.image IS 'SKU图片';
COMMENT ON COLUMN dsm_product_sku.weight IS '重量(kg)';
COMMENT ON COLUMN dsm_product_sku.sort_order IS '排序';
COMMENT ON COLUMN dsm_product_sku.status IS '状态:0禁用 1启用';

-- 5. 购物车表（硬删除，无 deleted 字段）
CREATE TABLE dsm_cart (
    id          BIGSERIAL,
    user_id     BIGINT      NOT NULL,
    product_id  BIGINT      NOT NULL,
    sku_id      BIGINT,
    quantity    INT         DEFAULT 1,
    checked     SMALLINT    DEFAULT 1,
    created_at  TIMESTAMP   DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP   DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT uk_user_product_sku UNIQUE (user_id, product_id, sku_id)
);
CREATE INDEX idx_cart_user_id ON dsm_cart (user_id);
COMMENT ON TABLE dsm_cart IS '购物车表';
COMMENT ON COLUMN dsm_cart.id IS '购物车项ID';
COMMENT ON COLUMN dsm_cart.user_id IS '用户ID';
COMMENT ON COLUMN dsm_cart.product_id IS '商品ID';
COMMENT ON COLUMN dsm_cart.sku_id IS 'SKU ID(NULL=无SKU)';
COMMENT ON COLUMN dsm_cart.quantity IS '数量';
COMMENT ON COLUMN dsm_cart.checked IS '是否勾选:0否 1是';

-- 6. 收货地址表
CREATE TABLE dsm_address (
    id              BIGSERIAL,
    user_id         BIGINT          NOT NULL,
    receiver_name   VARCHAR(50)     NOT NULL,
    receiver_phone  VARCHAR(20)     NOT NULL,
    province        VARCHAR(50),
    city            VARCHAR(50),
    district        VARCHAR(50),
    detail_address  VARCHAR(500)    NOT NULL,
    zip_code        VARCHAR(10),
    label           VARCHAR(20),
    is_default      SMALLINT        DEFAULT 0,
    created_at      TIMESTAMP       DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP       DEFAULT CURRENT_TIMESTAMP,
    deleted         SMALLINT        DEFAULT 0,
    PRIMARY KEY (id)
);
CREATE INDEX idx_address_user_id ON dsm_address (user_id);
COMMENT ON TABLE dsm_address IS '收货地址表';
COMMENT ON COLUMN dsm_address.id IS '地址ID';
COMMENT ON COLUMN dsm_address.user_id IS '用户ID';
COMMENT ON COLUMN dsm_address.receiver_name IS '收件人';
COMMENT ON COLUMN dsm_address.receiver_phone IS '收件人电话';
COMMENT ON COLUMN dsm_address.province IS '省';
COMMENT ON COLUMN dsm_address.city IS '市';
COMMENT ON COLUMN dsm_address.district IS '区/县';
COMMENT ON COLUMN dsm_address.detail_address IS '详细地址';
COMMENT ON COLUMN dsm_address.zip_code IS '邮编';
COMMENT ON COLUMN dsm_address.label IS '标签:家/公司/学校';
COMMENT ON COLUMN dsm_address.is_default IS '是否默认:0否 1是';

-- 7. 订单表
CREATE TABLE dsm_order_info (
    id              BIGSERIAL,
    order_no        VARCHAR(32)     NOT NULL,
    user_id         BIGINT          NOT NULL,
    total_amount    DECIMAL(10,2)   NOT NULL,
    shipping_fee    DECIMAL(10,2)   DEFAULT 0.00,
    discount_amount DECIMAL(10,2)   DEFAULT 0.00,
    actual_amount   DECIMAL(10,2)   NOT NULL,
    payment_amount  DECIMAL(10,2),
    status          SMALLINT        DEFAULT 0,
    remark          VARCHAR(500),
    address_snapshot JSONB,
    payment_method  VARCHAR(50),
    payment_time    TIMESTAMP,
    delivery_time   TIMESTAMP,
    receive_time    TIMESTAMP,
    cancel_time     TIMESTAMP,
    cancel_reason   VARCHAR(500),
    close_time      TIMESTAMP,
    created_at      TIMESTAMP       DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP       DEFAULT CURRENT_TIMESTAMP,
    deleted         SMALLINT        DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uk_order_no UNIQUE (order_no)
);
CREATE INDEX idx_order_user_id ON dsm_order_info (user_id);
CREATE INDEX idx_order_status ON dsm_order_info (status);
CREATE INDEX idx_created_at ON dsm_order_info (created_at);
COMMENT ON TABLE dsm_order_info IS '订单表';
COMMENT ON COLUMN dsm_order_info.id IS '订单ID';
COMMENT ON COLUMN dsm_order_info.order_no IS '订单号';
COMMENT ON COLUMN dsm_order_info.user_id IS '用户ID';
COMMENT ON COLUMN dsm_order_info.total_amount IS '商品总金额';
COMMENT ON COLUMN dsm_order_info.shipping_fee IS '运费';
COMMENT ON COLUMN dsm_order_info.discount_amount IS '优惠金额';
COMMENT ON COLUMN dsm_order_info.actual_amount IS '实付金额';
COMMENT ON COLUMN dsm_order_info.payment_amount IS '实际支付金额';
COMMENT ON COLUMN dsm_order_info.status IS '状态:0待付款 1已付款 2已发货 3已收货 4已完成 5已取消 6已关闭';
COMMENT ON COLUMN dsm_order_info.remark IS '订单备注';
COMMENT ON COLUMN dsm_order_info.address_snapshot IS '地址快照';
COMMENT ON COLUMN dsm_order_info.payment_method IS '支付方式';
COMMENT ON COLUMN dsm_order_info.payment_time IS '支付时间';
COMMENT ON COLUMN dsm_order_info.delivery_time IS '发货时间';
COMMENT ON COLUMN dsm_order_info.receive_time IS '收货时间';
COMMENT ON COLUMN dsm_order_info.cancel_time IS '取消时间';
COMMENT ON COLUMN dsm_order_info.cancel_reason IS '取消原因';
COMMENT ON COLUMN dsm_order_info.close_time IS '自动关闭时间';

-- 8. 订单项表
CREATE TABLE dsm_order_item (
    id              BIGSERIAL,
    order_id        BIGINT          NOT NULL,
    product_id      BIGINT          NOT NULL,
    sku_id          BIGINT,
    product_name    VARCHAR(255)    NOT NULL,
    product_image   VARCHAR(500),
    sku_specs       JSONB,
    unit_price      DECIMAL(10,2)   NOT NULL,
    quantity        INT             NOT NULL,
    subtotal        DECIMAL(10,2)   NOT NULL,
    created_at      TIMESTAMP       DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
);
CREATE INDEX idx_order_id ON dsm_order_item (order_id);
CREATE INDEX idx_order_item_product_id ON dsm_order_item (product_id);
COMMENT ON TABLE dsm_order_item IS '订单项表';
COMMENT ON COLUMN dsm_order_item.id IS '订单项ID';
COMMENT ON COLUMN dsm_order_item.order_id IS '订单ID';
COMMENT ON COLUMN dsm_order_item.product_id IS '商品ID';
COMMENT ON COLUMN dsm_order_item.sku_id IS 'SKU ID';
COMMENT ON COLUMN dsm_order_item.product_name IS '商品名(快照)';
COMMENT ON COLUMN dsm_order_item.product_image IS '商品图片(快照)';
COMMENT ON COLUMN dsm_order_item.sku_specs IS 'SKU规格(快照)';
COMMENT ON COLUMN dsm_order_item.unit_price IS '单价';
COMMENT ON COLUMN dsm_order_item.quantity IS '数量';
COMMENT ON COLUMN dsm_order_item.subtotal IS '小计';

-- 9. 支付记录表（仅追加，无更新/删除操作）
CREATE TABLE dsm_payment (
    id              BIGSERIAL,
    order_no        VARCHAR(32)     NOT NULL,
    user_id         BIGINT          NOT NULL,
    payment_no      VARCHAR(64),
    payment_method  VARCHAR(50),
    amount          DECIMAL(10,2)   NOT NULL,
    status          SMALLINT        DEFAULT 0,
    pay_time        TIMESTAMP,
    notify_data     TEXT,
    created_at      TIMESTAMP       DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT uk_payment_no UNIQUE (payment_no)
);
CREATE INDEX idx_order_no ON dsm_payment (order_no);
COMMENT ON TABLE dsm_payment IS '支付记录表';
COMMENT ON COLUMN dsm_payment.id IS '支付ID';
COMMENT ON COLUMN dsm_payment.order_no IS '订单号';
COMMENT ON COLUMN dsm_payment.user_id IS '用户ID';
COMMENT ON COLUMN dsm_payment.payment_no IS '交易流水号';
COMMENT ON COLUMN dsm_payment.payment_method IS '支付方式';
COMMENT ON COLUMN dsm_payment.amount IS '支付金额';
COMMENT ON COLUMN dsm_payment.status IS '状态:0待支付 1成功 2失败 3退款';
COMMENT ON COLUMN dsm_payment.pay_time IS '支付时间';
COMMENT ON COLUMN dsm_payment.notify_data IS '回调原始数据';
