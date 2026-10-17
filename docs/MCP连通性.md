# MCP 连通性

## 连通性检查范围

验证控制端与工具服务之间的真实 MCP 通信，不请求大模型、不连接目标应用、不采集机器信息、不操作 Docker。无需启动 `demo` 或任何数据库。

```text
GET /api/mcp/check（控制端）
  → 创建 LangChain4j MCP 客户端
  → HTTP 初始化与协议协商
  → tools/list 发现工具
  → tools/call 调用 ping
  → 校验结果并关闭会话
```

这不是把普通 REST 接口包装成工具名，也不是 MCP 自带的协议级 `ping`：我们注册的是名为 `ping` 的业务测试工具，实际通过 `tools/call` 调用。

## 协议与实现

| 项目 | 当前约定 |
| --- | --- |
| 传输 | Streamable HTTP |
| 工具服务入口 | `http://127.0.0.1:18081/mcp` |
| 控制端检查入口 | `GET http://127.0.0.1:18080/api/mcp/check` |
| MCP 协议版本 | `2025-11-25`，与本轮固定的两端版本配套 |
| 工具端 SDK | 官方 Java SDK 1.1.5，`mcp-core` + `mcp-json-jackson2` |
| 客户端 | LangChain4j MCP 1.22.0-beta32 |

协议 Servlet 仅映射 `/mcp`，不接管健康检查。传输层由官方 SDK 处理初始化、会话与 JSON-RPC，不自行手写协议。控制端通过客户端 SDK 发现工具和发送调用。

## ping 工具约定

- 名称：`ping`，仅用于测试通信；不是目标应用健康检查。
- 输入：空对象 `{}`，JSON Schema 为 `object`，无属性，禁止额外属性。
- 成功：同时返回 JSON 文本和结构化结果，包含 `service=phoenix-mcp-server`、`version=0.0.1-SNAPSHOT`、`status=PONG`。
- 失败：额外参数返回工具错误；未注册的工具不能调用。
- 控制端只采信格式正确、服务身份匹配、版本非空且状态为 PONG 的结果。

## 配置与生命周期

控制端 `phoenix.mcp.server-url` 默认指向本机工具服务，可用 `PHOENIX_MCP_SERVER_URL` 覆盖。地址必须是 HTTP(S)，不能携带用户凭据、查询参数或 URL 片段，且不跟随重定向。检查 API 没有目标 URL 或工具名入参。

`phoenix.mcp.request-timeout` 默认 `5s`，可用 `PHOENIX_MCP_REQUEST_TIMEOUT` 覆盖；允许范围为大于 0、最多 30 秒，约束各次请求及初始化/工具执行阶段，不是全流程总截止时间。

控制端启动时仅加载配置，不主动连接 MCP；每次检查建立一个短会话，结束后关闭。初始化失败时同样关闭已创建的传输。工具端关闭时释放协议资源，无开放通知流的闲置会话按五分钟周期回收。本轮不提供长期客户端连接池。

## 错误与安全边界

连接失败、超时、没有 ping 工具、工具执行错误和异常结果统一返回 HTTP 502 与不含底层地址、堆栈的提示。它表示连通性检查失败，不能据此认定某个业务应用故障。

工具端请求体上限为 16 KiB，拒绝带 Origin 的浏览器请求。两端默认仅监听回环地址，尚无身份鉴权；不能公开到公网。工具的只读标注只是元数据；当前额外提供三个受固定目标、参数和采集大小约束的只读工具，详见 [只读故障诊断](只读故障诊断.md)。无任意网络/文件参数、Shell 或写操作。跨机器前仍须落实身份鉴权，写操作必须具备审批。

`GET /api/mcp/check` 仍只执行 `ping`，但返回的 `tools` 为实际发现列表，当前包含 `ping`、`inspect_application`、`inspect_redis`、`read_recent_logs`。目标应用或 Redis 停止不影响这个协议连通性检查。

## 验证方式

启动 `PhoenixMcpServerApplication` 和 `PhoenixServerApplication`，请求控制端检查接口。关闭工具端后再次请求，应失败而不是返回虚假 PONG；控制端自身健康检查仍应可用。

自动化测试分别验证生产工具服务的真实 HTTP 协议行为，以及生产控制端连接独立 SDK 测试服务的完整 HTTP 入口。另有异常结果、配置校验、无响应超时、来源与大小限制测试。测试不依赖手动启动的进程或外部服务。

实现参考：[LangChain4j MCP](https://docs.langchain4j.dev/tutorials/mcp/)、[官方 Java MCP SDK](https://github.com/modelcontextprotocol/java-sdk)。
