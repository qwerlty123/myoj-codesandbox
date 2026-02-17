package com.qwerlty.myojcodesandbox;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.WaitContainerResultCallback;
import com.github.dockerjava.api.exception.DockerClientException;
import com.github.dockerjava.api.model.AccessMode;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.LogConfig;
import com.github.dockerjava.api.model.StreamType;
import com.github.dockerjava.api.model.Ulimit;
import com.github.dockerjava.api.model.Volume;
import com.github.dockerjava.api.async.ResultCallback;
import com.qwerlty.myojcodesandbox.config.SandboxContainerProperties;
import com.qwerlty.myojcodesandbox.model.CaseExecutionResult;
import com.qwerlty.myojcodesandbox.model.ExecuteCodeRequest;
import com.qwerlty.myojcodesandbox.model.ExecuteCodeResponse;
import com.qwerlty.myojcodesandbox.model.ExecutionProfile;
import com.qwerlty.myojcodesandbox.model.JudgeInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * 使用受限 Docker 容器编译并运行 Java、C++ 和 Go。一次请求只编译一次，
 * 每个测试用例使用全新的运行容器，避免用例之间通过文件或后台进程相互污染。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "codesandbox.type", havingValue = "container", matchIfMissing = true)
public class DockerCodeSandbox implements CodeSandbox {

    private static final int STATUS_SUCCEED = 1;
    private static final int STATUS_SANDBOX_ERROR = 2;
    private static final int STATUS_USER_CODE_ERROR = 3;

    private final DockerClient dockerClient;
    private final SandboxContainerProperties properties;
    private final Semaphore executionPermits;

    public DockerCodeSandbox(DockerClient dockerClient, SandboxContainerProperties properties) {
        this.dockerClient = dockerClient;
        this.properties = properties;
        this.executionPermits = new Semaphore(Math.max(1, properties.getMaxConcurrentExecutions()), true);
        log.info("Docker sandbox runtimes configured: javaImage={}, cppImage={}, goImage={}",
                properties.getJavaImage(), properties.getCppImage(), properties.getGoImage());
    }

    @Override
    public ExecuteCodeResponse executeCode(ExecuteCodeRequest request) {
        Validation validation = validate(request);
        if (!validation.valid) {
            return response(STATUS_SANDBOX_ERROR, validation.message, "System Error",
                    Collections.<String>emptyList(), Collections.<CaseExecutionResult>emptyList(), 0L);
        }

        Path workspace = null;
        boolean permitAcquired = false;
        try {
            permitAcquired = executionPermits.tryAcquire(
                    properties.getQueueWaitTimeoutMs(), TimeUnit.MILLISECONDS);
            if (!permitAcquired) {
                return response(STATUS_SANDBOX_ERROR, "沙箱执行资源繁忙，请稍后重试", "System Error",
                        Collections.<String>emptyList(), Collections.<CaseExecutionResult>emptyList(), 0L);
            }
            workspace = createWorkspace(validation.runtime, request);
            Limits limits = limits(request.getExecutionProfile());
            RunResult compile = runContainer(validation.runtime.image(properties), workspace, AccessMode.rw,
                    validation.runtime.compileCommand(), properties.getCompileTimeoutMs(),
                    properties.getMaxOutputLimitBytes(), limits, true);
            if (compile.timedOut) {
                return response(STATUS_SANDBOX_ERROR, "编译进程超时", "System Error",
                        Collections.<String>emptyList(), Collections.<CaseExecutionResult>emptyList(), compile.timeMs);
            }
            if (compile.exitCode != 0) {
                if (isMissingRuntimeCommand(compile.exitCode, compile.stderr, compile.stdout)) {
                    return response(STATUS_SANDBOX_ERROR,
                            "沙箱运行时缺少编译命令: " + validation.runtime.name().toLowerCase(java.util.Locale.ROOT),
                            "System Error", Collections.<String>emptyList(),
                            Collections.<CaseExecutionResult>emptyList(), compile.timeMs);
                }
                return response(STATUS_USER_CODE_ERROR,
                        sanitize(firstNonBlank(compile.stderr, compile.stdout, "代码编译失败"), workspace),
                        "Compile Error", Collections.<String>emptyList(),
                        Collections.<CaseExecutionResult>emptyList(), compile.timeMs);
            }
            if (!Files.exists(workspace.resolve(validation.runtime.getArtifactFile()))) {
                return response(STATUS_SANDBOX_ERROR, "编译未生成预期产物", "System Error",
                        Collections.<String>emptyList(), Collections.<CaseExecutionResult>emptyList(), compile.timeMs);
            }

            return runCases(validation.runtime, workspace, request.getInputList(), limits);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return response(STATUS_SANDBOX_ERROR, "沙箱请求已中断", "System Error",
                    Collections.<String>emptyList(), Collections.<CaseExecutionResult>emptyList(), 0L);
        } catch (DockerClientException exception) {
            log.error("Docker 沙箱不可用", exception);
            return response(STATUS_SANDBOX_ERROR, "容器运行时不可用: " + safeMessage(exception),
                    "System Error", Collections.<String>emptyList(),
                    Collections.<CaseExecutionResult>emptyList(), 0L);
        } catch (Exception exception) {
            log.error("容器沙箱执行失败", exception);
            return response(STATUS_SANDBOX_ERROR, "代码沙箱内部错误: " + safeMessage(exception),
                    "System Error", Collections.<String>emptyList(),
                    Collections.<CaseExecutionResult>emptyList(), 0L);
        } finally {
            if (workspace != null && !FileUtil.del(workspace.toFile())) {
                log.error("沙箱临时目录清理失败: {}", workspace);
            }
            if (permitAcquired) {
                executionPermits.release();
            }
        }
    }

