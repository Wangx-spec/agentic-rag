# 天池字段 → demo 字段全量映射清单

> 用途：S3.1 demo 库建表（T2 `schema.sql`）与灌库（T3 `load-demo-data.sh`）的字段口径唯一依据。
> 生成依据：天池「电商运营分析数据」官方 `字段说明.txt` + 已清洗产物 `data/demo-data/*.csv` 真实表头。

## 0. 数据源与许可

| 数据 | 来源 | 许可 | 用途声明 |
|---|---|---|---|
| 天池「电商运营分析数据」 | `tianchi.aliyun.com/dataset/222889` | **Public Domain (CC0)** ✅ | 多平台业务**模拟数据**（官方定性：课程与研究用途，非真实生产数据）。订单/用户/商品/行为/字典表 |
| 电商评论 | `SophonPlus/ChineseNlpCorpus` 的 `online_shopping_10_cats` | ⚠️ **License 未声明** | 仅本地 demo、不商用、不公开分发。抽样 2950 条 |

## 1. 表名映射总览

> demo 统一用**复数命名**；字段名天池已是规范「小写下划线」，demo 字段名**与天池同名（1:1）**，无重命名。

| 天池表名 | demo 表名 | 说明 | 行数 |
|---|---|---|---|
| `platform` | `platforms` | 平台字典表 | 5 |
| `order_status` | `order_statuses` | 订单状态字典表 | 5 |
| `payment_method` | `payment_methods` | 支付方式字典表 | 5 |
| `shipping_method` | `shipping_methods` | 配送方式字典表 | 4 |
| `user` | `users` | 用户主数据表 | 5,486 |
| `product` | `products` | 商品主数据表 | 3,298 |
| `user_behavior` | `user_actions` | 用户行为明细表 | 21,998 |
| `order` | `orders` | 订单主表 | 10,996 |
| `order_item` | `order_items` | 订单明细行表 | 27,523 |
| `auth_user` | **不纳入（排除）** | Django 管理员表 | 7 |
| （评论语料） | `comments` | 独立评论表（非天池） | 2,950 |

## 2. 类型映射约定（MySQL → PostgreSQL）

| MySQL | PostgreSQL |
|---|---|
| `varchar(n)` | `varchar(n)` |
| `int` | `integer` |
| `decimal(p,s)` | `numeric(p,s)` |
| `datetime(6)` | `timestamp` |
| `date` | `date` |
| `longtext` | `text` |
| `tinyint(1)` | `boolean`（仅 auth_user，已排除） |

## 3. 各表字段映射

### 3.1 `platforms`（平台字典表）

| 天池字段 | demo 字段 | PG 类型 | 含义 | 约束 |
|---|---|---|---|---|
| `code` | `code` | varchar(20) | 平台编码（主键），如 taobao/jd/douyin | **PK** |
| `name` | `name` | varchar(50) | 平台显示名称（淘宝/京东/拼多多/抖音/TikTok Shop） | |
| `description` | `description` | varchar(200) | 平台说明文案 | |
| `created_at` | `created_at` | timestamp | 记录创建时间 | |
| `updated_at` | `updated_at` | timestamp | 记录最后更新时间 | |

### 3.2 `order_statuses`（订单状态字典表）

| 天池字段 | demo 字段 | PG 类型 | 含义 | 约束 |
|---|---|---|---|---|
| `code` | `code` | varchar(20) | 状态编码（主键） | **PK** |
| `name` | `name` | varchar(50) | 状态名称（待付款/已付款/已完成等） | |
| `badge_class` | `badge_class` | varchar(50) | 前端徽章样式类名 | |
| `description` | `description` | varchar(200) | 状态说明 | |
| `created_at` | `created_at` | timestamp | 记录创建时间 | |
| `updated_at` | `updated_at` | timestamp | 记录最后更新时间 | |

### 3.3 `payment_methods`（支付方式字典表）

| 天池字段 | demo 字段 | PG 类型 | 含义 | 约束 |
|---|---|---|---|---|
| `code` | `code` | varchar(20) | 支付方式编码（主键） | **PK** |
| `name` | `name` | varchar(50) | 支付方式名称（微信支付/支付宝等） | |
| `description` | `description` | varchar(200) | 说明 | |
| `created_at` | `created_at` | timestamp | 记录创建时间 | |
| `updated_at` | `updated_at` | timestamp | 记录最后更新时间 | |

### 3.4 `shipping_methods`（配送方式字典表）

| 天池字段 | demo 字段 | PG 类型 | 含义 | 约束 |
|---|---|---|---|---|
| `code` | `code` | varchar(20) | 配送方式编码（主键） | **PK** |
| `name` | `name` | varchar(50) | 配送方式名称（快递配送等） | |
| `description` | `description` | varchar(200) | 说明 | |
| `created_at` | `created_at` | timestamp | 记录创建时间 | |
| `updated_at` | `updated_at` | timestamp | 记录最后更新时间 | |

