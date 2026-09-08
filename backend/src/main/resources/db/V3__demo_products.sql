-- ============================================
-- DSMarket 黑海商城 - 演示商品种子 V3 (PostgreSQL)
-- ============================================
-- 说明：V1 建表、V2 分类之后，用本脚本播种演示商品 + SKU，
--       保证全新初始化的生产库也有可浏览/可下单的商品。
-- 图片：main_image 指向 /uploads/*.jpg，部署时需将同名文件放入 uploads 目录。

-- 商品
INSERT INTO dsm_product
(id, name, title, brief, description, category_id, brand, unit, price, original_price, stock, sales,
 main_image, has_sku, is_featured, status, weight, sort_order) VALUES
(1, 'iPhone 16', 'A18芯片 超强性能', '最新款苹果手机，性能怪兽',
 '<p>全新 iPhone 16，搭载 A18 芯片，性能与能效大幅提升。</p><p>4800 万像素主摄，ProMotion 120Hz 自适应刷新率。</p>',
 100, 'Apple', '台', 5999.00, 6499.00, 100, 1203, '/uploads/iphone16.jpg', 1, 1, 1, 0.17, 1),
(2, '华为 Mate 70', '鸿蒙系统 卫星通信', '华为旗舰，鸿蒙 NEXT',
 '<p>华为 Mate 70 系列，搭载鸿蒙 NEXT，全场景智慧互联。</p><p>卫星通信，无地面网络也能保持联系。</p>',
 101, 'HUAWEI', '台', 5499.00, 5999.00, 80, 950, '/uploads/mate70.jpg', 1, 1, 1, 0.19, 2),
(3, '机械键盘 87键', '热插拔 茶轴', '入门机械键盘',
 '<p>87 键精简配列，热插拔轴体，茶轴手感均衡。</p>',
 2, 'Darmoshark', '把', 399.00, 499.00, 500, 3000, '/uploads/kb87.jpg', 0, 0, 1, 0.90, 3),
(4, '小米 15', '骁龙至尊版', '小米最新旗舰',
 '<p>小米 15，骁龙至尊版平台，徕卡光学三摄。</p>',
 102, 'Xiaomi', '台', 4599.00, NULL, 60, 0, '/uploads/mi15.jpg', 1, 0, 1, 0.18, 4);

-- 商品 SKU（iPhone 双规格、Mate 双规格、小米单规格）
INSERT INTO dsm_product_sku
(id, product_id, sku_code, price, original_price, stock, specs, weight, sort_order, status) VALUES
(1, 1, 'IP16-BLK-128', 5999.00, 6499.00, 37, '[{"key":"颜色","value":"黑色"},{"key":"存储","value":"128GB"}]', 0.17, 1, 1),
(2, 1, 'IP16-WHT-256', 6499.00, 6999.00, 30, '[{"key":"颜色","value":"白色"},{"key":"存储","value":"256GB"}]', 0.17, 2, 1),
(3, 2, 'M70-BLK-256', 5499.00, 5999.00, 50, '[{"key":"颜色","value":"黑色"},{"key":"存储","value":"256GB"}]', 0.19, 1, 1),
(4, 2, 'M70-BLU-512', 6299.00, 6999.00, 30, '[{"key":"颜色","value":"蓝色"},{"key":"存储","value":"512GB"}]', 0.19, 2, 1),
(5, 4, 'MI15-BLK-256', 4599.00, NULL, 60, '[{"key":"颜色","value":"黑色"},{"key":"存储","value":"256GB"}]', 0.18, 1, 1);

-- 重置自增序列，避免后续新增主键冲突
SELECT setval(pg_get_serial_sequence('dsm_product', 'id'), (SELECT MAX(id) FROM dsm_product));
SELECT setval(pg_get_serial_sequence('dsm_product_sku', 'id'), (SELECT MAX(id) FROM dsm_product_sku));
