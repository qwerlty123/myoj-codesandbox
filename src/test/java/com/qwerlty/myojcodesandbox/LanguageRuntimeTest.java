package com.qwerlty.myojcodesandbox;

import com.qwerlty.myojcodesandbox.config.SandboxContainerProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LanguageRuntimeTest {

    @Test
    void mapsOnlySupportedLanguagesCaseInsensitively() {
        assertEquals(LanguageRuntime.JAVA, LanguageRuntime.from(" JAVA "));
        assertEquals(LanguageRuntime.CPP, LanguageRuntime.from("cpp"));
        assertEquals(LanguageRuntime.GO, LanguageRuntime.from("Go"));
        assertNull(LanguageRuntime.from("python"));
        assertNull(LanguageRuntime.from(null));
    }

    @Test
    void usesPinnedRuntimeImagesAndExpectedBuildArtifacts() {
        SandboxContainerProperties properties = new SandboxContainerProperties();

        assertEquals("eclipse-temurin:17-jdk", LanguageRuntime.JAVA.image(properties));
        assertEquals("gcc:13", LanguageRuntime.CPP.image(properties));
        assertEquals("golang:1.22", LanguageRuntime.GO.image(properties));
        assertEquals("Main.class", LanguageRuntime.JAVA.getArtifactFile());
        assertEquals("main", LanguageRuntime.CPP.getArtifactFile());
        assertEquals("main", LanguageRuntime.GO.getArtifactFile());
    }

    @Test
    void runCommandsReadOnlyTheRequestedCaseInput() {
        String javaCommand = LanguageRuntime.JAVA.runCommand(262_144, 65_536, 7);

        assertTrue(javaCommand.contains("-Xmx224m"));
        assertTrue(javaCommand.endsWith("< /workspace/input-7.txt"));
        assertEquals("/workspace/main < /workspace/input-2.txt",
                LanguageRuntime.CPP.runCommand(262_144, 65_536, 2));
        assertEquals("/workspace/main < /workspace/input-3.txt",
                LanguageRuntime.GO.runCommand(262_144, 65_536, 3));
    }
}
