package com.qwerlty.myojcodesandbox.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExecuteCodeRequest {
    /**
     * 输入用例
     */
    private List<String> inputList;

    /**
     * 执行的代码
     */
    private String code;

    /**
     * 编程语言
     */
    private String language;

    /**
     * 可选执行限制。为空时由沙箱使用服务端默认值；客户端不能突破服务端硬上限。
     */
    private ExecutionProfile executionProfile;
}
