package com.qwerlty.myojcodesandbox.security;

import cn.hutool.dfa.FoundWord;
import cn.hutool.dfa.WordTree;
import cn.hutool.core.io.resource.ResourceUtil;
import cn.hutool.core.util.StrUtil;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.ArrayList;

/**
 * 违禁词检查器：基于 DFA 字典树检测用户代码中的危险/违禁 API
 */
public class ForbiddenWordChecker {

    private static final WordTree WORD_TREE;
    /** 默认 Java 危险 API/关键字黑名单 */
    private static final List<String> DEFAULT_BLACKLIST = Arrays.asList(
            "Runtime.getRuntime",
            "Runtime",
            "ProcessBuilder",
            "Process",
            "exec(",
            "exec ",
            "System.exit",
            "File(",
            "FileInputStream",
            "FileOutputStream",
            "FileReader",
            "FileWriter",
            "Files.",
            "Paths.get",
            "Path ",
            "Path.",
            "java.io.File",
            "java.nio.file.Files",
            "java.nio.file.Path",
            "reflect.",
            "Method.invoke",
            "Class.forName",
            "URLClassLoader",
            "ScriptEngineManager",
            "javax.script",
            "Native",
            "Unsafe",
            "sun.misc",
            "java.lang.reflect",
            "getDeclaredMethod",
            "setAccessible",
            "loadLibrary",
            "Runtime.getRuntime().exec",
            "ProcessBuilder(",
            "Desktop.getDesktop",
            "Desktop",
            "Socket(",
            "ServerSocket",
            "Socket.",
            "DatagramSocket",
            "URL.openConnection",
            "HttpURLConnection",
            "java.net",
            "javax.net",
            "exec",
            "destroyForcibly",
            "System.setSecurityManager",
            "setSecurityManager",
            "Thread.stop",
            ".stop(",
            "Thread.destroy",
            "ReflectionFactory",
            "ObjectInputStream",
            "ObjectOutputStream",
            "Serializable",
            "JNDI",
            "InitialContext",
            "Naming.lookup",
            "javax.naming",
            "ScriptEngine",
            "eval(",
            "compile(",
            "javax.tools.ToolProvider",
            "ToolProvider.getSystemJavaCompiler",
            "Compiler",
            "Compiler.compileClass"
    );

    static {
        WORD_TREE = new WordTree();
        List<String> words = loadBlacklist();
        WORD_TREE.addWords(words);
    }

    private static List<String> loadBlacklist() {
        try {
            String content = ResourceUtil.readStr("forbidden-words.txt", StandardCharsets.UTF_8);
            if (StrUtil.isNotBlank(content)) {
                List<String> lines = new ArrayList<>();
                for (String line : content.split("[\\r\\n]+")) {
                    String t = line.trim();
                    if (StrUtil.isNotBlank(t) && !t.startsWith("#")) {
                        lines.add(t);
                    }
                }
                if (!lines.isEmpty()) {
                    return lines;
                }
            }
        } catch (Exception ignored) {
            // 无配置文件或读取失败则使用默认
        }
        return DEFAULT_BLACKLIST;
    }

    /**
     * 检查代码是否包含违禁词
     *
     * @param code 用户代码
     * @return 若包含违禁词返回违禁词内容，否则返回 null
     */
    public static String check(String code) {
        if (StrUtil.isBlank(code)) {
            return null;
        }
        FoundWord found = WORD_TREE.matchWord(code);
        return found != null ? found.getFoundWord() : null;
    }

    /**
     * 是否通过违禁词检查
     */
    public static boolean pass(String code) {
        return check(code) == null;
    }
}
