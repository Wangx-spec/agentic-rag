#!/usr/bin/env python3
"""
S3.1 数据清洗与抽样脚本（T1 步骤 2/3 的执行工具）

职责：
1. 天池 9 张业务表（排除 auth_user）—— 剥 UTF-8 BOM 头 → 复制到 data/demo-data/，
   并按表名映射重命名 CSV 文件（原始单数名 → demo 复数表名）。
2. 评论语料 online_shopping_10_cats.csv —— 按「品类 cat × 情感 label」双维度分层抽样
   （每品类目标 300 条 = 正负各 150，不足 150 的标签全取），输出 data/demo-data/comments.csv。

用法（从仓库根运行）：
    python3 scripts/prepare-demo-data.py

前置（原始数据已就位）：
    data/shangchuan_data/*.csv        天池「电商运营分析数据」（CC0，10 张表）
    data/online_shopping_10_cats.csv  ChineseNlpCorpus 评论（62,774 条，License 未声明）

说明：
- 字段名不做重命名（天池字段已是规范的小写下划线），字段口径映射见
  scripts/demo-data/field-mapping.md（T1 步骤 5 产出）。
- 本脚本只用 Python 标准库（csv/random/collections/pathlib），无第三方依赖。
"""
from __future__ import annotations

import csv
import random
from collections import defaultdict
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
SRC_TIANCHI = REPO_ROOT / "data" / "shangchuan_data"
SRC_COMMENT = REPO_ROOT / "data" / "online_shopping_10_cats.csv"
DST_DIR = REPO_ROOT / "data" / "demo-data"

# 天池表名映射：原始 CSV 文件名（不含 .csv）→ demo 表名
# auth_user（Django 管理员表，含密码哈希）刻意排除，不纳入 demo 库
TIANCHI_TABLES = {
    "platform": "platforms",
    "order_status": "order_statuses",
    "payment_method": "payment_methods",
    "shipping_method": "shipping_methods",
    "user": "users",
    "product": "products",
    "user_behavior": "user_actions",
    "order": "orders",
    "order_item": "order_items",
}

# 评论抽样参数
COMMENT_PER_CAT = 300    # 每品类目标条数
COMMENT_PER_LABEL = 150  # 每品类内每标签目标条数（正/负各 150）
SEED = 42                # 固定随机种子，保证抽样可复现

BOM = b"\xef\xbb\xbf"


def strip_bom_copy(src: Path, dst: Path) -> int:
    """字节级剥 BOM 复制，其余内容原样保留（不改动字段/引号/换行）。返回数据行数（近似）。"""
    data = src.read_bytes()
    if data.startswith(BOM):
        data = data[3:]
    dst.write_bytes(data)
    # 数据行数 = 总行数 - 1（表头）；天池字段内无换行，按 \n 计数即可
    total_lines = data.count(b"\n") + (0 if data.endswith(b"\n") else 1)
    return max(0, total_lines - 1)


def sample_comments(src: Path, dst: Path) -> tuple[int, dict[str, tuple[int, int]]]:
    """按品类×情感分层抽样评论，写 comments.csv。返回 (总抽样数, {品类: (负, 正)})。"""
    groups: dict[str, dict[str, list[list[str]]]] = defaultdict(lambda: defaultdict(list))
    with src.open("r", encoding="utf-8-sig", newline="") as fh:
        reader = csv.reader(fh)
        header = next(reader)
        for row in reader:
            cat, label = row[0].strip(), row[1].strip()
            groups[cat][label].append(row)

    rng = random.Random(SEED)
    sampled: list[list[str]] = []
    stats: dict[str, tuple[int, int]] = {}
    for cat in sorted(groups):
        neg = groups[cat].get("0", [])
        pos = groups[cat].get("1", [])
        n_neg = min(COMMENT_PER_LABEL, len(neg))
        n_pos = min(COMMENT_PER_LABEL, len(pos))
        sampled.extend(rng.sample(neg, n_neg))
        sampled.extend(rng.sample(pos, n_pos))
        stats[cat] = (n_neg, n_pos)

    with dst.open("w", encoding="utf-8", newline="") as fh:
        writer = csv.writer(fh, lineterminator="\n")
        writer.writerow(header)
        writer.writerows(sampled)

    return len(sampled), stats


def main() -> None:
    DST_DIR.mkdir(parents=True, exist_ok=True)

    print("=== 1. 天池表：剥 BOM + 复制（表名重命名） ===")
    for src_name, dst_name in TIANCHI_TABLES.items():
        src = SRC_TIANCHI / f"{src_name}.csv"
        dst = DST_DIR / f"{dst_name}.csv"
        if not src.exists():
            print(f"  [跳过] 找不到 {src}")
            continue
        rows = strip_bom_copy(src, dst)
        print(f"  {src_name:<16} -> {dst_name:<18} {rows:>6} 行")

    print("\n=== 2. 评论抽样（品类 × 情感 分层） ===")
    total, stats = sample_comments(SRC_COMMENT, DST_DIR / "comments.csv")
    n_neg_sum = sum(n for n, _ in stats.values())
    n_pos_sum = sum(p for _, p in stats.values())
    print(f"  {'品类':<6} {'负(0)':>6} {'正(1)':>6} {'小计':>6}")
    for cat in sorted(stats):
        n_neg, n_pos = stats[cat]
        print(f"  {cat:<6} {n_neg:>6} {n_pos:>6} {n_neg + n_pos:>6}")
    print(f"  {'合计':<6} {n_neg_sum:>6} {n_pos_sum:>6} {total:>6}")

    print(f"\n完成。输出目录 {DST_DIR}/（{len(TIANCHI_TABLES) + 1} 个 CSV，auth_user 已排除）。")


if __name__ == "__main__":
    main()
