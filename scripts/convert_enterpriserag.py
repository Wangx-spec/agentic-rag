#!/usr/bin/env python3
"""EnterpriseRAG-Bench confluence 语料转换器（S1）

将 EnterpriseRAG-Bench 的 confluence JSON 文档转换为 agentic-rag 可入库的 markdown：
- 文件名 = dsid.md（官方判分按 expected_doc_ids 对齐，必须保留）
- title -> # 标题；content 中无标点的短行升级为 ## 节标题（StructureChunker 可识别）
- 产出 manifest（dsid -> title 映射，调试与报告用）

用法:
    python3 scripts/convert_enterpriserag.py \
        --bench /path/to/EnterpriseRAG-Bench-main \
        --source confluence \
        --out ./data/enterpriserag-md
"""
import argparse
import json
import sys
from pathlib import Path


def build_reverse_index(bench_root: Path) -> dict[str, str]:
    """uuid_index.json: dsid -> 相对路径. 反转为 path -> dsid."""
    idx = json.loads((bench_root / "generated_data" / "uuid_index.json").read_text())
    return {rel: dsid for dsid, rel in idx.items()}


def is_heading_like(line: str, next_line: str) -> bool:
    """启发式判定 content 中的节标题行：短、无结尾标点/冒号、独立成段."""
    stripped = line.strip()
    if not stripped or len(stripped) > 60:
        return False
    if stripped.endswith((":", ":", ".", ",", ";", "!", "?", "。", "，", "；")):
        return False
    if stripped.startswith(("-", "*", "#", ">")):
        return False
    return next_line.strip() == ""


def to_markdown(doc: dict) -> str:
    parts = [f"# {doc.get('title', 'Untitled')}"]
    meta_bits = []
    if doc.get("space"):
        meta_bits.append(f"Space: {doc['space']}")
    if doc.get("labels"):
        meta_bits.append("Labels: " + ", ".join(doc["labels"]))
    if meta_bits:
        parts.append("")
        parts.append("> " + " | ".join(meta_bits))
    if doc.get("summary"):
        parts.append("")
        parts.append(f"**Summary:** {doc['summary']}")
    content = doc.get("content", "")
    if content:
        parts.append("")
        lines = content.splitlines()
        for i, line in enumerate(lines):
            nxt = lines[i + 1] if i + 1 < len(lines) else ""
            if i > 0 and lines[i - 1].strip() == "" and is_heading_like(line, nxt):
                parts.append(f"## {line.strip()}")
            else:
                parts.append(line)
    return "\n".join(parts).strip() + "\n"


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--bench", required=True, help="EnterpriseRAG-Bench-main 根目录")
    ap.add_argument("--source", default="confluence", help="源类型（默认 confluence）")
    ap.add_argument("--out", default="./data/enterpraserag-md", help="输出目录")
    args = ap.parse_args()

    bench_root = Path(args.bench)
    src_dir = bench_root / "generated_data" / "sources" / args.source
    out_dir = Path(args.out)
    if not src_dir.is_dir():
        print(f"[ERR] 源目录不存在: {src_dir}", file=sys.stderr)
        return 1
    out_dir.mkdir(parents=True, exist_ok=True)

    rev = build_reverse_index(bench_root)
    # 断点续跑：manifest 已存在则先加载，跳过已转换文件
    manifest_path = out_dir / "manifest.json"
    if manifest_path.exists():
        try:
            manifest = json.loads(manifest_path.read_text())
        except Exception:  # noqa: BLE001
            manifest = {}
    else:
        manifest = {}
    ok = skip_no_dsid = skip_existing = fail = 0
    for path in sorted(src_dir.rglob("*.json")):
        rel = str(path.relative_to(bench_root / "generated_data" / "sources"))
        dsid = rev.get(rel)
        if not dsid:
            skip_no_dsid += 1
            continue
        if (out_dir / f"{dsid}.md").exists():
            skip_existing += 1
            continue
        try:
            doc = json.loads(path.read_text())
            md = to_markdown(doc)
            (out_dir / f"{dsid}.md").write_text(md, encoding="utf-8")
            manifest[dsid] = doc.get("title", "")
            ok += 1
        except Exception as exc:  # noqa: BLE001
            fail += 1
            print(f"[WARN] {rel}: {exc}", file=sys.stderr)
        total_done = ok + skip_existing
        if total_done % 500 == 0 and total_done:
            print(f"  ... 累计 {total_done} 篇（本次新转 {ok}）")

    (out_dir / "manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=0), encoding="utf-8"
    )
    print(f"完成: 本次转换 {ok} 篇, 已存在跳过 {skip_existing}, 无 dsid 跳过 {skip_no_dsid}, 失败 {fail}")
    print(f"输出目录: {out_dir.resolve()} 共 {ok + skip_existing} 篇")
    return 0 if fail == 0 else 2


if __name__ == "__main__":
    raise SystemExit(main())
