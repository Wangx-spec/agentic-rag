-- S3.1 电商数据分析 demo 库建表脚本
-- 字段口径唯一依据：scripts/demo-data/field-mapping.md
-- 幂等策略：DROP TABLE IF EXISTS ... CASCADE 后重建（灌库脚本 load-demo-data.sh 整体幂等）

SET client_encoding = 'UTF8';

-- ============================================================
-- 0. 清理（幂等：先删后建，CASCADE 连带删除依赖的外键/索引）
-- ============================================================
DROP TABLE IF EXISTS order_items CASCADE;
DROP TABLE IF EXISTS orders CASCADE;
DROP TABLE IF EXISTS user_actions CASCADE;
DROP TABLE IF EXISTS comments CASCADE;
DROP TABLE IF EXISTS users CASCADE;
DROP TABLE IF EXISTS products CASCADE;
DROP TABLE IF EXISTS platforms CASCADE;
DROP TABLE IF EXISTS order_statuses CASCADE;
DROP TABLE IF EXISTS payment_methods CASCADE;
DROP TABLE IF EXISTS shipping_methods CASCADE;

-- ============================================================
-- 1. 字典表（独立参考维度，不与其他表建 FK）
-- ============================================================

CREATE TABLE platforms (
    code        varchar(20)  PRIMARY KEY,
    name        varchar(50),
    description varchar(200),
    created_at  timestamp,
    updated_at  timestamp
);
COMMENT ON TABLE  platforms              IS '平台字典表：电商平台主数据（淘宝/京东/拼多多/抖音/TikTok Shop）';
COMMENT ON COLUMN platforms.code         IS '平台编码（主键），如 taobao/jd/douyin';
COMMENT ON COLUMN platforms.name         IS '平台显示名称（中文）';
COMMENT ON COLUMN platforms.description  IS '平台说明文案';
COMMENT ON COLUMN platforms.created_at   IS '记录创建时间';
COMMENT ON COLUMN platforms.updated_at   IS '记录最后更新时间';

CREATE TABLE order_statuses (
    code        varchar(20)  PRIMARY KEY,
    name        varchar(50),
    badge_class varchar(50),
    description varchar(200),
    created_at  timestamp,
    updated_at  timestamp
);
COMMENT ON TABLE  order_statuses              IS '订单状态字典表：订单生命周期状态枚举（参考维度，不与 orders 建外键）';
COMMENT ON COLUMN order_statuses.code         IS '状态编码（主键）';
COMMENT ON COLUMN order_statuses.name         IS '状态名称（待付款/已付款/已完成等）';
COMMENT ON COLUMN order_statuses.badge_class  IS '前端徽章样式类名';
COMMENT ON COLUMN order_statuses.description  IS '状态说明';
COMMENT ON COLUMN order_statuses.created_at   IS '记录创建时间';
COMMENT ON COLUMN order_statuses.updated_at   IS '记录最后更新时间';

CREATE TABLE payment_methods (
    code        varchar(20)  PRIMARY KEY,
    name        varchar(50),
    description varchar(200),
    created_at  timestamp,
    updated_at  timestamp
);
COMMENT ON TABLE  payment_methods              IS '支付方式字典表：支付渠道枚举（参考维度，不与 orders 建外键）';
COMMENT ON COLUMN payment_methods.code         IS '支付方式编码（主键）';
COMMENT ON COLUMN payment_methods.name         IS '支付方式名称（微信支付/支付宝等）';
COMMENT ON COLUMN payment_methods.description  IS '说明';
COMMENT ON COLUMN payment_methods.created_at   IS '记录创建时间';
COMMENT ON COLUMN payment_methods.updated_at   IS '记录最后更新时间';

CREATE TABLE shipping_methods (
    code        varchar(20)  PRIMARY KEY,
    name        varchar(50),
    description varchar(200),
    created_at  timestamp,
    updated_at  timestamp
);
COMMENT ON TABLE  shipping_methods              IS '配送方式字典表：物流渠道枚举（参考维度，不与 orders 建外键）';
COMMENT ON COLUMN shipping_methods.code         IS '配送方式编码（主键）';
COMMENT ON COLUMN shipping_methods.name         IS '配送方式名称（快递配送等）';
COMMENT ON COLUMN shipping_methods.description  IS '说明';
COMMENT ON COLUMN shipping_methods.created_at   IS '记录创建时间';
COMMENT ON COLUMN shipping_methods.updated_at   IS '记录最后更新时间';

-- ============================================================
-- 2. 主数据表
-- ============================================================

