package com.qwerlty.myojcodesandbox.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "codesandbox.container")
public class SandboxContainerProperties {

    private String javaImage = "eclipse-temurin:17-jdk";
    private String cppImage = "gcc:13";
    private String goImage = "golang:1.22";
    private String workspaceRoot = "tmpCode/container";
    private String user = "65534:65534";
    private long compileTimeoutMs = 20_000L;
    private long defaultTimeLimitMs = 5_000L;
    private long maxTimeLimitMs = 15_000L;
    private long defaultMemoryLimitKb = 262_144L;
    private long maxMemoryLimitKb = 524_288L;
    private long defaultStackLimitKb = 262_144L;
    private long maxStackLimitKb = 262_144L;
    private int defaultOutputLimitBytes = 65_536;
    private int maxOutputLimitBytes = 1_048_576;
    private int maxCodeBytes = 1_048_576;
    private int maxInputBytes = 1_048_576;
    private int maxJudgeCases = 100;
    private long nanoCpus = 1_000_000_000L;
    private long pidsLimit = 64L;
    private int maxConcurrentExecutions = 2;
    private long queueWaitTimeoutMs = 2_000L;
}
