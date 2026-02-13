package com.qwerlty.myojcodesandbox;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.qwerlty.myojcodesandbox.model.ExecuteCodeRequest;
import com.qwerlty.myojcodesandbox.model.ExecuteCodeResponse;
import com.qwerlty.myojcodesandbox.model.ExecuteMessage;
import com.qwerlty.myojcodesandbox.model.JudgeInfo;
import com.qwerlty.myojcodesandbox.utils.ProcessUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Java 原生代码沙箱的公共执行流程。
 */
@Slf4j
public abstract class JavaCodeSandboxTemplate implements CodeSandbox {

    private static final String GLOBAL_CODE_DIR_NAME = "tmpCode";

    private static final String GLOBAL_JAVA_CLASS_NAME = "Main.java";

    private static final long COMPILE_TIMEOUT_MILLIS = 10_000L;

    private static final long EXECUTE_TIMEOUT_MILLIS = 5_000L;

    private static final int OUTPUT_LIMIT_BYTES = 64 * 1024;

    private static final int MAX_CODE_BYTES = 1024 * 1024;

    private static final int MAX_INPUT_BYTES = 1024 * 1024;

    private static final int MAX_JUDGE_CASES = 100;

    private static final int STATUS_SUCCEED = 1;

    private static final int STATUS_SANDBOX_ERROR = 2;

    private static final int STATUS_USER_CODE_ERROR = 3;

    @Override
    public ExecuteCodeResponse executeCode(ExecuteCodeRequest request) {
        ExecuteCodeResponse validationError = validateRequest(request);
        if (validationError != null) {
            return validationError;
        }

        File userCodeFile = null;
        try {
            userCodeFile = saveCodeToFile(request.getCode());
            ExecuteMessage compileResult = compileFile(userCodeFile);
            if (Boolean.TRUE.equals(compileResult.getTimedOut())) {
                return response(STATUS_SANDBOX_ERROR, "编译进程超时", "System Error", Collections.emptyList(), compileResult.getTime());
            }
            if (compileResult.getExitValue() == null || compileResult.getExitValue() != 0) {
                String compileMessage = firstNonBlank(
                        compileResult.getErrorMessage(),
                        compileResult.getMessage(),
                        "代码编译失败"
                );
                return response(
                        STATUS_USER_CODE_ERROR,
                        sanitizeCompilerMessage(compileMessage, userCodeFile),
                        "Compile Error",
                        Collections.emptyList(),
                        compileResult.getTime()
                );
            }

            List<ExecuteMessage> executeMessageList = runFile(userCodeFile, request.getInputList());
            return getOutputResponse(executeMessageList);
        } catch (Exception e) {
            log.error("代码沙箱执行失败", e);
            return getErrorResponse(e);
        } finally {
            if (userCodeFile != null && !deleteFile(userCodeFile)) {
                log.error("临时代码目录清理失败: {}", userCodeFile.getParent());
            }
        }
    }

    /** 保存用户代码到相互隔离的临时目录。 */
    public File saveCodeToFile(String code) {
        String userDir = System.getProperty("user.dir");
        String globalCodePathName = userDir + File.separator + GLOBAL_CODE_DIR_NAME;
        if (!FileUtil.exist(globalCodePathName)) {
            FileUtil.mkdir(globalCodePathName);
        }

        String userCodeParentPath = globalCodePathName + File.separator + UUID.randomUUID();
        String userCodePath = userCodeParentPath + File.separator + GLOBAL_JAVA_CLASS_NAME;
        return FileUtil.writeString(code, userCodePath, StandardCharsets.UTF_8);
    }

    /** 编译 Java 源文件。编译错误属于正常业务结果，不在这里抛异常。 */
    public ExecuteMessage compileFile(File userCodeFile) {
        try {
            Process compileProcess = new ProcessBuilder(
                    "javac",
                    "-encoding",
                    "UTF-8",
                    userCodeFile.getAbsolutePath()
            ).start();
            return ProcessUtils.runProcessAndGetMessage(
                    compileProcess,
                    null,
                    "编译",
                    COMPILE_TIMEOUT_MILLIS,
                    OUTPUT_LIMIT_BYTES
            );
        } catch (Exception e) {
            throw new IllegalStateException("无法启动 Java 编译器", e);
        }
    }

    /** 对每个测试用例单独启动进程，并通过标准输入传入原始用例内容。 */
    public List<ExecuteMessage> runFile(File userCodeFile, List<String> inputList) {
        String userCodeParentPath = userCodeFile.getParentFile().getAbsolutePath();
        List<ExecuteMessage> results = new ArrayList<>();
        for (String input : inputList) {
            try {
                Process process = new ProcessBuilder(
                        "java",
                        "-Xmx256m",
                        "-Dfile.encoding=UTF-8",
                        "-cp",
                        userCodeParentPath,
                        "Main"
                ).start();
                results.add(ProcessUtils.runProcessAndGetMessage(
                        process,
                        input,
                        "运行",
                        getExecuteTimeoutMillis(),
                        getOutputLimitBytes()
                ));
            } catch (Exception e) {
                throw new IllegalStateException("无法启动用户代码进程", e);
            }
        }
        return results;
    }