CREATE TABLE users (
    global_user_id    varchar(50) PRIMARY KEY,
    platform_user_id  varchar(50),
    platform          varchar(20),
    user_name         varchar(50),
    gender            varchar(1),
    age               integer,
    city              varchar(50),
    registration_date date,
    phone             varchar(20),
    email             varchar(100),
    user_level        varchar(20)
);
COMMENT ON TABLE  users                    IS '用户主数据表：跨平台统一用户视图';
COMMENT ON COLUMN users.global_user_id     IS '全局用户唯一标识（跨平台统一 ID，主键）';
COMMENT ON COLUMN users.platform_user_id   IS '用户在具体平台上的原始 ID';
COMMENT ON COLUMN users.platform           IS '所属平台编码（参考 platforms.code，未建外键）';
COMMENT ON COLUMN users.user_name          IS '用户昵称/姓名';
COMMENT ON COLUMN users.gender             IS '性别：M 男 / F 女';
COMMENT ON COLUMN users.age                IS '年龄';
COMMENT ON COLUMN users.city               IS '所在城市';
COMMENT ON COLUMN users.registration_date  IS '注册日期';
COMMENT ON COLUMN users.phone              IS '手机号';
COMMENT ON COLUMN users.email              IS '邮箱';
COMMENT ON COLUMN users.user_level         IS '用户等级/会员等级';

CREATE INDEX idx_users_platform ON users (platform);
CREATE INDEX idx_users_phone    ON users (phone);
CREATE INDEX idx_users_email    ON users (email);

CREATE TABLE products (
    global_product_id   varchar(50) PRIMARY KEY,
    platform_product_id varchar(50),
    platform            varchar(20),
    product_name        varchar(200),
    category            varchar(50),
    subcategory         varchar(50),
    price               numeric(10,2),
    brand               varchar(100),
    stock_status        varchar(20),
    tags                text
);
COMMENT ON TABLE  products                     IS '商品主数据表：跨平台商品目录';
COMMENT ON COLUMN products.global_product_id   IS '全局商品唯一标识（主键）';
COMMENT ON COLUMN products.platform_product_id IS '平台上的商品 SKU/ID';
COMMENT ON COLUMN products.platform            IS '所属平台编码（参考 platforms.code，未建外键）';
COMMENT ON COLUMN products.product_name        IS '商品名称';
COMMENT ON COLUMN products.category            IS '一级品类';
COMMENT ON COLUMN products.subcategory         IS '子品类';
COMMENT ON COLUMN products.price               IS '标价/参考单价（元）';
COMMENT ON COLUMN products.brand               IS '品牌';
COMMENT ON COLUMN products.stock_status        IS '库存状态描述';
COMMENT ON COLUMN products.tags                IS '标签串，多值以逗号分隔';

CREATE INDEX idx_products_platform ON products (platform);
CREATE INDEX idx_products_name     ON products (product_name);
CREATE INDEX idx_products_category ON products (category);

-- ============================================================
-- 3. 业务明细表（建 FK）
-- ============================================================

CREATE TABLE orders (
    order_id         varchar(100) PRIMARY KEY,
    global_user_id   varchar(50) REFERENCES users(global_user_id),
    platform         varchar(20),
    order_time       timestamp,
    payment_time     timestamp,
    payment_method   varchar(50),
    shipping_address text,
    order_status     varchar(50),
    total_amount     numeric(12,2),
    discount_amount  numeric(10,2),
    shipping_fee     numeric(8,2),
    tax_amount       numeric(8,2),
    promotion_id     varchar(100),
    coupon_code      varchar(50),
    shipping_method  varchar(50)
);
COMMENT ON TABLE  orders                  IS '订单主表：一笔订单一行，金额字段单位为元';
COMMENT ON COLUMN orders.order_id         IS '订单号（主键）';
COMMENT ON COLUMN orders.global_user_id   IS '下单用户 ID（外键 → users.global_user_id）';
COMMENT ON COLUMN orders.platform         IS '下单平台编码（参考 platforms.code，未建外键）';
COMMENT ON COLUMN orders.order_time       IS '下单时间';
COMMENT ON COLUMN orders.payment_time     IS '支付完成时间（未支付为空）';
COMMENT ON COLUMN orders.payment_method   IS '支付方式中文名（如「微信支付」，存中文名不建外键，方案 A）';
COMMENT ON COLUMN orders.shipping_address IS '收货地址全文';
COMMENT ON COLUMN orders.order_status     IS '订单状态中文名（如「已付款」，存中文名不建外键，方案 A）';
COMMENT ON COLUMN orders.total_amount     IS '订单应付总金额（元）';
COMMENT ON COLUMN orders.discount_amount  IS '优惠/折扣金额（元）';
COMMENT ON COLUMN orders.shipping_fee     IS '运费（元）';
COMMENT ON COLUMN orders.tax_amount       IS '税费（元）';
COMMENT ON COLUMN orders.promotion_id     IS '活动/促销 ID';
COMMENT ON COLUMN orders.coupon_code      IS '优惠券码';
COMMENT ON COLUMN orders.shipping_method  IS '配送方式中文名（如「快递配送」，存中文名不建外键，方案 A）';