    private ExecuteCodeResponse runCases(LanguageRuntime runtime,
                                         Path workspace,
                                         List<String> inputs,
                                         Limits limits) throws IOException {
        List<String> outputs = new ArrayList<>();
        List<CaseExecutionResult> cases = new ArrayList<>();
        long maxTime = 0L;

        for (int index = 0; index < inputs.size(); index++) {
            RunResult result = runContainer(runtime.image(properties), workspace, AccessMode.ro,
                    runtime.runCommand(limits.memoryLimitKb, limits.stackLimitKb, index),
                    limits.timeLimitMs, limits.outputLimitBytes, limits, false);
            maxTime = Math.max(maxTime, result.timeMs);
            CaseExecutionResult caseResult = CaseExecutionResult.builder()
                    .index(index)
                    .exitCode(result.exitCode)
                    .output(result.stdout)
                    .error(result.stderr)
                    .timeMs(result.timeMs)
                    .timedOut(result.timedOut)
                    .outputLimitExceeded(result.outputLimitExceeded)
                    .build();
            cases.add(caseResult);

            if (result.outputLimitExceeded) {
                return response(STATUS_USER_CODE_ERROR, "程序输出超过限制", "Output Limit Exceeded",
                        outputs, cases, maxTime);
            }
            if (result.timedOut) {
                return response(STATUS_USER_CODE_ERROR, "程序执行超过时间限制", "Time Limit Exceeded",
                        outputs, cases, maxTime);
            }
            if (result.exitCode != 0) {
                return response(STATUS_USER_CODE_ERROR,
                        firstNonBlank(result.stderr, result.stdout, "程序异常退出"),
                        result.exitCode == 137 ? "Memory Limit Exceeded" : "Runtime Error",
                        outputs, cases, maxTime);
            }
            outputs.add(stripTrailingLineBreaks(result.stdout));
        }

        return response(STATUS_SUCCEED, null, null, outputs, cases, maxTime);
    }

