package com.qwerlty.myojcodesandbox;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DockerContainerCommandTest {

    @Test
    void preservesTheRuntimeImagePathInsteadOfStartingALoginShell() {
        assertArrayEquals(new String[]{"sh", "-c", "go version"},
                DockerCodeSandbox.containerCommand("go version"));
    }

    @Test
    void distinguishesAMissingCompilerFromInvalidUserCode() {
        assertTrue(DockerCodeSandbox.isMissingRuntimeCommand(
                127, "sh: 1: go: not found", ""));
        assertFalse(DockerCodeSandbox.isMissingRuntimeCommand(
                1, "Main.go: syntax error", ""));
    }
}
