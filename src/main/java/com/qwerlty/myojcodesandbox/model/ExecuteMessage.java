package com.qwerlty.myojcodesandbox.model;

import lombok.Data;

/**
 * 进程执行信息
 */
@Data
public class ExecuteMessage {

    private Integer exitValue;

    private String message;

    private String errorMessage;

    private Long time;

    private Long memory;

    /** 是否因为超过执行时间限制而被终止 */
    private Boolean timedOut;

    /** 标准输出或错误输出是否超过采集上限 */
    private Boolean outputLimitExceeded;
}