CREATE INDEX idx_orders_user      ON orders (global_user_id);
CREATE INDEX idx_orders_platform  ON orders (platform);
CREATE INDEX idx_orders_time      ON orders (order_time);
CREATE INDEX idx_orders_status    ON orders (order_status);

CREATE TABLE order_items (
    order_item_id     integer PRIMARY KEY,
    order_id          varchar(100) REFERENCES orders(order_id),
    global_product_id varchar(50) REFERENCES products(global_product_id),
    quantity          integer,
    unit_price        numeric(10,2),
    item_total        numeric(12,2),
    sku_info          text
);
COMMENT ON TABLE  order_items                   IS '订单明细行表：订单与商品的多对多展开';
COMMENT ON COLUMN order_items.order_item_id     IS '订单行自增主键';
COMMENT ON COLUMN order_items.order_id          IS '所属订单号（外键 → orders.order_id）';
COMMENT ON COLUMN order_items.global_product_id IS '商品 ID（外键 → products.global_product_id）';
COMMENT ON COLUMN order_items.quantity          IS '购买数量';
COMMENT ON COLUMN order_items.unit_price        IS '成交单价（元）';
COMMENT ON COLUMN order_items.item_total        IS '该行小计金额（元），一般=数量×单价';
COMMENT ON COLUMN order_items.sku_info          IS 'SKU 规格等补充信息（文本）';

CREATE INDEX idx_order_items_order   ON order_items (order_id);
CREATE INDEX idx_order_items_product ON order_items (global_product_id);

CREATE TABLE user_actions (
    behavior_id      integer PRIMARY KEY,
    global_user_id   varchar(50) REFERENCES users(global_user_id),
    global_product_id varchar(50) REFERENCES products(global_product_id),
    platform         varchar(20),
    session_id       varchar(100),
    behavior_type    varchar(50),
    behavior_time    timestamp,
    duration_seconds integer,
    page_url         varchar(500),
    referrer         varchar(500),
    device_type      varchar(50),
    app_version      varchar(50),
    latitude         numeric(9,6),
    longitude        numeric(9,6),
    extra_data       text
);
COMMENT ON TABLE  user_actions                   IS '用户行为明细表：浏览/加购/下单等事件流';
COMMENT ON COLUMN user_actions.behavior_id       IS '行为记录自增主键';
COMMENT ON COLUMN user_actions.global_user_id    IS '用户 ID（外键 → users.global_user_id）';
COMMENT ON COLUMN user_actions.global_product_id IS '商品 ID（外键 → products.global_product_id，可为空）';
COMMENT ON COLUMN user_actions.platform          IS '行为发生平台（参考 platforms.code，未建外键）';
COMMENT ON COLUMN user_actions.session_id        IS '会话 ID，串联同会话行为';
COMMENT ON COLUMN user_actions.behavior_type     IS '行为类型（浏览/加购/下单等）';
COMMENT ON COLUMN user_actions.behavior_time     IS '行为发生时间';
COMMENT ON COLUMN user_actions.duration_seconds  IS '停留/持续时长（秒）';
COMMENT ON COLUMN user_actions.page_url          IS '页面 URL';
COMMENT ON COLUMN user_actions.referrer          IS '来源页/Referrer';
COMMENT ON COLUMN user_actions.device_type       IS '设备类型（移动端/PC 等）';
COMMENT ON COLUMN user_actions.app_version       IS '客户端版本号';
COMMENT ON COLUMN user_actions.latitude          IS '纬度（若采集）';
COMMENT ON COLUMN user_actions.longitude         IS '经度（若采集）';
COMMENT ON COLUMN user_actions.extra_data        IS '扩展 JSON/文本字段';

CREATE INDEX idx_user_actions_user     ON user_actions (global_user_id);
CREATE INDEX idx_user_actions_product  ON user_actions (global_product_id);
CREATE INDEX idx_user_actions_platform ON user_actions (platform);
CREATE INDEX idx_user_actions_type     ON user_actions (behavior_type);
CREATE INDEX idx_user_actions_time     ON user_actions (behavior_time);

-- ============================================================
-- 4. 独立评论表（非天池数据，不建外键，S3.2 走 RAG 语义关联）
-- ============================================================

CREATE TABLE comments (
    id     serial PRIMARY KEY,
    cat    varchar(50),
    label  varchar(1),
    review text
);
COMMENT ON TABLE  comments        IS '商品评论表（独立语料，与天池主数据无 id 交集，不建外键；S3.2 靠 RAG 语义关联）';
COMMENT ON COLUMN comments.id     IS '自增主键';
COMMENT ON COLUMN comments.cat    IS '评论品类（手机/平板/水果/洗发水/衣服/酒店等 10 类）';
COMMENT ON COLUMN comments.label  IS '情感标签：0 负向 / 1 正向';
COMMENT ON COLUMN comments.review IS '评论文本';

CREATE INDEX idx_comments_cat   ON comments (cat);
CREATE INDEX idx_comments_label ON comments (label);