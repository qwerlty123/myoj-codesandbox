package com.qwerlty.myojcodesandbox;

import com.github.dockerjava.api.DockerClient;
import com.qwerlty.myojcodesandbox.config.SandboxContainerProperties;
import com.qwerlty.myojcodesandbox.model.ExecuteCodeRequest;
import com.qwerlty.myojcodesandbox.model.ExecuteCodeResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class DockerCodeSandboxValidationTest {

    private DockerClient dockerClient;
    private DockerCodeSandbox sandbox;

    @BeforeEach
    void setUp() {
        dockerClient = mock(DockerClient.class);
        sandbox = new DockerCodeSandbox(dockerClient, new SandboxContainerProperties());
    }

    @Test
    void rejectsUnsupportedLanguageBeforeTouchingDocker() {
        ExecuteCodeResponse response = sandbox.executeCode(request("python", "print(1)", ""));

        assertEquals(2, response.getStatus());
        assertTrue(response.getMessage().contains("不支持"));
        verifyNoInteractions(dockerClient);
    }

    @Test
    void rejectsEmptyCasesBeforeTouchingDocker() {
        ExecuteCodeRequest request = request("java", "public class Main {}", "");
        request.setInputList(Collections.<String>emptyList());

        ExecuteCodeResponse response = sandbox.executeCode(request);

        assertEquals(2, response.getStatus());
        assertTrue(response.getMessage().contains("测试用例"));
        verifyNoInteractions(dockerClient);
    }

    @Test
    void rejectsOversizedSourceBeforeTouchingDocker() {
        SandboxContainerProperties properties = new SandboxContainerProperties();
        properties.setMaxCodeBytes(3);
        sandbox = new DockerCodeSandbox(dockerClient, properties);

        ExecuteCodeResponse response = sandbox.executeCode(request("go", "1234", ""));

        assertEquals(2, response.getStatus());
        assertTrue(response.getMessage().contains("代码大小"));
        verifyNoInteractions(dockerClient);
    }

    private ExecuteCodeRequest request(String language, String code, String input) {
        return ExecuteCodeRequest.builder()
                .language(language)
                .code(code)
                .inputList(Collections.singletonList(input))
                .build();
    }
}
