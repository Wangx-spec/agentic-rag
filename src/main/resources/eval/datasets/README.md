# Eval Datasets 说明

| 文件 | 来源 | 许可 |
|---|---|---|
| `eval-set.json` / `intent-set.json` | 本项目自建 golden set（配套 `../corpus/` 合成语料） | 随本项目 Apache 2.0 |
| `enterprise-rag-64.jsonl` | 派生自 [EnterpriseRAG-Bench](https://github.com/onyx-dot-app/EnterpriseRAG-Bench)（Onyx），从其 500 题中筛出答案完全落在 Confluence 源的 64 题 | 原项目 MIT License |
| `enterprise-info-not-found-20.jsonl` | 同上，info_not_found 题型 20 题（拒答能力辅助评测） | 原项目 MIT License |

派生数据按 MIT 许可再分发，著作权归原项目所有。语料（文档本体）不入库，
转换与接入流程见 `docs/plan/M6-EnterpriseRAG-Bench接入Runbook.md` 与
`scripts/convert_enterpriserag.py`。