### 3.5 `users`（用户主数据表）

| 天池字段 | demo 字段 | PG 类型 | 含义 | 约束 |
|---|---|---|---|---|
| `global_user_id` | `global_user_id` | varchar(50) | 全局用户唯一标识（跨平台统一 ID） | **PK** |
| `platform_user_id` | `platform_user_id` | varchar(50) | 用户在具体平台上的原始 ID | |
| `platform` | `platform` | varchar(20) | 所属平台编码，关联 platforms.code | 索引 |
| `user_name` | `user_name` | varchar(50) | 用户昵称/姓名 | |
| `gender` | `gender` | varchar(1) | 性别：M 男 / F 女 | |
| `age` | `age` | integer | 年龄 | |
| `city` | `city` | varchar(50) | 所在城市 | |
| `registration_date` | `registration_date` | date | 注册日期 | |
| `phone` | `phone` | varchar(20) | 手机号 | 索引 |
| `email` | `email` | varchar(100) | 邮箱 | 索引 |
| `user_level` | `user_level` | varchar(20) | 用户等级/会员等级 | |

### 3.6 `products`（商品主数据表）

| 天池字段 | demo 字段 | PG 类型 | 含义 | 约束 |
|---|---|---|---|---|
| `global_product_id` | `global_product_id` | varchar(50) | 全局商品唯一标识 | **PK** |
| `platform_product_id` | `platform_product_id` | varchar(50) | 平台上的商品 SKU/ID | |
| `platform` | `platform` | varchar(20) | 所属平台编码 | 索引 |
| `product_name` | `product_name` | varchar(200) | 商品名称 | 索引 |
| `category` | `category` | varchar(50) | 一级品类 | 索引 |
| `subcategory` | `subcategory` | varchar(50) | 子品类 | |
| `price` | `price` | numeric(10,2) | 标价/参考单价（元） | |
| `brand` | `brand` | varchar(100) | 品牌 | |
| `stock_status` | `stock_status` | varchar(20) | 库存状态描述 | |
| `tags` | `tags` | text | 标签串，多值常以逗号分隔 | |

### 3.7 `user_actions`（用户行为明细表）

| 天池字段 | demo 字段 | PG 类型 | 含义 | 约束 |
|---|---|---|---|---|
| `behavior_id` | `behavior_id` | integer | 行为记录自增主键 | **PK** |
| `global_user_id` | `global_user_id` | varchar(50) | 用户 ID，关联 users.global_user_id | 索引、FK→users |
| `global_product_id` | `global_product_id` | varchar(50) | 商品 ID，关联 products.global_product_id | FK→products |
| `platform` | `platform` | varchar(20) | 行为发生平台 | 索引 |
| `session_id` | `session_id` | varchar(100) | 会话 ID，串联同会话行为 | |
| `behavior_type` | `behavior_type` | varchar(50) | 行为类型（浏览/加购/下单等） | 索引 |
| `behavior_time` | `behavior_time` | timestamp | 行为发生时间 | 索引 |
| `duration_seconds` | `duration_seconds` | integer | 停留/持续时长（秒） | |
| `page_url` | `page_url` | varchar(500) | 页面 URL | |
| `referrer` | `referrer` | varchar(500) | 来源页/Referrer | |
| `device_type` | `device_type` | varchar(50) | 设备类型（移动端/PC 等） | |
| `app_version` | `app_version` | varchar(50) | 客户端版本号 | |
| `latitude` | `latitude` | numeric(9,6) | 纬度（若采集） | |
| `longitude` | `longitude` | numeric(9,6) | 经度（若采集） | |
| `extra_data` | `extra_data` | text | 扩展 JSON/文本字段 | |

### 3.8 `orders`（订单主表）

| 天池字段 | demo 字段 | PG 类型 | 含义 | 约束 |
|---|---|---|---|---|
| `order_id` | `order_id` | varchar(100) | 订单号 | **PK** |
| `global_user_id` | `global_user_id` | varchar(50) | 下单用户 ID，关联 users.global_user_id | 索引、FK→users |
| `platform` | `platform` | varchar(20) | 下单平台编码 | 索引 |
| `order_time` | `order_time` | timestamp | 下单时间 | 索引 |
| `payment_time` | `payment_time` | timestamp | 支付完成时间 | |
| `payment_method` | `payment_method` | varchar(50) | 支付方式**中文名**（如「微信支付」） | **不建 FK**（方案 A） |
| `shipping_address` | `shipping_address` | text | 收货地址全文 | |
| `order_status` | `order_status` | varchar(50) | 订单状态**中文名**（如「已付款」） | 索引、**不建 FK**（方案 A） |
| `total_amount` | `total_amount` | numeric(12,2) | 订单应付总金额（元） | |
| `discount_amount` | `discount_amount` | numeric(10,2) | 优惠/折扣金额（元） | |
| `shipping_fee` | `shipping_fee` | numeric(8,2) | 运费（元） | |
| `tax_amount` | `tax_amount` | numeric(8,2) | 税费（元） | |
| `promotion_id` | `promotion_id` | varchar(100) | 活动/促销 ID | |
| `coupon_code` | `coupon_code` | varchar(50) | 优惠券码 | |
| `shipping_method` | `shipping_method` | varchar(50) | 配送方式**中文名**（如「快递配送」） | **不建 FK**（方案 A） |

