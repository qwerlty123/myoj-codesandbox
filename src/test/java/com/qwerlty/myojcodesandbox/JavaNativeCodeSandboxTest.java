package com.qwerlty.myojcodesandbox;

import com.qwerlty.myojcodesandbox.model.ExecuteCodeRequest;
import com.qwerlty.myojcodesandbox.model.ExecuteCodeResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaNativeCodeSandboxTest {

    private static final String VALID_CODE =
            "import java.util.*; public class Main { public static void main(String[] args) { "
                    + "Scanner scanner = new Scanner(System.in); System.out.print(scanner.nextInt() * 2); } }";

    @TempDir
    Path temporaryDirectory;

    private String originalUserDirectory;
    private JavaNativeCodeSandbox sandbox;

    @BeforeEach
    void setUp() {
        originalUserDirectory = System.getProperty("user.dir");
        System.setProperty("user.dir", temporaryDirectory.toString());
        sandbox = new JavaNativeCodeSandbox();
    }

    @AfterEach
    void tearDown() {
        System.setProperty("user.dir", originalUserDirectory);
    }

    @Test
    void compileErrorReturnsStructuredUserErrorAndCleansTemporaryFiles() throws Exception {
        String invalidCode = "public class Main { public static void main(String[] args) { System.out.printIn(1); } }";

        ExecuteCodeResponse response = sandbox.executeCode(request(invalidCode, "java", ""));

        assertEquals(3, response.getStatus());
        assertNotNull(response.getMessage());
        assertFalse(response.getMessage().trim().isEmpty());
        assertFalse(response.getMessage().contains(temporaryDirectory.toString()));
        assertEquals("Compile Error", response.getJudgeInfo().getMessage());
        assertTemporaryCodeDirectoryIsEmpty();
    }

    @Test
    void judgeCaseIsWrittenToStandardInput() {
        ExecuteCodeResponse response = sandbox.executeCode(request(VALID_CODE, "java", "21"));

        assertEquals(1, response.getStatus());
        assertEquals(Collections.singletonList("42"), response.getOutputList());
    }

    @Test
    void runtimeErrorReturnsStructuredUserError() {
        String code = "public class Main { public static void main(String[] args) { throw new RuntimeException(\"boom\"); } }";

        ExecuteCodeResponse response = sandbox.executeCode(request(code, "java", ""));

        assertEquals(3, response.getStatus());
        assertEquals("Runtime Error", response.getJudgeInfo().getMessage());
        assertTrue(response.getMessage().contains("boom"));
    }

    @Test
    void emptyJudgeCaseListIsRejected() {
        ExecuteCodeResponse response = sandbox.executeCode(ExecuteCodeRequest.builder()
                .code(VALID_CODE)
                .language("java")
                .inputList(Collections.emptyList())
                .build());

        assertEquals(2, response.getStatus());
        assertTrue(response.getMessage().contains("测试用例"));
    }

    @Test
    void unsupportedLanguageIsRejected() {
        ExecuteCodeResponse response = sandbox.executeCode(request(VALID_CODE, "go", "21"));

        assertEquals(2, response.getStatus());
        assertTrue(response.getMessage().contains("不支持"));
    }

    @Test
    void timeoutIsReportedClearly() {
        sandbox = new JavaNativeCodeSandbox() {
            @Override
            protected long getExecuteTimeoutMillis() {
                return 200L;
            }
        };
        String code = "public class Main { public static void main(String[] args) { while (true) { } } }";

        ExecuteCodeResponse response = sandbox.executeCode(request(code, "java", ""));

        assertEquals(3, response.getStatus());
        assertEquals("Time Limit Exceeded", response.getJudgeInfo().getMessage());
    }

    @Test
    void excessiveOutputIsBoundedAndReportedClearly() {
        sandbox = new JavaNativeCodeSandbox() {
            @Override
            protected int getOutputLimitBytes() {
                return 1024;
            }
        };
        String code = "public class Main { public static void main(String[] args) { "
                + "for (int i = 0; i < 5000; i++) System.out.print('x'); } }";

        ExecuteCodeResponse response = sandbox.executeCode(request(code, "java", ""));

        assertEquals(3, response.getStatus());
        assertEquals("Output Limit Exceeded", response.getJudgeInfo().getMessage());
    }

    private ExecuteCodeRequest request(String code, String language, String input) {
        return ExecuteCodeRequest.builder()
                .code(code)
                .language(language)
                .inputList(Collections.singletonList(input))
                .build();
    }

    private void assertTemporaryCodeDirectoryIsEmpty() throws Exception {
        Path codeDirectory = temporaryDirectory.resolve("tmpCode");
        if (!Files.exists(codeDirectory)) {
            return;
        }
        try (java.util.stream.Stream<Path> children = Files.list(codeDirectory)) {
            assertEquals(0, children.count());
        }
    }
}