    private RunResult runContainer(String image,
                                   Path workspace,
                                   AccessMode accessMode,
                                   String command,
                                   long timeoutMs,
                                   int outputLimitBytes,
                                   Limits limits,
                                   boolean compiling) {
        String containerId = null;
        long started = 0L;
        boolean timedOut = false;
        try {
            HostConfig hostConfig = hostConfig(workspace, accessMode, limits, compiling);
            CreateContainerResponse created = dockerClient.createContainerCmd(image)
                    .withName("myoj-sandbox-" + UUID.randomUUID())
                    .withHostConfig(hostConfig)
                    .withWorkingDir("/workspace")
                    .withUser(properties.getUser())
                    .withEnv(Arrays.asList(
                            "HOME=/tmp",
                            "LANG=C.UTF-8",
                            "GOCACHE=/tmp/go-cache",
                            "GOMODCACHE=/tmp/go-mod"))
                    .withCmd(containerCommand(command))
                    .withLabels(Collections.singletonMap("myoj.sandbox", "true"))
                    .withNetworkDisabled(true)
                    .withAttachStdout(true)
                    .withAttachStderr(true)
                    .exec();
            containerId = created.getId();
            started = System.nanoTime();
            dockerClient.startContainerCmd(containerId).exec();

            Integer exitCode;
            WaitContainerResultCallback waiter = dockerClient.waitContainerCmd(containerId).start();
            try {
                exitCode = waiter.awaitStatusCode(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (DockerClientException timeout) {
                if (!isWaitTimeout(timeout)) {
                    if (timeout.getCause() instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                    throw timeout;
                }
                timedOut = true;
                killQuietly(containerId);
                exitCode = -1;
            } finally {
                closeQuietly(waiter);
            }

            FrameCollector collector = new FrameCollector(outputLimitBytes);
            try {
                dockerClient.logContainerCmd(containerId)
                        .withStdOut(true)
                        .withStdErr(true)
                        .withTailAll()
                        .exec(collector);
                collector.awaitCompletion(3, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                closeQuietly(collector);
            }
            return new RunResult(exitCode == null ? -1 : exitCode,
                    collector.stdout(), collector.stderr(), timedOut,
                    collector.limitExceeded(), started == 0L ? 0L : elapsedMs(started));
        } finally {
            removeQuietly(containerId);
        }
    }

    static String[] containerCommand(String command) {
        // 登录 Shell 会重置官方运行时镜像的 PATH，例如丢失 /usr/local/go/bin。
        return new String[]{"sh", "-c", command};
    }

    static boolean isMissingRuntimeCommand(int exitCode, String stderr, String stdout) {
        if (exitCode != 127) {
            return false;
        }
        String message = firstNonBlankStatic(stderr, stdout).toLowerCase(java.util.Locale.ROOT);
        return message.contains("not found") || message.contains("no such file or directory");
    }

    private static String firstNonBlankStatic(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private HostConfig hostConfig(Path workspace,
                                  AccessMode accessMode,
                                  Limits limits,
                                  boolean compiling) {
        long effectiveMemoryKb = compiling
                ? Math.max(limits.memoryLimitKb, properties.getDefaultMemoryLimitKb())
                : limits.memoryLimitKb;
        long memoryBytes = effectiveMemoryKb * 1024L;
        Map<String, String> tmpFs = new HashMap<>();
        tmpFs.put("/tmp", "rw,nosuid,nodev,noexec,size=128m");
        Map<String, String> logOptions = new HashMap<>();
        logOptions.put("max-size", "1m");
        logOptions.put("max-file", "1");

        return HostConfig.newHostConfig()
                .withBinds(new Bind(workspace.toAbsolutePath().toString(), new Volume("/workspace"), accessMode))
                .withNetworkMode("none")
                .withReadonlyRootfs(true)
                .withTmpFs(tmpFs)
                .withMemory(memoryBytes)
                .withMemorySwap(memoryBytes)
                .withNanoCPUs(properties.getNanoCpus())
                .withPidsLimit(properties.getPidsLimit())
                .withCapDrop(Capability.ALL)
                .withSecurityOpts(Collections.singletonList("no-new-privileges"))
                .withUlimits(Arrays.asList(
                        new Ulimit("nofile", 64L, 64L),
                        new Ulimit("nproc", properties.getPidsLimit(), properties.getPidsLimit()),
                        new Ulimit("stack", limits.stackLimitKb * 1024L, limits.stackLimitKb * 1024L)))
                .withLogConfig(new LogConfig(LogConfig.LoggingType.JSON_FILE, logOptions))
                .withAutoRemove(false)
                .withInit(true)
                .withOomKillDisable(false)
                .withPrivileged(false);
    }

    private Path createWorkspace(LanguageRuntime runtime, ExecuteCodeRequest request) throws IOException {
        Path root = Paths.get(properties.getWorkspaceRoot()).toAbsolutePath().normalize();
        Files.createDirectories(root);
        Path workspace = Files.createTempDirectory(root, "exec-");
        setPermissions(workspace, EnumSet.allOf(PosixFilePermission.class));
        Path source = workspace.resolve(runtime.getSourceFile());
        Files.write(source, request.getCode().getBytes(StandardCharsets.UTF_8));
        setPermissions(source, EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.GROUP_READ, PosixFilePermission.OTHERS_READ));
        for (int index = 0; index < request.getInputList().size(); index++) {
            String value = request.getInputList().get(index);
            Path input = workspace.resolve("input-" + index + ".txt");
            Files.write(input, (value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            setPermissions(input, EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.GROUP_READ, PosixFilePermission.OTHERS_READ));
        }
        return workspace;
    }

    private Validation validate(ExecuteCodeRequest request) {
        if (request == null) {
            return Validation.error("请求不能为空");
        }
        LanguageRuntime runtime = LanguageRuntime.from(request.getLanguage());
        if (runtime == null) {
            return Validation.error("暂不支持该编程语言: " + request.getLanguage());
        }
        if (StrUtil.isBlank(request.getCode())) {
            return Validation.error("代码不能为空");
        }
        if (request.getCode().getBytes(StandardCharsets.UTF_8).length > properties.getMaxCodeBytes()) {
            return Validation.error("代码大小不能超过 1 MB");
        }
        List<String> inputs = request.getInputList();
        if (inputs == null || inputs.isEmpty()) {
            return Validation.error("测试用例不能为空");
        }
        if (inputs.size() > properties.getMaxJudgeCases()) {
            return Validation.error("测试用例数量不能超过 " + properties.getMaxJudgeCases());
        }
        for (String input : inputs) {
            if (input != null && input.getBytes(StandardCharsets.UTF_8).length > properties.getMaxInputBytes()) {
                return Validation.error("单个测试用例不能超过 1 MB");
            }
        }
        return Validation.success(runtime);
    }

    private Limits limits(ExecutionProfile profile) {
        long time = bounded(profile == null ? null : profile.getTimeLimitMs(),
                properties.getDefaultTimeLimitMs(), 100L, properties.getMaxTimeLimitMs());
        long memory = bounded(profile == null ? null : profile.getMemoryLimitKb(),
                properties.getDefaultMemoryLimitKb(), 16_384L, properties.getMaxMemoryLimitKb());
        long stack = bounded(profile == null ? null : profile.getStackLimitKb(),
                properties.getDefaultStackLimitKb(), 256L, properties.getMaxStackLimitKb());
        int output = (int) bounded(profile == null || profile.getOutputLimitBytes() == null
                        ? null : profile.getOutputLimitBytes().longValue(),
                properties.getDefaultOutputLimitBytes(), 1_024L, properties.getMaxOutputLimitBytes());
        return new Limits(time, memory, stack, output);
    }

    private long bounded(Long requested, long defaultValue, long minimum, long maximum) {
        if (requested == null) {
            return defaultValue;
        }
        return Math.max(minimum, Math.min(maximum, requested));
    }

    private ExecuteCodeResponse response(int status,
                                         String message,
                                         String judgeMessage,
                                         List<String> outputs,
                                         List<CaseExecutionResult> cases,
                                         long timeMs) {
        JudgeInfo judgeInfo = new JudgeInfo();
        judgeInfo.setMessage(judgeMessage);
        judgeInfo.setMemory(0L);
        judgeInfo.setTime(timeMs);
        return ExecuteCodeResponse.builder()
                .status(status)
                .message(message)
                .outputList(new ArrayList<>(outputs))
                .caseResults(new ArrayList<>(cases))
                .judgeInfo(judgeInfo)
                .build();
    }

    private void killQuietly(String containerId) {
        if (containerId == null) {
            return;
        }
        try {
            dockerClient.killContainerCmd(containerId).exec();
        } catch (Exception ignored) {
            // 容器可能已经退出。
        }
    }

    private void removeQuietly(String containerId) {
        if (containerId == null) {
            return;
        }
        try {
            dockerClient.removeContainerCmd(containerId).withForce(true).withRemoveVolumes(true).exec();
        } catch (Exception exception) {
            log.error("残留容器清理失败: {}", containerId, exception);
        }
    }

    private void closeQuietly(Closeable closeable) {
        try {
            closeable.close();
        } catch (IOException ignored) {
            // Docker 回调关闭失败不影响容器的强制清理。
        }
    }

    private void setPermissions(Path path, Set<PosixFilePermission> permissions) {
        try {
            Files.setPosixFilePermissions(path, permissions);
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows / Docker Desktop 共享目录不支持 POSIX 权限时使用平台默认权限。
        }
    }

    private String sanitize(String value, Path workspace) {
        return value == null ? null : value.replace(workspace.toAbsolutePath().toString(), "/workspace");
    }

    private String stripTrailingLineBreaks(String value) {
        return value == null ? "" : value.replaceFirst("[\\r\\n]+$", "");
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (StrUtil.isNotBlank(value)) {
                return value;
            }
        }
        return "";
    }

    private String safeMessage(Throwable throwable) {
        Throwable cause = throwable.getCause() == null ? throwable : throwable.getCause();
        return StrUtil.blankToDefault(cause.getMessage(), cause.getClass().getSimpleName());
    }

    private long elapsedMs(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    private boolean isWaitTimeout(DockerClientException exception) {
        return exception.getMessage() != null
                && exception.getMessage().toLowerCase(java.util.Locale.ROOT).contains("timeout");
    }

    private static final class Validation {
        private final boolean valid;
        private final String message;
        private final LanguageRuntime runtime;

        private Validation(boolean valid, String message, LanguageRuntime runtime) {
            this.valid = valid;
            this.message = message;
            this.runtime = runtime;
        }

        private static Validation success(LanguageRuntime runtime) {
            return new Validation(true, null, runtime);
        }

        private static Validation error(String message) {
            return new Validation(false, message, null);
        }
    }

    private static final class Limits {
        private final long timeLimitMs;
        private final long memoryLimitKb;
        private final long stackLimitKb;
        private final int outputLimitBytes;

        private Limits(long timeLimitMs, long memoryLimitKb, long stackLimitKb, int outputLimitBytes) {
            this.timeLimitMs = timeLimitMs;
            this.memoryLimitKb = memoryLimitKb;
            this.stackLimitKb = stackLimitKb;
            this.outputLimitBytes = outputLimitBytes;
        }
    }

    private static final class RunResult {
        private final int exitCode;
        private final String stdout;
        private final String stderr;
        private final boolean timedOut;
        private final boolean outputLimitExceeded;
        private final long timeMs;

        private RunResult(int exitCode, String stdout, String stderr, boolean timedOut,
                          boolean outputLimitExceeded, long timeMs) {
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.stderr = stderr;
            this.timedOut = timedOut;
            this.outputLimitExceeded = outputLimitExceeded;
            this.timeMs = timeMs;
        }
    }

    private static final class FrameCollector extends ResultCallback.Adapter<Frame> {
        private final BoundedBuffer stdout;
        private final BoundedBuffer stderr;

        private FrameCollector(int limit) {
            this.stdout = new BoundedBuffer(limit);
            this.stderr = new BoundedBuffer(limit);
        }

        @Override
        public void onNext(Frame frame) {
            if (frame == null || frame.getPayload() == null) {
                return;
            }
            if (frame.getStreamType() == StreamType.STDERR) {
                stderr.append(frame.getPayload());
            } else {
                stdout.append(frame.getPayload());
            }
        }

        private String stdout() {
            return stdout.value();
        }

        private String stderr() {
            return stderr.value();
        }

        private boolean limitExceeded() {
            return stdout.exceeded || stderr.exceeded;
        }
    }

    private static final class BoundedBuffer {
        private final int limit;
        private final ByteArrayOutputStream output;
        private long received;
        private boolean exceeded;

        private BoundedBuffer(int limit) {
            this.limit = Math.max(0, limit);
            this.output = new ByteArrayOutputStream(Math.min(this.limit, 4096));
        }

        private synchronized void append(byte[] bytes) {
            received += bytes.length;
            int writable = Math.min(bytes.length, Math.max(0, limit - output.size()));
            if (writable > 0) {
                output.write(bytes, 0, writable);
            }
            exceeded = received > limit;
        }

        private synchronized String value() {
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
