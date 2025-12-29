package com.qwerlty.myojcodesandbox.filter;

import cn.hutool.core.io.IoUtil;

import javax.servlet.ReadListener;
import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 包装请求以便多次读取 body，用于签名校验后仍可供 Controller 反序列化
 */
public class CachingRequestBodyWrapper extends HttpServletRequestWrapper {

    private final byte[] body;

    public CachingRequestBodyWrapper(HttpServletRequest request) throws IOException {
        super(request);
        InputStream is = request.getInputStream();
        this.body = IoUtil.readBytes(is);
    }

    @Override
    public ServletInputStream getInputStream() {
        return new ByteArrayServletInputStream(new ByteArrayInputStream(body));
    }

    public byte[] getBodyBytes() {
        return body;
    }

    public String getBodyAsString() {
        return new String(body, StandardCharsets.UTF_8);
    }

    private static class ByteArrayServletInputStream extends ServletInputStream {
        private final ByteArrayInputStream delegate;

        ByteArrayServletInputStream(ByteArrayInputStream delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean isFinished() {
            return delegate.available() == 0;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int read() {
            return delegate.read();
        }
    }
}
