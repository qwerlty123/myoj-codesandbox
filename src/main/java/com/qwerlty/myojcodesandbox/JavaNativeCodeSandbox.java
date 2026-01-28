package com.qwerlty.myojcodesandbox;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.qwerlty.myojcodesandbox.config.SandboxConstants;
import com.qwerlty.myojcodesandbox.model.ExecuteCodeRequest;
import com.qwerlty.myojcodesandbox.model.ExecuteCodeResponse;
import com.qwerlty.myojcodesandbox.model.ExecuteMessage;
import com.qwerlty.myojcodesandbox.model.JudgeInfo;
import com.qwerlty.myojcodesandbox.security.ForbiddenWordChecker;
import com.qwerlty.myojcodesandbox.utils.ProcessUtils;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Java 原生代码沙箱：安全校验、违禁词、超时与内存限制
 */
public class JavaNativeCodeSandbox implements CodeSandbox {

    private static final String GLOBAL_CODE_DIR_NAME = "tmpCode";
    private static final String GLOBAL_JAVA_CLASS_NAME = "Main.java";

    /** 安全管理器 classpath：优先使用 target/classes，便于子进程加载同一 SecurityManager */
    private static final String getSecurityManagerClassPath() {
        String userDir = System.getProperty("user.dir");
        return userDir + File.separator + "target" + File.separator + "classes";
    }

    private static final String SECURITY_MANAGER_CLASS_NAME = "com.qwerlty.myojcodesandbox.security.MySecurityManager";

    /** 执行状态：1 正常 2 沙箱错误 3 用户代码运行错误 4 请求非法/违禁词 5 超时 */
    private static final int STATUS_OK = 1;
    private static final int STATUS_SANDBOX_ERROR = 2;
    private static final int STATUS_USER_ERROR = 3;
    private static final int STATUS_FORBIDDEN_OR_INVALID = 4;
    private static final int STATUS_TIMEOUT = 5;

    public static void main(String[] args) {
        JavaNativeCodeSandbox sandbox = new JavaNativeCodeSandbox();
        ExecuteCodeRequest req = new ExecuteCodeRequest();
        req.setInputList(Arrays.asList("1 2", "1 3"));
        req.setCode("public class Main { public static void main(String[] args) { System.out.println(1+2); } }");
        req.setLanguage("java");
        ExecuteCodeResponse resp = sandbox.executeCode(req);
        System.out.println(resp);
    }

    @Override
    public ExecuteCodeResponse executeCode(ExecuteCodeRequest executeCodeRequest) {
        List<String> inputList = executeCodeRequest.getInputList();
        String code = executeCodeRequest.getCode();
        String language = executeCodeRequest.getLanguage();

        // ---------- 1. 安全与参数校验 ----------
        ExecuteCodeResponse invalid = validateRequest(code, language, inputList);
        if (invalid != null) {
            return invalid;
        }

        // ---------- 2. 违禁词检查 ----------
        String forbidden = ForbiddenWordChecker.check(code);
        if (forbidden != null) {
            return buildResponse(null, "代码包含违禁内容，不允许使用: " + forbidden, STATUS_FORBIDDEN_OR_INVALID, null);
        }

        String userDir = System.getProperty("user.dir");
        String globalCodePathName = userDir + File.separator + GLOBAL_CODE_DIR_NAME;
        if (!FileUtil.exist(globalCodePathName)) {
            FileUtil.mkdir(globalCodePathName);
        }

        String userCodeParentPath = globalCodePathName + File.separator + UUID.randomUUID();
        String userCodePath = userCodeParentPath + File.separator + GLOBAL_JAVA_CLASS_NAME;
        File userCodeFile = FileUtil.writeString(code, userCodePath, StandardCharsets.UTF_8);

        try {
            // ---------- 3. 编译 ----------
            String compileCmd = String.format("javac -encoding utf-8 %s", userCodeFile.getAbsolutePath());
            Process compileProcess = Runtime.getRuntime().exec(compileCmd);
            ExecuteMessage compileMsg = ProcessUtils.runProcessAndGetMessage(compileProcess, "编译", SandboxConstants.COMPILE_TIME_OUT_MS);
            if (compileMsg.getExitValue() != null && compileMsg.getExitValue() != 0) {
                return buildResponse(new ArrayList<>(), "编译失败: " + StrUtil.nullToEmpty(compileMsg.getErrorMessage()), STATUS_USER_ERROR, null);
            }

            // ---------- 4. 执行（带超时、内存限制、安全管理器） ----------
            String securityCp = getSecurityManagerClassPath();
            String cp = userCodeParentPath + File.pathSeparator + securityCp;

            List<ExecuteMessage> executeMessageList = new ArrayList<>();
            for (String inputArgs : inputList) {
                List<String> cmdList = new ArrayList<>();
                cmdList.add("java");
                cmdList.add("-Xmx" + SandboxConstants.JVM_XMX_MB + "m");
                cmdList.add("-Dfile.encoding=UTF-8");
                cmdList.add("-cp");
                cmdList.add(cp);
                cmdList.add("-Djava.security.manager=" + SECURITY_MANAGER_CLASS_NAME);
                cmdList.add("-Dallowed.read.path=" + userCodeParentPath);
                cmdList.add("Main");
                if (StrUtil.isNotBlank(inputArgs)) {
                    for (String a : inputArgs.split("\\s+")) {
                        cmdList.add(a.trim());
                    }
                }
                Process runProcess = new ProcessBuilder(cmdList)
                        .directory(new File(userCodeParentPath))
                        .redirectErrorStream(false)
                        .start();
                ExecuteMessage runMsg = ProcessUtils.runProcessAndGetMessage(runProcess, "运行", SandboxConstants.RUN_TIME_OUT_MS);
                executeMessageList.add(runMsg);
            }

            // ---------- 5. 整理结果 ----------
            return collectResponse(executeMessageList);
        } catch (Exception e) {
            return getErrorResponse(e);
        } finally {
            if (userCodeFile != null && userCodeFile.getParentFile() != null) {
                FileUtil.del(userCodeFile.getParentFile());
            }
        }
    }

