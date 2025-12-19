package com.qwerlty.myojcodesandbox;


import com.qwerlty.myojcodesandbox.model.ExecuteCodeRequest;
import com.qwerlty.myojcodesandbox.model.ExecuteCodeResponse;

public interface CodeSandbox {

    ExecuteCodeResponse executeCode(ExecuteCodeRequest executeCodeRequest);

}
