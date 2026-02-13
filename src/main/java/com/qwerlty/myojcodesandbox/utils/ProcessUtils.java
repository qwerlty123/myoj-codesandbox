package com.qwerlty.myojcodesandbox.utils;

import com.qwerlty.myojcodesandbox.model.ExecuteMessage;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * 进程工具类
 */
public class ProcessUtils {

    private static final long DEFAULT_TIMEOUT_MILLIS = 60_000L;

    private static final int DEFAULT_OUTPUT_LIMIT_BYTES = 64 * 1024;

    private ProcessUtils() {
    }

    /**
     * 执行进程并获取信息
     *
     * @param runProcess
     * @param opName
     * @return
     */
    public static ExecuteMessage runProcessAndGetMessage(Process runProcess, String opName) {
        return runProcessAndGetMessage(
                runProcess,
                null,
                opName,
                DEFAULT_TIMEOUT_MILLIS,
                DEFAULT_OUTPUT_LIMIT_BYTES
        );
    }

    /**
     * 同时消费 stdout / stderr，向 stdin 写入测试数据，并在超时后终止进程。
     * 先 waitFor 再读取输出会在管道缓冲区写满时死锁，因此三个流必须并行处理。
     */
    public static ExecuteMessage runProcessAndGetMessage(Process process,
                                                         String input,
                                                         String opName,
                                                         long timeoutMillis,
                                                         int outputLimitBytes) {
        ExecuteMessage result = new ExecuteMessage();
        long startedAt = System.nanoTime();
        ExecutorService executor = Executors.newFixedThreadPool(3, daemonThreadFactory(opName));
        Future<CapturedOutput> stdoutFuture = executor.submit(new StreamCollector(process.getInputStream(), outputLimitBytes));
        Future<CapturedOutput> stderrFuture = executor.submit(new StreamCollector(process.getErrorStream(), outputLimitBytes));
        Future<?> stdinFuture = executor.submit(() -> writeInput(process, input));

        try {
            boolean finished = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS);
            if (!finished) {
                result.setTimedOut(true);
                terminate(process);
            } else {
                result.setTimedOut(false);
            }
            result.setExitValue(finished ? process.exitValue() : -1);

            CapturedOutput stdout = stdoutFuture.get(2, TimeUnit.SECONDS);
            CapturedOutput stderr = stderrFuture.get(2, TimeUnit.SECONDS);
            result.setMessage(stripTrailingLineBreaks(stdout.text));
            result.setErrorMessage(stripTrailingLineBreaks(stderr.text));
            result.setOutputLimitExceeded(stdout.truncated || stderr.truncated);
            stdinFuture.cancel(true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            terminate(process);
            result.setExitValue(-1);
            result.setErrorMessage(opName + "被中断");
        } catch (Exception e) {
            terminate(process);
            result.setExitValue(-1);
            result.setErrorMessage(opName + "进程处理失败: " + safeMessage(e));
        } finally {
            result.setTime(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt));
            executor.shutdownNow();
        }
        return result;
    }

    /**
     * 执行交互式进程并获取信息
     *
     * @param runProcess
     * @param args
     * @return
     */
    public static ExecuteMessage runInteractProcessAndGetMessage(Process runProcess, String args) {
        return runProcessAndGetMessage(
                runProcess,
                args,
                "运行",
                DEFAULT_TIMEOUT_MILLIS,
                DEFAULT_OUTPUT_LIMIT_BYTES
        );
    }

    private static void writeInput(Process process, String input) {
        try (OutputStream outputStream = process.getOutputStream()) {
            if (input != null) {
                outputStream.write(input.getBytes(StandardCharsets.UTF_8));
                if (!input.endsWith("\n")) {
                    outputStream.write('\n');
                }
            }
            outputStream.flush();
        } catch (IOException ignored) {
            // 进程提前退出或被超时终止时，关闭的 stdin 会产生 Broken pipe。
        }
    }

    private static void terminate(Process process) {
        if (!process.isAlive()) {
            return;
        }
        process.destroy();
        try {
            if (!process.waitFor(200, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                process.waitFor(200, TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    private static String stripTrailingLineBreaks(String value) {
        return value == null ? null : value.replaceFirst("[\\r\\n]+$", "");
    }

    private static String safeMessage(Exception exception) {
        Throwable cause = exception.getCause() == null ? exception : exception.getCause();
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }

    private static ThreadFactory daemonThreadFactory(String opName) {
        return runnable -> {
            Thread thread = new Thread(runnable, "sandbox-" + opName + "-io");
            thread.setDaemon(true);
            return thread;
        };
    }

    private static final class StreamCollector implements Callable<CapturedOutput> {

        private final InputStream inputStream;
        private final int limit;

        private StreamCollector(InputStream inputStream, int limit) {
            this.inputStream = inputStream;
            this.limit = Math.max(0, limit);
        }

        @Override
        public CapturedOutput call() throws IOException {
            ByteArrayOutputStream captured = new ByteArrayOutputStream(Math.min(limit, 4096));
            byte[] buffer = new byte[4096];
            long total = 0L;
            int read;
            try (InputStream stream = inputStream) {
                while ((read = stream.read(buffer)) != -1) {
                    int writable = Math.min(read, Math.max(0, limit - captured.size()));
                    if (writable > 0) {
                        captured.write(buffer, 0, writable);
                    }
                    total += read;
                }
            }
            return new CapturedOutput(
                    new String(captured.toByteArray(), StandardCharsets.UTF_8),
                    total > limit
            );
        }
    }

    private static final class CapturedOutput {

        private final String text;
        private final boolean truncated;

        private CapturedOutput(String text, boolean truncated) {
            this.text = text;
            this.truncated = truncated;
        }
    }
}