    private ExecuteCodeResponse validateRequest(String code, String language, List<String> inputList) {
        if (StrUtil.isBlank(code)) {
            return buildResponse(new ArrayList<>(), "代码不能为空", STATUS_FORBIDDEN_OR_INVALID, null);
        }
        if (code.length() > SandboxConstants.MAX_CODE_LENGTH) {
            return buildResponse(new ArrayList<>(), "代码长度超过限制 " + SandboxConstants.MAX_CODE_LENGTH + " 字符", STATUS_FORBIDDEN_OR_INVALID, null);
        }
        if (!"java".equalsIgnoreCase(language)) {
            return buildResponse(new ArrayList<>(), "仅支持 Java 语言", STATUS_FORBIDDEN_OR_INVALID, null);
        }
        if (inputList != null && inputList.size() > SandboxConstants.MAX_INPUT_LIST_SIZE) {
            return buildResponse(new ArrayList<>(), "输入用例数量超过限制 " + SandboxConstants.MAX_INPUT_LIST_SIZE, STATUS_FORBIDDEN_OR_INVALID, null);
        }
        return null;
    }

    private ExecuteCodeResponse collectResponse(List<ExecuteMessage> executeMessageList) {
        ExecuteCodeResponse response = new ExecuteCodeResponse();
        List<String> outputList = new ArrayList<>();
        long maxTime = 0;

        for (ExecuteMessage msg : executeMessageList) {
            String err = msg.getErrorMessage();
            if (ProcessUtils.TIMEOUT_ERROR_MSG.equals(err)) {
                response.setStatus(STATUS_TIMEOUT);
                response.setMessage(err);
                response.setOutputList(outputList);
                JudgeInfo info = new JudgeInfo();
                info.setTime(msg.getTime());
                response.setJudgeInfo(info);
                return response;
            }
            if (StrUtil.isNotBlank(err)) {
                response.setMessage(err);
                response.setStatus(STATUS_USER_ERROR);
                response.setOutputList(outputList);
                JudgeInfo info = new JudgeInfo();
                info.setTime(msg.getTime());
                response.setJudgeInfo(info);
                return response;
            }
            outputList.add(StrUtil.nullToEmpty(msg.getMessage()));
            if (msg.getTime() != null) {
                maxTime = Math.max(maxTime, msg.getTime());
            }
        }

        response.setStatus(STATUS_OK);
        response.setOutputList(outputList);
        JudgeInfo judgeInfo = new JudgeInfo();
        judgeInfo.setTime(maxTime);
        judgeInfo.setMessage("运行限制: 时间 " + SandboxConstants.RUN_TIME_OUT_MS + "ms, 内存 " + SandboxConstants.JVM_XMX_MB + "MB");
        response.setJudgeInfo(judgeInfo);
        return response;
    }

    private ExecuteCodeResponse buildResponse(List<String> outputList, String message, int status, JudgeInfo judgeInfo) {
        ExecuteCodeResponse r = new ExecuteCodeResponse();
        r.setOutputList(outputList != null ? outputList : new ArrayList<>());
        r.setMessage(message);
        r.setStatus(status);
        r.setJudgeInfo(judgeInfo != null ? judgeInfo : new JudgeInfo());
        return r;
    }

    private ExecuteCodeResponse getErrorResponse(Throwable e) {
        return buildResponse(new ArrayList<>(), "沙箱异常: " + e.getMessage(), STATUS_SANDBOX_ERROR, new JudgeInfo());
    }
}
