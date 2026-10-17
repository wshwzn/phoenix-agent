# MCP 通信链路与连通性测试

## 本轮功能

- 工具服务通过官方 Java SDK 提供真实 Streamable HTTP `/mcp` 端点，只注册无参数 `ping` 工具。
- 控制端通过 LangChain4j MCP 完成初始化、工具发现和 `tools/call`，不是直接调用普通 REST 探活接口。
- 提供本机 `GET /api/mcp/check` 联调入口，返回发现的工具名及固定服务信息。
- 按请求创建并关闭短会话；对端不响应不影响控制端启动，检查失败映射为 HTTP 502。
- 加入地址/超时校验、错误结果检查、MCP 请求体上限和 Origin 限制。
- 不接入模型、目标应用、Docker、数据库、RAG 或诊断流程。

## 新增类及作用

| 类 | 模块 | 作用 |
| --- | --- | --- |
| `McpServerConfiguration` | 工具端 | 配置官方 SDK、Jackson 2、Servlet 和唯一测试工具 |
| `PingTool` | 工具端 | 声明空参数 Schema，返回固定 JSON 文本和结构化服务信息 |
| `McpOriginFilter` | 工具端 | 拒绝浏览器带 Origin 的 MCP 请求 |
| `McpClientProperties` | 控制端 | 绑定并校验固定服务 URL 与阶段超时 |
| `McpClientFactory` | 控制端 | 按需构建真实协议客户端，初始化失败时清理传输 |
| `McpConnectionService` | 控制端 | 定义连通性检查业务接口 |
| `McpConnectionServiceImpl` | 控制端 | 发现工具、调用 ping、校验响应、关闭会话 |
| `McpPingResult` | 控制端 | 表达工具返回的服务名、版本与状态 |
| `McpConnectionResult` | 控制端 | 表达检查接口返回的传输类型、工具名及 ping 结果 |
| `McpConnectionException` | 控制端 | 封装内部通信错误，对外提供不含内部异常的提示 |
| `McpConnectionController` | 控制端 | 提供固定检查入口和 502 Problem Detail 响应 |
| `McpConnectionIntegrationTests` | 控制端测试 | 用独立 SDK HTTP 服务验证生产检查入口及重复连接 |
| `McpConnectionFailureTests` | 控制端测试 | 验证无响应对端下仍可启动，并有限时返回 502 |
| `McpConnectionServiceTests` | 控制端测试 | 验证缺失工具、错误和畸形结果不会被误报为成功，失败也关闭会话 |
| `McpClientPropertiesTests` | 控制端测试 | 验证 URL、凭据与超时边界 |

## 修改内容

- `PhoenixMcpServerApplication`：更新入口类说明，由骨架进入真实协议阶段。
- `PhoenixMcpServerApplicationTests`：替换原先 `/mcp` 应不存在的断言，增加真实协议、参数与边界测试。
- 父 POM：统一 SDK 与 MCP 客户端依赖版本；子模块 POM 分别加入生产依赖和测试依赖，生产模块间不互相依赖。
- 两端 `application.yml`：更新能力说明，控制端增加默认 URL 和超时配置。
- README、架构说明：维护当前状态、操作方式与未完成范围；新增 `docs/MCP连通性.md`。
- 无数据库变更，无新增模型配置或敏感配置文件。

## 验证结果

- `.\mvnw.cmd -B -ntp clean verify`：三个模块完整清理构建成功，31 项测试通过，失败、错误、跳过均为 0。
- 自动化测试覆盖真实 HTTP 握手、无参数 Schema、工具发现、ping 调用、重复检查、未知工具、额外参数、Origin 拒绝和超大请求体。
- 控制端测试覆盖无响应服务下正常启动、250ms 阶段超时触发 502、错误提示不泄露底层信息，以及异常结果与会话关闭。
- 另将两个可执行 JAR 启动为独立进程，使用随机本机端口完成两次实际检查，均返回工具列表及 PONG；不依赖测试替身。
- 停止上述 MCP 进程后，控制端检查返回 502，自身健康状态保持 UP；随后关闭测试用控制端进程，没有留下后台服务。
- 未启动 demo、Redis 或 Docker，没有调用真实模型或修改服务器。
- `git diff --check`：通过。所有新增方法均有简短中文说明，业务 Service 采用接口与实现分离。

## 限制与下一步

当前无身份鉴权，只用于本机联调；ping 只能证明协议调用成功，不能判断 demo 或真实应用是否健康。阶段超时不是整个检查的总耗时上限。模型自主选工具、采集工具和图流程恢复均未实现，本轮不提前声称完成。
