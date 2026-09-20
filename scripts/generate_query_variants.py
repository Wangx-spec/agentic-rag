#!/usr/bin/env python3
"""M6 Phase 5：泛化性评估 — Query 扰动集生成脚本

从 EnterpriseRAG-Bench 64 题 JSONL 派生三类变体（gold doc 不变）：
  1. 去术语化（口语改写）          →  questions-variants-dejargon.jsonl
  2. 短查询（关键词抽取）          →  questions-variants-keywords.jsonl
  3. 跨语言（中译）                →  questions-variants-zh.jsonl

每条变体保留 question_id / question_type / expected_doc_ids 不变，仅替换 question 字段。
通过 OpenAI 兼容 API 调用 LLM 批量生成，支持断点续跑（已生成题目跳过）。

用法:
    # 默认用 GLM-4-Flash（cheap LLM，控制成本）
    python3 scripts/generate_query_variants.py \
        --input ./data/benchmark/questions.jsonl \
        --out ./data/derived \
        --base-url https://api.siliconflow.cn/v1 \
        --api-key $LLM_API_KEY \
        --model Qwen/Qwen2.5-7B-Instruct

    # 只生成某一类变体（消融/调试用）
    python3 scripts/generate_query_variants.py --only dejargon ...
"""

import argparse
import json
import os
import sys
import time
from pathlib import Path
from urllib.request import Request, urlopen
from urllib.error import URLError, HTTPError

# ── Prompt 模板 ──────────────────────────────────────────────────────────────

PROMPT_DEJARGON = """你是一个口语改写助手。将以下专业术语密集的英文问题改写为日常口语表达，要求：
1. 去掉或替换专业术语，改用普通人能懂的日常说法
2. 保留原问题的核心意图和信息需求
3. 使用自然的日常对话语气
4. **输出必须是英文**（与原问题语言一致），禁止输出中文
5. 只返回改写后的问题本身，不要解释、不要引号、不要编号

原问题：
{question}

改写（英文）："""

PROMPT_KEYWORDS = """你是一个关键词抽取助手。从以下英文问题中抽取 5-8 个最核心的关键词，用空格连接，去掉所有疑问词（what/how/does/can 等）和连接词。要求：
1. 保留专业术语和专有名词的完整形式
2. 包含问题中的核心名词短语和关键动词
3. 只返回关键词序列本身，不要解释、不要引号、不要编号

原问题：
{question}

关键词："""

PROMPT_ZH = """你是一个翻译助手。将以下英文问题翻译为自然流畅的中文，要求：
1. 保留所有专业术语的准确中文翻译（或保留英文缩写如 RAG/API）
2. 保持原问题的语气和意图
3. 只返回中文翻译本身，不要解释、不要引号、不要编号

原问题：
{question}

中文："""

PROMPTS = {
    "dejargon": PROMPT_DEJARGON,
    "keywords": PROMPT_KEYWORDS,
    "zh": PROMPT_ZH,
}

OUTPUT_FILES = {
    "dejargon": "questions-variants-dejargon.jsonl",
    "keywords": "questions-variants-keywords.jsonl",
    "zh": "questions-variants-zh.jsonl",
}


# ── LLM 调用 ─────────────────────────────────────────────────────────────────

def chat(base_url: str, api_key: str, model: str, prompt: str, question: str) -> str:
    """单次非流式 OpenAI 兼容 chat 请求，返回 response text；异常抛 RuntimeError。"""
    body = json.dumps({
        "model": model,
        "messages": [
            {"role": "user", "content": prompt.format(question=question)},
        ],
        "temperature": 0.3,
        "max_tokens": 512,
        "stream": False,
    }).encode("utf-8")

    url = base_url.rstrip("/") + "/chat/completions"
    req = Request(url, data=body, headers={
        "Content-Type": "application/json",
        "Authorization": f"Bearer {api_key}",
    })

    try:
        with urlopen(req, timeout=60) as resp:
            data = json.loads(resp.read())
        content = data["choices"][0]["message"]["content"]
        return content.strip()
    except (HTTPError, URLError, KeyError, IndexError) as e:
        raise RuntimeError(f"LLM 请求失败: {e}") from e


