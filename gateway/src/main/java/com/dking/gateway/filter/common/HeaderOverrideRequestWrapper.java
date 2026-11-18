package com.dking.gateway.filter.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 可以增删请求头的包装器。
 *
 * <p>网关要把「网关算出来的身份」传给下游，同时必须让客户端自己塞的同名头失效
 * ——否则客户端伪造一个 X-User-Id 就能冒充别人。</p>
 *
 * <p>约定：{@code value == null} 表示删除该头。</p>
 */
public final class HeaderOverrideRequestWrapper extends HttpServletRequestWrapper {

    private final Map<String, String> overrides = new LinkedHashMap<>();

    public HeaderOverrideRequestWrapper(HttpServletRequest request, Map<String, String> headers) {
        super(request);
        if (headers != null) {
            headers.forEach((name, value) -> overrides.put(name.toLowerCase(), value));
        }
    }

    @Override
    public String getHeader(String name) {
        String key = name.toLowerCase();
        return overrides.containsKey(key) ? overrides.get(key) : super.getHeader(name);
    }

    @Override
    public Enumeration<String> getHeaders(String name) {
        String key = name.toLowerCase();
        if (!overrides.containsKey(key)) {
            return super.getHeaders(name);
        }
        String value = overrides.get(key);
        return value == null ? Collections.emptyEnumeration() : Collections.enumeration(java.util.List.of(value));
    }

    @Override
    public Enumeration<String> getHeaderNames() {
        Set<String> names = new LinkedHashSet<>();
        Enumeration<String> originals = super.getHeaderNames();
        while (originals != null && originals.hasMoreElements()) {
            String name = originals.nextElement();
            if (!overrides.containsKey(name.toLowerCase())) {
                names.add(name);
            }
        }
        overrides.forEach((name, value) -> {
            if (value != null) {
                names.add(name);
            }
        });
        return Collections.enumeration(names);
    }
}
