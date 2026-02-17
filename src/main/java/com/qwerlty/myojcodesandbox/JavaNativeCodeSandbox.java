package com.qwerlty.myojcodesandbox;

import com.qwerlty.myojcodesandbox.JavaCodeSandboxTemplate;

import com.qwerlty.myojcodesandbox.model.ExecuteCodeRequest;
import com.qwerlty.myojcodesandbox.model.ExecuteCodeResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Java 原生代码沙箱实现（直接复用模板方法）
 */
@Component
@ConditionalOnProperty(name = "codesandbox.type", havingValue = "native")
public class JavaNativeCodeSandbox extends JavaCodeSandboxTemplate {

    @Override
    public ExecuteCodeResponse executeCode(ExecuteCodeRequest executeCodeRequest) {
        return super.executeCode(executeCodeRequest);
    }
}
