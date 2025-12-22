import java.io.File;
import java.security.Permission;

/**
 * 子进程用安全管理器（默认包，供 -cp resources/security 加载）。
 * 通过 -Dallowed.read.path=目录 指定允许读的根目录，禁止执行、写、删、网络。
 */
public class MySecurityManager extends SecurityManager {

    private static final String ALLOWED_READ_PATH_PROP = "allowed.read.path";

    @Override
    public void checkPermission(Permission perm) {
        // 默认不限制，仅对下面具体 check 方法做限制
    }

    @Override
    public void checkExec(String cmd) {
        throw new SecurityException("禁止执行外部命令: " + cmd);
    }

    @Override
    public void checkRead(String file) {
        if (file == null) return;
        String allowed = System.getProperty(ALLOWED_READ_PATH_PROP);
        if (allowed != null && !allowed.isEmpty()) {
            String normalized = file.replace('/', File.separatorChar).replace('\\', File.separatorChar);
            String allowedNorm = allowed.replace('/', File.separatorChar).replace('\\', File.separatorChar);
            if (normalized.startsWith(allowedNorm)) return;
        }
        throw new SecurityException("禁止读取该路径: " + file);
    }

    @Override
    public void checkWrite(String file) {
        throw new SecurityException("禁止写文件: " + file);
    }

    @Override
    public void checkDelete(String file) {
        throw new SecurityException("禁止删除文件: " + file);
    }

    @Override
    public void checkConnect(String host, int port) {
        throw new SecurityException("禁止网络连接: " + host + ":" + port);
    }
}
