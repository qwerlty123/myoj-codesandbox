package com.qwerlty.myojcodesandbox.model;

import lombok.Data;

@Data
public class ExecutionProfile {

    /** 普通判题或 AI 生成验证。 */
    private String purpose;

    private Long timeLimitMs;

    private Long memoryLimitKb;

    private Long stackLimitKb;

    private Integer outputLimitBytes;
}
