-- ============================================
-- DSMarket 黑海商城 - 种子数据 V2 (PostgreSQL)
-- ============================================
-- 说明：用户（admin/testuser）由后端 DataInitializer 启动时播种（BCrypt 运行时加密），
--       此处仅保留演示数据（商品分类）。

-- 商品分类
INSERT INTO dsm_category (id, name, parent_id, level, sort_order, status) VALUES
(1, '手机数码', 0, 1, 1, 1),
(2, '电脑办公', 0, 1, 2, 1),
(3, '家用电器', 0, 1, 3, 1);

INSERT INTO dsm_category (id, name, parent_id, level, sort_order, status) VALUES
(10, '智能手机', 1, 2, 1, 1),
(11, '平板电脑', 1, 2, 2, 1);

INSERT INTO dsm_category (id, name, parent_id, level, sort_order, status) VALUES
(100, '苹果', 10, 3, 1, 1),
(101, '华为', 10, 3, 2, 1),
(102, '小米', 10, 3, 3, 1);

-- 显式插入 id 后同步自增序列，避免后续插入主键冲突
SELECT setval(pg_get_serial_sequence('dsm_category', 'id'), (SELECT MAX(id) FROM dsm_category));
