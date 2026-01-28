package com.qwerlty.myojcodesandbox.utils;

import cn.hutool.core.util.StrUtil;
import com.qwerlty.myojcodesandbox.model.ExecuteMessage;
import org.apache.commons.lang3.StringUtils;
import org.springframework.util.StopWatch;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 进程工具类：执行进程并支持超时、获取输出与耗时
 */
public class ProcessUtils {

    /** 超时时的错误信息前缀 */
    public static final String TIMEOUT_ERROR_MSG = "运行超时，已强制终止";

    /**
     * 执行进程并获取信息（无超时，一直等待）
     */
    public static ExecuteMessage runProcessAndGetMessage(Process runProcess, String opName) {
        return runProcessAndGetMessage(runProcess, opName, null);
    }

    /**
     * 执行进程并获取信息，支持超时
     *
     * @param runProcess 进程
     * @param opName     操作名称（如 "编译"、"运行"）
     * @param timeoutMs  超时毫秒数，null 表示不限制
     * @return 执行结果；若超时则 errorMessage 为 TIMEOUT_ERROR_MSG，并会 destroy 进程
     */
    public static ExecuteMessage runProcessAndGetMessage(Process runProcess, String opName, Long timeoutMs) {
        ExecuteMessage executeMessage = new ExecuteMessage();

        try {
            StopWatch stopWatch = new StopWatch();
            stopWatch.start();

            boolean finishedInTime;
            if (timeoutMs != null && timeoutMs > 0) {
                finishedInTime = runProcess.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
                if (!finishedInTime) {
                    runProcess.destroyForcibly();
                    runProcess.waitFor(2, TimeUnit.SECONDS);
                    executeMessage.setExitValue(-1);
                    executeMessage.setErrorMessage(TIMEOUT_ERROR_MSG);
                    stopWatch.stop();
                    executeMessage.setTime(stopWatch.getLastTaskTimeMillis());
                    return executeMessage;
                }
            } else {
                runProcess.waitFor();
            }

            int exitValue = runProcess.exitValue();
            executeMessage.setExitValue(exitValue);

            if (exitValue == 0) {
                String out = readStream(runProcess.getInputStream());
                executeMessage.setMessage(out != null ? out : "");
            } else {
                String out = readStream(runProcess.getInputStream());
                executeMessage.setMessage(out != null ? out : "");
                String err = readStream(runProcess.getErrorStream());
                executeMessage.setErrorMessage(err != null ? err : "");
            }

            stopWatch.stop();
            executeMessage.setTime(stopWatch.getLastTaskTimeMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            runProcess.destroyForcibly();
            executeMessage.setExitValue(-1);
            executeMessage.setErrorMessage("执行被中断: " + e.getMessage());
        } catch (Exception e) {
            executeMessage.setExitValue(-1);
            executeMessage.setErrorMessage(opName + "异常: " + e.getMessage());
        }
        return executeMessage;
    }

    private static String readStream(InputStream is) {
        if (is == null) return "";
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
            List<String> lines = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
            return StringUtils.join(lines, "\n");
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 执行交互式进程并获取信息
     *
     * @param runProcess
     * @param args
     * @return
     */
    public static ExecuteMessage runInteractProcessAndGetMessage(Process runProcess, String args) {
        ExecuteMessage executeMessage = new ExecuteMessage();

        try {
            // 向控制台输入程序
            OutputStream outputStream = runProcess.getOutputStream();
            OutputStreamWriter outputStreamWriter = new OutputStreamWriter(outputStream);
            String[] s = args.split(" ");
            String join = StrUtil.join("\n", (Object[]) s) + "\n";
            outputStreamWriter.write(join);
            // 相当于按了回车，执行输入的发送
            outputStreamWriter.flush();

            // 分批获取进程的正常输出
            InputStream inputStream = runProcess.getInputStream();
            BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(inputStream));
            StringBuilder compileOutputStringBuilder = new StringBuilder();
            // 逐行读取
            String compileOutputLine;
            while ((compileOutputLine = bufferedReader.readLine()) != null) {
                compileOutputStringBuilder.append(compileOutputLine);
            }
            executeMessage.setMessage(compileOutputStringBuilder.toString());
            // 记得资源的释放，否则会卡死
            outputStreamWriter.close();
            outputStream.close();
            inputStream.close();
            runProcess.destroy();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return executeMessage;
    }
}