# ── 断点续跑 ──────────────────────────────────────────────────────────────────

def load_done_ids(out_path: Path) -> set:
    """读取已生成的变体文件中的 question_id 集合，用于断点跳过。"""
    if not out_path.exists():
        return set()
    ids = set()
    with open(out_path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            try:
                ids.add(json.loads(line)["question_id"])
            except (json.JSONDecodeError, KeyError):
                continue
    return ids


# ── 单类变体生成 ──────────────────────────────────────────────────────────────

def generate_one_variant(
    variant: str,
    questions: list[dict],
    out_path: Path,
    base_url: str,
    api_key: str,
    model: str,
    delay: float,
) -> int:
    """生成一种变体的全部题目，返回成功数。"""
    prompt = PROMPTS[variant]
    done_ids = load_done_ids(out_path)
    ok = skip = fail = 0

    with open(out_path, "a", encoding="utf-8") as out_f:
        for i, q in enumerate(questions):
            qid = q["question_id"]
            if qid in done_ids:
                skip += 1
                continue

            try:
                new_question = chat(base_url, api_key, model, prompt, q["question"])
                new_case = {
                    "question_id": qid,
                    "question_type": q.get("question_type", ""),
                    "question": new_question,
                    "expected_doc_ids": q.get("expected_doc_ids", []),
                }
                out_f.write(json.dumps(new_case, ensure_ascii=False) + "\n")
                out_f.flush()
                ok += 1
                print(f"  [{variant}] {i+1}/{len(questions)}  {qid}  ✓  "
                      f"({len(new_question)} chars)")
            except Exception as e:
                fail += 1
                print(f"  [{variant}] {i+1}/{len(questions)}  {qid}  ✗  {e}",
                      file=sys.stderr)
            time.sleep(delay)  # 避免 API 限流

    print(f"[{variant}] 完成: 本次新增 {ok}, 已存在跳过 {skip}, 失败 {fail}")
    return ok


# ── 主逻辑 ────────────────────────────────────────────────────────────────────

def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--input", required=True,
                    help="JSONL 原始问题文件路径（questions.jsonl）")
    ap.add_argument("--out", default="./data/derived",
                    help="变体输出目录（默认 ./data/derived）")
    ap.add_argument("--base-url", required=True,
                    help="OpenAI 兼容 API base URL")
    ap.add_argument("--api-key", default=os.environ.get("LLM_API_KEY"),
                    help="API Key（默认取环境变量 LLM_API_KEY）")
    ap.add_argument("--model", default="Qwen/Qwen2.5-7B-Instruct",
                    help="生成变体使用的模型（推荐 cheap LLM）")
    ap.add_argument("--only", choices=["dejargon", "keywords", "zh"],
                    help="只生成指定类型变体（默认全部三类）")
    ap.add_argument("--delay", type=float, default=0.5,
                    help="题间延迟秒数，避免 API 限流（默认 0.5s）")
    args = ap.parse_args()

    if not args.api_key:
        print("错误: 需要 API Key，请设置 --api-key 或 LLM_API_KEY 环境变量",
              file=sys.stderr)
        return 1

    # 读取原始问题
    in_path = Path(args.input)
    if not in_path.exists():
        print(f"错误: 输入文件不存在: {in_path}", file=sys.stderr)
        return 1

    questions = []
    with open(in_path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line:
                questions.append(json.loads(line))
    print(f"读取 {len(questions)} 条原始问题")

    out_dir = Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)

    variants = [args.only] if args.only else ["dejargon", "keywords", "zh"]
    total = 0
    for var in variants:
        out_path = out_dir / OUTPUT_FILES[var]
        total += generate_one_variant(
            var, questions, out_path, args.base_url, args.api_key, args.model,
            args.delay,
        )

    print(f"\n全部完成: 共生成 {total} 条变体（目标 {len(variants)} × {len(questions)} = "
          f"{len(variants) * len(questions)}）")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())