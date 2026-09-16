# 配置与安全

所有 API Key 都必须通过环境变量注入，不能硬编码到 application.yaml。README 和 docker-compose 示例只允许出现占位符，例如 LLM_API_KEY。这样仓库可以公开分发，同时避免把真实密钥提交进版本历史。
