package com.qwerlty.myojcodesandbox;

import com.qwerlty.myojcodesandbox.config.SandboxContainerProperties;

import java.util.Locale;

enum LanguageRuntime {
    JAVA("java", "Main.java", "Main.class"),
    CPP("cpp", "Main.cpp", "main"),
    GO("go", "Main.go", "main");

    private final String language;
    private final String sourceFile;
    private final String artifactFile;

    LanguageRuntime(String language, String sourceFile, String artifactFile) {
        this.language = language;
        this.sourceFile = sourceFile;
        this.artifactFile = artifactFile;
    }

    static LanguageRuntime from(String language) {
        if (language == null) {
            return null;
        }
        String normalized = language.trim().toLowerCase(Locale.ROOT);
        for (LanguageRuntime runtime : values()) {
            if (runtime.language.equals(normalized)) {
                return runtime;
            }
        }
        return null;
    }

    String image(SandboxContainerProperties properties) {
        switch (this) {
            case JAVA:
                return properties.getJavaImage();
            case CPP:
                return properties.getCppImage();
            case GO:
                return properties.getGoImage();
            default:
                throw new IllegalStateException("未知语言");
        }
    }

    String compileCommand() {
        switch (this) {
            case JAVA:
                return "javac -encoding UTF-8 /workspace/Main.java";
            case CPP:
                return "g++ -std=c++17 -O2 -pipe /workspace/Main.cpp -o /workspace/main";
            case GO:
                return "go build -trimpath -o /workspace/main /workspace/Main.go";
            default:
                throw new IllegalStateException("未知语言");
        }
    }

    String runCommand(long memoryLimitKb, long stackLimitKb, int caseIndex) {
        String input = "/workspace/input-" + caseIndex + ".txt";
        switch (this) {
            case JAVA:
                long heapMb = Math.max(16L, memoryLimitKb / 1024L - 32L);
                return "java -Xmx" + heapMb + "m -Xss" + Math.max(256L, stackLimitKb) +
                        "k -Dfile.encoding=UTF-8 -cp /workspace Main < " + input;
            case CPP:
            case GO:
                return "/workspace/main < " + input;
            default:
                throw new IllegalStateException("未知语言");
        }
    }

    String getSourceFile() {
        return sourceFile;
    }

    String getArtifactFile() {
        return artifactFile;
    }
}
