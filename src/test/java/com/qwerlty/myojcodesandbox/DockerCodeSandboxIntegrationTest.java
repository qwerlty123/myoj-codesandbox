package com.qwerlty.myojcodesandbox;

import com.github.dockerjava.api.DockerClient;
import com.qwerlty.myojcodesandbox.config.DockerSandboxConfiguration;
import com.qwerlty.myojcodesandbox.config.SandboxContainerProperties;
import com.qwerlty.myojcodesandbox.model.ExecuteCodeRequest;
import com.qwerlty.myojcodesandbox.model.ExecuteCodeResponse;
import com.qwerlty.myojcodesandbox.model.ExecutionProfile;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.time.Duration;
import java.nio.file.Path;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "RUN_DOCKER_SANDBOX_IT", matches = "true")
class DockerCodeSandboxIntegrationTest {

    @TempDir
    static Path workspaceRoot;

    private static DockerClient dockerClient;
    private static DockerCodeSandbox sandbox;

    @BeforeAll
    static void setUp() {
        dockerClient = new DockerSandboxConfiguration().sandboxDockerClient();
        SandboxContainerProperties properties = new SandboxContainerProperties();
        properties.setWorkspaceRoot(workspaceRoot.toString());
        sandbox = new DockerCodeSandbox(dockerClient, properties);
    }

    @AfterAll
    static void tearDown() throws IOException {
        if (dockerClient != null) {
            dockerClient.close();
        }
    }

    @Test
    void executesJava17InARestrictedContainer() {
        assertDoubles("java", "import java.util.*; public class Main { "
                + "public static void main(String[] args) { Scanner scanner = new Scanner(System.in); "
                + "System.out.print(scanner.nextLong() * 2); } }");
    }

    @Test
    void executesCpp17InARestrictedContainer() {
        assertDoubles("cpp", "#include <iostream>\nint main() { long long value; std::cin >> value; "
                + "std::cout << value * 2; return 0; }");
    }

    @Test
    void executesGo122InARestrictedContainer() {
        assertDoubles("go", "package main\nimport \"fmt\"\nfunc main() { var value int64; "
                + "fmt.Scan(&value); fmt.Print(value * 2) }");
    }

    @Test
    void javaSelectionWithCppSourceReturnsCompileErrorWithoutHanging() {
        String cppSource = "#include <bits/stdc++.h>\n"
                + "using namespace std;\n\n"
                + "int main() {\n"
                + "    ios::sync_with_stdio(false);\n"
                + "    cin.tie(nullptr);\n\n"
                + "    int n;\n"
                + "    long long B;\n"
                + "    if (!(cin >> n >> B)) return 0;\n\n"
                + "    vector<long long> a(n);\n"
                + "    for (int i = 0; i < n; ++i) cin >> a[i];\n\n"
                + "    long long ans = 0;\n"
                + "    int left = 0, right = n - 1;\n\n"
                + "    while (left < right) {\n"
                + "        if (a[left] + a[right] <= B) {\n"
                + "            ans += right - left;\n"
                + "            ++left;\n"
                + "        } else {\n"
                + "            --right;\n"
                + "        }\n"
                + "    }\n\n"
                + "    cout << ans << '\\n';\n"
                + "    return 0;\n"
                + "}";
        ExecuteCodeResponse response = assertTimeoutPreemptively(Duration.ofSeconds(30), () ->
                sandbox.executeCode(ExecuteCodeRequest.builder()
                        .language("java")
                        .code(cppSource)
                        .inputList(Collections.singletonList(""))
                        .build()));

        assertEquals(3, response.getStatus());
        assertEquals("Compile Error", response.getJudgeInfo().getMessage());
        assertTrue(response.getMessage() != null && !response.getMessage().trim().isEmpty());
    }

    private void assertDoubles(String language, String code) {
        ExecutionProfile profile = new ExecutionProfile();
        profile.setPurpose("INTEGRATION_TEST");
        profile.setTimeLimitMs(3_000L);
        profile.setMemoryLimitKb(262_144L);
        profile.setStackLimitKb(65_536L);
        profile.setOutputLimitBytes(65_536);
        ExecuteCodeResponse response = sandbox.executeCode(ExecuteCodeRequest.builder()
                .language(language)
                .code(code)
                .inputList(Collections.singletonList("21"))
                .executionProfile(profile)
                .build());

        assertEquals(1, response.getStatus(), response.getMessage());
        assertEquals(Collections.singletonList("42"), response.getOutputList());
        assertEquals(1, response.getCaseResults().size());
    }
}
