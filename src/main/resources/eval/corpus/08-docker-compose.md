# Docker Compose

容器化编排包含 app、postgres、qdrant、redis 四个服务。postgres 使用 pg_isready 健康检查，qdrant 使用 /healthz，redis 使用 redis-cli ping，应用服务依赖这些基础服务健康后再启动。这样本地演示只需一条 docker compose up 命令。
