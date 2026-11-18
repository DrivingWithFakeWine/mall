package com.dking.gateway.filter.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 免登录路径配置。格式："METHOD /path/pattern"，pattern 只支持精确匹配和 {@code /**} 后缀。
 *
 * <p>这份清单必须和后端 SecurityConfig 里的 permitAll 保持一致，
 * 否则会出现「网关放行、后端 401」或「网关拦了、后端本来允许」。</p>
 *
 * <p>注意：公开路径在 AuthFilter 里仍会尝试解析 token（软认证），
 * 只是解析失败不会 401——目的是让限流能按用户而不是按 IP 计数。</p>
 */
@Component
@ConfigurationProperties(prefix = "app.auth")
public class AuthProperties {

    private List<String> permit = new ArrayList<>();

    public List<String> getPermit() {
        return permit;
    }

    public void setPermit(List<String> permit) {
        this.permit = permit;
    }
}
