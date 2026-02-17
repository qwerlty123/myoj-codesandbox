# MyOJ Code Sandbox

代码沙箱默认使用 Docker 容器运行 Java 17、C++17 和 Go 1.22。一次请求只编译一次，每个输入使用独立运行容器；原生 Java 沙箱仅保留作本机兼容模式，不应在生产环境启用。

## Security model

- 请求使用 `X-Timestamp` 和 `X-Signature`，签名内容为 `timestamp + "\n" + 原始请求体` 的 HMAC-SHA256。
- 容器禁用网络，根文件系统只读，仅挂载单次临时工作目录。
- 运行阶段工作目录只读；`/tmp` 是带大小限制的 `nosuid,nodev,noexec` tmpfs。
- 容器以 `65534:65534` 非 root 用户运行，丢弃全部 Linux capabilities，启用 `no-new-privileges` 和 Docker 默认 seccomp。
- 每个容器限制内存、CPU、进程数、文件描述符、栈、执行时间和日志大小；请求同时限制源码、输入、用例数量与输出大小。
- 宿主机使用公平信号量限制并发执行数，超过等待时间的请求返回可重试的系统繁忙状态。
- 容器无论成功、编译失败、超时或异常都会强制删除，临时目录在请求结束时清理。容器带 `myoj.sandbox=true` 标签，可用于宿主机异常宕机后的运维清理。

Docker 守护进程是安全边界的一部分。生产环境应把沙箱部署在独立节点，限制 API 入站来源，并禁止 AI Service 或业务服务直接访问 Docker Socket。不要在承载数据库、网关或其他核心服务的节点运行不可信代码。

## Configuration

```dotenv
CODESANDBOX_TYPE=container
CODESANDBOX_SECRET_KEY=replace-with-a-long-random-secret
CODESANDBOX_JAVA_IMAGE=eclipse-temurin:17-jdk
CODESANDBOX_CPP_IMAGE=gcc:13
CODESANDBOX_GO_IMAGE=golang:1.22
CODESANDBOX_WORKSPACE_ROOT=/var/lib/myoj-sandbox/work
```

生产发布应预拉取并扫描三种镜像，随后把镜像变量固定为经过批准的 digest，例如 `eclipse-temurin:17-jdk@sha256:...`，避免浮动标签在重启时改变运行环境。工作目录必须位于沙箱专用磁盘并配置容量告警。

默认上限可通过 `codesandbox.container.*` 配置调整。服务会把调用方请求的限制裁剪到平台最大值，不能通过请求提升平台上限。

## API

```http
POST /executeCode
X-Timestamp: 1786632000000
X-Signature: <hex hmac-sha256>
Content-Type: application/json

{
  "language": "java",
  "code": "public class Main { ... }",
  "inputList": ["1 2\n"],
  "executionProfile": {
    "purpose": "AI_VALIDATION",
    "timeLimitMs": 1000,
    "memoryLimitKb": 262144,
    "stackLimitKb": 65536,
    "outputLimitBytes": 1048576
  }
}
```

`status=1` 表示全部用例执行成功，`status=2` 表示沙箱或请求错误，`status=3` 表示编译、运行、超时、内存或输出限制错误。`caseResults` 提供逐用例的退出码、耗时和限制状态。

## Operations

```bash
mvn test
mvn package

# 部署节点已启动 Docker 且预拉取三种镜像后，执行真实三语言容器验收
RUN_DOCKER_SANDBOX_IT=true mvn -Dtest=DockerCodeSandboxIntegrationTest test
```

服务器已完成首次 systemd 安装后，可以从 Mac 使用可回滚发布向导更新：

```bash
./scripts/deploy-server.sh
```

向导默认发布到 `ubuntu@124.221.250.220:22`，可通过 `DEPLOY_HOST`、
`DEPLOY_USER` 和 `DEPLOY_PORT` 环境变量覆盖。它会执行测试打包、上传、镜像与配置
检查、旧 JAR 备份、原子替换、服务重启和健康检查；启动失败时自动恢复旧 JAR。

上线探针使用 `/actuator/health`；Docker 守护进程不可用时健康状态会变为 `DOWN`。指标在 `/actuator/prometheus` 暴露。部署时应额外告警宿主机磁盘、Docker daemon、残留 `myoj.sandbox=true` 容器数量和请求错误率。

若宿主机异常退出导致容器残留，在确认没有沙箱请求运行后，可由运维流程按 `myoj.sandbox=true` 标签列出并清理；不要对整个 Docker 节点执行无条件 prune。
