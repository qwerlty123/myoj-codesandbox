package com.qwerlty.myojcodesandbox.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CaseExecutionResult {
    private Integer index;
    private Integer exitCode;
    private String output;
    private String error;
    private Long timeMs;
    private Boolean timedOut;
    private Boolean outputLimitExceeded;
}