    /** 将进程结果转换成稳定的沙箱协议。 */
    public ExecuteCodeResponse getOutputResponse(List<ExecuteMessage> executeMessageList) {
        List<String> outputList = new ArrayList<>();
        long maxTime = 0L;

        for (ExecuteMessage executeMessage : executeMessageList) {
            if (executeMessage.getTime() != null) {
                maxTime = Math.max(maxTime, executeMessage.getTime());
            }
            if (Boolean.TRUE.equals(executeMessage.getOutputLimitExceeded())) {
                return response(STATUS_USER_CODE_ERROR, "程序输出超过 64 KB 限制", "Output Limit Exceeded", outputList, maxTime);
            }
            if (Boolean.TRUE.equals(executeMessage.getTimedOut())) {
                return response(STATUS_USER_CODE_ERROR, "程序执行超过时间限制", "Time Limit Exceeded", outputList, maxTime);
            }
            if (executeMessage.getExitValue() == null || executeMessage.getExitValue() != 0) {
                return response(
                        STATUS_USER_CODE_ERROR,
                        firstNonBlank(executeMessage.getErrorMessage(), executeMessage.getMessage(), "程序异常退出"),
                        "Runtime Error",
                        outputList,
                        maxTime
                );
            }
            outputList.add(executeMessage.getMessage() == null ? "" : executeMessage.getMessage());
        }

        return response(STATUS_SUCCEED, null, null, outputList, maxTime);
    }

    /** 删除本次执行的整个隔离目录。 */
    public boolean deleteFile(File userCodeFile) {
        File parent = userCodeFile == null ? null : userCodeFile.getParentFile();
        return parent == null || !parent.exists() || FileUtil.del(parent);
    }

    protected long getExecuteTimeoutMillis() {
        return EXECUTE_TIMEOUT_MILLIS;
    }

    protected int getOutputLimitBytes() {
        return OUTPUT_LIMIT_BYTES;
    }

    private ExecuteCodeResponse validateRequest(ExecuteCodeRequest request) {
        if (request == null) {
            return getErrorResponse(new IllegalArgumentException("请求不能为空"));
        }
        if (!"java".equalsIgnoreCase(request.getLanguage())) {
            return getErrorResponse(new IllegalArgumentException("暂不支持该编程语言: " + request.getLanguage()));
        }
        if (StrUtil.isBlank(request.getCode())) {
            return getErrorResponse(new IllegalArgumentException("代码不能为空"));
        }
        if (request.getCode().getBytes(StandardCharsets.UTF_8).length > MAX_CODE_BYTES) {
            return getErrorResponse(new IllegalArgumentException("代码大小不能超过 1 MB"));
        }
        List<String> inputs = request.getInputList();
        if (inputs == null || inputs.isEmpty()) {
            return getErrorResponse(new IllegalArgumentException("测试用例不能为空"));
        }
        if (inputs.size() > MAX_JUDGE_CASES) {
            return getErrorResponse(new IllegalArgumentException("测试用例数量不能超过 " + MAX_JUDGE_CASES));
        }
        for (String input : inputs) {
            if (input != null && input.getBytes(StandardCharsets.UTF_8).length > MAX_INPUT_BYTES) {
                return getErrorResponse(new IllegalArgumentException("单个测试用例不能超过 1 MB"));
            }
        }
        return null;
    }

    private ExecuteCodeResponse getErrorResponse(Throwable throwable) {
        String message = throwable.getMessage();
        if (StrUtil.isBlank(message) && throwable.getCause() != null) {
            message = throwable.getCause().getMessage();
        }
        return response(
                STATUS_SANDBOX_ERROR,
                StrUtil.isBlank(message) ? "代码沙箱内部错误" : message,
                "System Error",
                Collections.emptyList(),
                0L
        );
    }

    private ExecuteCodeResponse response(int status,
                                         String message,
                                         String judgeMessage,
                                         List<String> outputs,
                                         Long time) {
        JudgeInfo judgeInfo = new JudgeInfo();
        judgeInfo.setMessage(judgeMessage);
        judgeInfo.setMemory(0L);
        judgeInfo.setTime(time == null ? 0L : time);
        return ExecuteCodeResponse.builder()
                .outputList(new ArrayList<>(outputs))
                .message(message)
                .status(status)
                .judgeInfo(judgeInfo)
                .build();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (StrUtil.isNotBlank(value)) {
                return value;
            }
        }
        return "";
    }

    private String sanitizeCompilerMessage(String message, File userCodeFile) {
        if (message == null || userCodeFile == null) {
            return message;
        }
        return message.replace(userCodeFile.getAbsolutePath(), GLOBAL_JAVA_CLASS_NAME);
    }
}
