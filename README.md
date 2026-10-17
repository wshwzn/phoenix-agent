# Phoenix — 智能故障诊断与修复助手

Phoenix 面向开发者的应用故障排查场景：通过工具收集证据，结合知识文档与历史经验形成诊断，经用户审批执行受控修复，并验证处理结果。

## 当前进度

目前完成项目初始化、真实 MCP 通信链路和**最小非流式模型对话**：模型可直接回答，也可自主请求 `ping`，后端执行后将工具结果反馈给模型，并在响应中返回调用明细。

**尚未实现**业务采集与修复工具、诊断任务、审批恢复、RAG、长期记忆和前端。当前 `ping` 只返回工具服务的固定信息，不访问 `demo`，也不代表被排障应用健康。示例应用尚未依赖 Redis，停止 Redis 不会影响当前探活接口。

## 技术基础

- Java 21、Maven 3.9.14（提供 Maven Wrapper）。
- Spring Boot 3.5.16。
- LangChain4j 1.22.0：承担兼容协议模型调用与消息、工具请求封装。
- LangGraph4j 1.8.22：后续承担任务流程与暂停恢复。
- MCP 客户端：LangChain4j MCP 1.22.0-beta32；服务端：官方 Java SDK 1.1.5，使用 Jackson 2 适配当前 Spring Boot。
- 测试使用 JUnit 5、Spring Boot Test、AssertJ。

自动化测试已验证真实 HTTP MCP 通信，以及生产模型客户端连接本机模拟 OpenAI 服务后的完整工具循环；未使用真实厂商 API Key，因此不代表某个真实模型的工具选择能力已验收。跨机器部署和图持久化恢复尚未验证。版本选择参考 [Spring Boot 系统要求](https://docs.spring.io/spring-boot/3.5/system-requirements.html)、[LangGraph4j 官方集成示例](https://langgraph4j.github.io/langgraph4j/main/how-tos/basic-reflection/) 和 [Java MCP SDK](https://github.com/modelcontextprotocol/java-sdk)。

## 项目结构

```text
phoenix-agent/
├─ pom.xml                 Maven 父工程，统一版本与聚合构建
├─ phoenix-server/         Agent 控制端，默认端口 18080
├─ phoenix-mcp-server/     MCP 工具服务，默认端口 18081
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
| 控制端 | `com.phoenix.server.PhoenixServerApplication` | `http://127.0.0.1:18080/actuator/health` |
| 工具服务 | `com.phoenix.mcp.PhoenixMcpServerApplication` | `http://127.0.0.1:18081/actuator/health` |
| 目标应用 | `com.phoenix.demo.PhoenixDemoApplication` | `http://127.0.0.1:18082/actuator/health` |

Java 根包与 Maven `groupId` 统一为 `com.phoenix`。如果已有 IDEA 启动配置仍指向旧包，请重新运行上表中的入口类，或修改启动配置的主类。

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

## 验证 MCP 连通性

分别启动 `PhoenixMcpServerApplication` 和 `PhoenixServerApplication`，无需启动 `demo`、Redis 或模型，然后访问：

```powershell
curl.exe http://127.0.0.1:18080/api/mcp/check
```

正常返回示例：

```json
{
  "transport": "STREAMABLE_HTTP",
  "tools": ["ping"],
  "ping": {
    "service": "phoenix-mcp-server",
    "version": "0.0.1-SNAPSHOT",
    "status": "PONG"
  }
}
```

MCP 协议入口是工具服务的 `http://127.0.0.1:18081/mcp`，不是普通网页或 REST 探活地址；浏览器直接打开它不等于完成 MCP 检查。

控制端默认连接上述地址，可通过 `PHOENIX_MCP_SERVER_URL` 覆盖；`PHOENIX_MCP_REQUEST_TIMEOUT` 默认 `5s`，有效范围为大于 0、不超过 30 秒，作用于各次网络请求与初始化/执行阶段，并非整个检查流程的总耗时上限。URL 不接受凭据、查询参数或片段。客户端按检查请求建立短会话，用完关闭；对端未启动不影响控制端启动，但检查接口返回 HTTP 502。

当前检查接口只调用固定的无参数 `ping`，不提供任意 URL、工具名或命令执行入口。工具端拒绝携带 `Origin` 的请求，不支持浏览器直接连接。双方尚无身份鉴权，只能用于本机联调，不要公开到公网。协议约定与测试范围见 [MCP 连通性](docs/MCP连通性.md)。

## 模型配置与最小对话

模型默认未启用，不影响应用启动、健康检查或 MCP 检查。使用支持工具调用的 OpenAI 兼容文本模型，在 IDEA 的控制端启动配置中填写环境变量：

| 环境变量 | 说明 |
| --- | --- |
| `PHOENIX_MODEL_ENABLED` | 设置为 `true` 启用模型 |
| `PHOENIX_MODEL_BASE_URL` | API 根路径，如 `https://厂商地址/v1`；不填 `/chat/completions` |
| `PHOENIX_MODEL_NAME` | 厂商支持工具调用的实际模型名称 |
| `PHOENIX_MODEL_API_KEY` | 仅在本地填写真实 Key，不提交、不发到对话中 |
| `PHOENIX_MODEL_REQUEST_TIMEOUT` | 可选，单次模型请求超时，默认 `30s` |

也可将 `phoenix-server/config/application-local.example.yml` 复制为同目录的 `application-local.yml`，填好后在 IDEA 激活 `local` profile，并把控制端 Working directory 设置为 `phoenix-server` 目录。该外部本地文件已被忽略且不会打入 JAR，不能提交；不要把真实 Key 写进 `src/main/resources`。当前没有 dotenv 加载器，放一个 `.env` 文件不会自动生效。

SDK 会在 `base-url` 下追加 `chat/completions`。例如配置 `https://example.com/v1`，实际请求为 `https://example.com/v1/chat/completions`；自定义厂商保留其要求的 API 路径前缀。配置修改后重启控制端。

启动模型已配置的控制端和 MCP 服务，向 `POST http://127.0.0.1:18080/api/chat` 发送 JSON：

```json
{
  "message": "检查一下 Phoenix MCP 服务是否连通，告诉我版本"
}
```

接口返回 `requestId`、`answer`、`toolCalls`。调用明细包含工具调用 ID、名称、参数、结果、状态与耗时；普通问候没有实际调用时 `toolCalls` 为空。模型是否调用由其响应决定，后端没有按用户关键词直接路由工具。

当前每次聊天都先连接 MCP 并发现工具，所以即使模型最后不调用工具，也需要工具服务可用。只允许 `ping`，默认最多 3 轮、总计 3 次调用；同轮多个请求也计入总次数。每个请求独立，没有历史对话；调用明细只在内存和响应中，不落库，日志仅记录请求 ID、代码与数量。失败响应也保留已实际发生的工具调用，不能把网络失败当成检查成功。

更完整的配置、错误代码和验证范围见 [模型与工具对话](docs/模型与工具对话.md)。当前没有鉴权，不得开放公网使用真实模型额度。

## 测试与打包

```powershell
# 只验证控制端及所需的父工程
.\mvnw.cmd -pl phoenix-server -am verify

# 打包后也可以直接启动可执行 JAR
java -jar phoenix-server/target/phoenix-server-0.0.1-SNAPSHOT.jar
```

普通测试在随机端口启动真实 HTTP 服务，并可请求本机模拟模型，但不请求真实模型厂商、不连接外部数据库、不操作 Docker。新增业务逻辑时补充对应测试，暂不设数字化覆盖率门槛。

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
