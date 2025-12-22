package com.qwerlty.myojcodesandbox.config;

/**
 * 代码沙箱常量配置（超时、内存、代码长度等）
 */
public final class SandboxConstants {

    /** 单用例运行超时时间（毫秒） */
    public static final long RUN_TIME_OUT_MS = 5000L;

    /** 编译超时时间（毫秒） */
    public static final long COMPILE_TIME_OUT_MS = 10000L;

    /** 子进程 JVM 最大堆内存（MB），对应 java -Xmx */
    public static final int JVM_XMX_MB = 256;

    /** 用户代码最大长度（字符），防止超大代码占用资源 */
    public static final int MAX_CODE_LENGTH = 100_000;

    /** 单次请求最大输入用例数量 */
    public static final int MAX_INPUT_LIST_SIZE = 20;

    private SandboxConstants() {
    }
}
