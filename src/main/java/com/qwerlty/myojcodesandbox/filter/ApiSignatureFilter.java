package com.qwerlty.myojcodesandbox.filter;

import com.qwerlty.myojcodesandbox.security.ApiSignUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import javax.servlet.*;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * 对 /executeCode 请求进行 API 签名验证，防止未授权调用与重放
 */
@Component
@Order(1)
public class ApiSignatureFilter implements Filter {

    public static final String HEADER_TIMESTAMP = "X-Timestamp";
    public static final String HEADER_SIGNATURE = "X-Signature";
    private static final String PATH_EXECUTE = "/executeCode";

    @Value("${codesandbox.auth.secretKey:}")
    private String secretKey;

    @Value("${codesandbox.auth.timestampToleranceSeconds:300}")
    private long timestampToleranceSeconds;

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse resp = (HttpServletResponse) response;

        if (!PATH_EXECUTE.equals(req.getRequestURI()) || !"POST".equalsIgnoreCase(req.getMethod())) {
            chain.doFilter(request, response);
            return;
        }

        if (secretKey == null || secretKey.trim().isEmpty()) {
            resp.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return;
        }

        CachingRequestBodyWrapper wrapper;
        try {
            wrapper = new CachingRequestBodyWrapper(req);
        } catch (IOException e) {
            resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return;
        }

        String timestampStr = wrapper.getHeader(HEADER_TIMESTAMP);
        String signature = wrapper.getHeader(HEADER_SIGNATURE);
        if (timestampStr == null || timestampStr.trim().isEmpty() || signature == null || signature.trim().isEmpty()) {
            resp.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return;
        }

        long timestamp;
        try {
            timestamp = Long.parseLong(timestampStr.trim());
        } catch (NumberFormatException e) {
            resp.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return;
        }

        long toleranceMs = timestampToleranceSeconds * 1000L;
        long now = System.currentTimeMillis();
        if (Math.abs(now - timestamp) > toleranceMs) {
            resp.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return;
        }

        String body = wrapper.getBodyAsString();
        if (!ApiSignUtil.verify(secretKey, timestamp, body, signature)) {
            resp.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return;
        }

        chain.doFilter(wrapper, response);
    }
}
