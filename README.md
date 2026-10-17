# Phoenix — 智能故障诊断与修复助手

Phoenix 面向开发者的应用故障排查场景：通过工具收集证据，结合知识文档与历史经验形成诊断，经用户审批执行受控修复，并验证处理结果。

## 当前进度

目前完成**项目初始化**：三个可独立启动的 Spring Boot 应用、健康检查、最小示例接口、框架冒烟测试与开发文档。

**尚未实现**真实模型调用、MCP 协议端点与工具、诊断任务、审批恢复、RAG、长期记忆和前端。`phoenix-mcp-server` 当前只是工具服务骨架；示例应用尚未依赖 Redis，停止 Redis 不会影响当前探活接口。

## 技术基础

- Java 21、Maven 3.9.14（提供 Maven Wrapper）。
- Spring Boot 3.5.16。
- LangChain4j 1.22.0：后续承担模型与工具调用。
- LangGraph4j 1.8.22：后续承担任务流程与暂停恢复。
- 测试使用 JUnit 5、Spring Boot Test、AssertJ。

当前仅验证客户端构建与最小图执行，不等同于验证真实模型、远程 MCP 或持久化恢复。版本选择参考 [Spring Boot 系统要求](https://docs.spring.io/spring-boot/3.5/system-requirements.html) 和 [LangGraph4j 官方集成示例](https://langgraph4j.github.io/langgraph4j/main/how-tos/basic-reflection/)，后续按实际集成测试调整。

## 项目结构

```text
phoenix-agent/
├─ pom.xml                 Maven 父工程，统一版本与聚合构建
├─ phoenix-server/         Agent 控制端，默认端口 18080
├─ phoenix-mcp-server/     目标机工具服务骨架，默认端口 18081
├─ demo/                   最小目标应用，默认端口 18082
│  └─ compose.yaml         可选的独立 Redis 演示环境
├─ docs/                   随实现更新的正式文档
└─ notes/                  按开发轮次递增的中文记录
```

各模块源码位于 `src/main/java`，配置位于 `src/main/resources`，测试位于 `src/test/java`。目前没有前端或静态资源。

## 本地运行

在 IDEA 中打开仓库根目录的 `pom.xml`，选择 Java 21 并导入 Maven。运行以下任意入口类即可，不需要 API Key、MySQL、Elasticsearch 或 Docker：

| 模块 | 启动类 | 健康检查 |
| --- | --- | --- |
| 控制端 | `PhoenixServerApplication` | `http://127.0.0.1:18080/actuator/health` |
| 工具服务骨架 | `PhoenixMcpServerApplication` | `http://127.0.0.1:18081/actuator/health` |
| 目标应用 | `PhoenixDemoApplication` | `http://127.0.0.1:18082/actuator/health` |

默认只监听本机回环地址；可通过进程环境变量 `SERVER_ADDRESS`、`SERVER_PORT` 覆盖。各模块提供 `/actuator/info` 查看应用身份，不暴露环境变量、配置值或健康组件详情。当前没有鉴权，不应开放到公网。

Windows PowerShell 中，在仓库根目录执行：

```powershell
# 构建全部模块并运行测试
.\mvnw.cmd verify

# 启动控制端；另外两个模块分别将 -pl 改成 phoenix-mcp-server 或 demo
.\mvnw.cmd -pl phoenix-server spring-boot:run

# 检查目标应用的示例接口（启动 demo 后执行）
curl.exe http://127.0.0.1:18082/api/demo/ping
```

macOS / Linux 使用 `./mvnw` 替换 `.\mvnw.cmd`。首次执行 Wrapper 会下载 Maven，首次构建也需要联网解析依赖。

## 测试与打包

```powershell
# 只验证控制端及所需的父工程
.\mvnw.cmd -pl phoenix-server -am verify

# 打包后也可以直接启动可执行 JAR
java -jar phoenix-server/target/phoenix-server-0.0.1-SNAPSHOT.jar
```

普通测试在随机端口启动真实 HTTP 服务，但不请求模型、不连接外部数据库、不操作 Docker。新增业务逻辑时补充对应测试，暂不设数字化覆盖率门槛。

## 可选演示组件

仅在需要准备 Redis 时执行，不是当前启动前提，也不会由 Spring Boot 自动执行：

```powershell
docker compose -f demo/compose.yaml up -d
docker compose -f demo/compose.yaml ps
docker compose -f demo/compose.yaml stop
```

Redis 仅映射到 `127.0.0.1:16379`，避免占用已有的 6379 端口；该配置无密码，仅供本机演示，不能用于公网或生产。数据使用独立命名卷，停止容器保留数据。当前 Compose 只包含 Redis，Java 应用仍由 IDEA 或 Maven 启动。

## 开发约定

新增方法使用简短中文 Javadoc；中文提交信息；每轮开发在 `notes/` 记录功能、增改类与测试结果。正式架构说明见 [架构设计](docs/架构设计.md)，表结构维护见 [数据库设计](docs/数据库设计.md)。不提交密钥、密码、本地环境文件或构建产物。