### 3.9 `order_items`（订单明细行表）

| 天池字段 | demo 字段 | PG 类型 | 含义 | 约束 |
|---|---|---|---|---|
| `order_item_id` | `order_item_id` | integer | 订单行自增主键 | **PK** |
| `order_id` | `order_id` | varchar(100) | 所属订单号，关联 orders.order_id | 索引、FK→orders |
| `global_product_id` | `global_product_id` | varchar(50) | 商品 ID，关联 products.global_product_id | 索引、FK→products |
| `quantity` | `quantity` | integer | 购买数量 | |
| `unit_price` | `unit_price` | numeric(10,2) | 成交单价（元） | |
| `item_total` | `item_total` | numeric(12,2) | 该行小计金额（元），一般=数量×单价 | |
| `sku_info` | `sku_info` | text | SKU 规格等补充信息（文本） | |

### 3.10 `comments`（独立评论表，非天池）

| 来源字段 | demo 字段 | PG 类型 | 含义 | 约束 |
|---|---|---|---|---|
| （自增） | `id` | serial | 自增主键（CSV 无此列，灌库时由序列生成） | **PK** |
| `cat` | `cat` | varchar(50) | 评论品类（手机/平板/水果/洗发水/衣服/酒店等 10 类） | 索引 |
| `label` | `label` | varchar(1) | 情感标签：`0` 负向 / `1` 正向 | 索引 |
| `review` | `review` | text | 评论文本 | |

> 说明：评论语料只有 `cat / label / review` 三列，**没有 `product_id`/`user_id`**，与天池主数据无 id 交集，故**不建外键**，S3.2 靠 RAG 语义关联。demo 表额外加 `id serial` 自增主键（CSV 无此列），灌库须 `\copy comments (cat, label, review) FROM ...` 显式指定列，否则 3 列数据会错位映射到 `id/cat/label`。

## 4. 枚举字段口径（方案 A，已定）

订单表 `orders` 的三个枚举字段存**中文名**而非字典 code，与字典表**不建外键**：

| 字段 | 实际存储值（中文名） | 对应字典表 | 处理 |
|---|---|---|---|
| `payment_method` | 「微信支付」「支付宝」… | `payment_methods.code`（wechat/alipay…） | 存中文名，不 JOIN |
| `order_status` | 「已付款」「已完成」… | `order_statuses.code` | 存中文名，不 JOIN |
| `shipping_method` | 「快递配送」… | `shipping_methods.code` | 存中文名，不 JOIN |

理由：Agent `GROUP BY` 结果直接是「微信支付/已付款」可读值，字典表仅作「数据字典」供 `list_tables` 展示参考；可读性优先、外键解耦。

## 5. 排除表 `auth_user`

`auth_user`（7 行）是 **Django 管理员表**（含 `password` 密码哈希、`is_superuser`/`is_staff` 权限字段），属平台账号体系、与业务分析无关，**不纳入 demo 库**。此排除为**主动选择，非遗漏**。

## 6. 主外键关系（T2 建表依据）

```
users (PK global_user_id)  ◀───  orders (FK global_user_id)
products (PK global_product_id)  ◀───  order_items (FK global_product_id)
orders (PK order_id)  ◀───  order_items (FK order_id)
users  ◀───  user_actions (FK global_user_id)
products  ◀───  user_actions (FK global_product_id)

字典表（platforms/order_statuses/payment_methods/shipping_methods）：独立，不与其他表建 FK
comments：独立，不建 FK
```

## 7. 特殊处理记录（清洗脚本已执行）

| 处理 | 说明 |
|---|---|
| UTF-8 BOM 剥离 | 天池 10 表 + 评论 CSV 均带 BOM，已剥净（否则首列名变 `\ufeffcode`） |
| 表名复数化 | platform→platforms 等，字段名不变（1:1） |
| 评论双维度抽样 | 按 `cat`×`label` 分层，每品类正负各 150（热水器负向仅 100 条，自适应全取），固定种子 SEED=42 可复现 |
| `auth_user` 排除 | 见第 5 节 |
| 模拟数据瑕疵 | 商品名/品类/品牌随机拼接、地址拼接、时间不自洽——不影响 demo，演示的是 Agent 分析能力非数据真实性 |
