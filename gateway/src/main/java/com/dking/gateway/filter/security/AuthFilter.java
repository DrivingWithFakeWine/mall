package com.dking.gateway.filter.security;

import com.dking.gateway.filter.common.HeaderOverrideRequestWrapper;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 网关鉴权：校验 JWT，把身份解析成请求头传给下游。
 *
 * <p><b>公开路径也解析 token（软认证）。</b>公开接口不强制登录，但如果客户端带了合法 token，
 * 仍然会把身份注进去。原因：限流要按用户算，如果公开接口拿不到 userId，只能退回按 IP，
 * 一个 NAT 出口后面几百个用户会被一起限流。</p>
 *
 * <p><b>双保险。</b>网关照常把原始 Authorization 转发给后端，后端仍用自己的 Resource Server
 * 校验一遍。好处是网关可以随时下线而不影响业务；代价是多验一次签名（HS256 微秒级）。</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class AuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AuthFilter.class);

    public static final String USER_ID_HEADER = "X-User-Id";
    public static final String USER_ROLE_HEADER = "X-User-Role";
    public static final String USERNAME_HEADER = "X-Username";

    private static final String AUTH_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtVerifier verifier;
    private final List<PermitRule> rules;
    private final JsonMapper json = JsonMapper.builder().build();

    private record PermitRule(String method, String pattern) {
    }

    public AuthFilter(JwtVerifier verifier, AuthProperties properties) {
        this.verifier = verifier;
        this.rules = new ArrayList<>();
        for (String rule : properties.getPermit()) {
            int split = rule.indexOf(' ');
            if (split <= 0) {
                log.warn("忽略无法解析的放行规则: {}", rule);
                continue;
            }
            rules.add(new PermitRule(rule.substring(0, split).trim().toUpperCase(),
                    rule.substring(split + 1).trim()));
        }
        log.info("网关放行规则: {}", rules);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = pathOf(request);
        String method = request.getMethod();

        // CORS 预检不带 token，直接放行
        if ("OPTIONS".equalsIgnoreCase(method)) {
            chain.doFilter(withIdentity(request, Map.of()), response);
            return;
        }

        String token = resolveToken(request);

        if (isPermitted(method, path)) {
            // 公开路径：token 合法就带上身份，不合法就当匿名，绝不 401
            Map<String, String> identity = tryIdentity(token, path);
            chain.doFilter(withIdentity(request, identity), response);
            return;
        }

        if (token == null) {
            unauthorized(response, "未登录或登录已过期");
            return;
        }

        Claims claims;
        try {
            claims = verifier.verify(token);
        } catch (Exception e) {
            // 不暴露具体失败原因（签名错/过期/格式错），避免给攻击者信息
            log.info("JWT 校验失败, path={}, err={}", path, e.getClass().getSimpleName());
            unauthorized(response, "登录已过期，请重新登录");
            return;
        }

        log.info("认证通过, path={}, userId={}, role={}", path, claims.getSubject(), claims.get("role"));
        chain.doFilter(withIdentity(request, identityOf(claims)), response);
    }

    /**
     * 公开路径的软认证：解析成功就用身份，失败就当匿名。
     * 注意不能抛异常——token 过期不该让用户连商品都看不了。
     */
    private Map<String, String> tryIdentity(String token, String path) {
        if (token == null) {
            return Map.of();
        }
        try {
            return identityOf(verifier.verify(token));
        } catch (Exception e) {
            log.debug("公开路径 token 无效，按匿名处理, path={}, err={}", path, e.getClass().getSimpleName());
            return Map.of();
        }
    }

    private Map<String, String> identityOf(Claims claims) {
        Map<String, String> identity = new LinkedHashMap<>();
        if (claims.getSubject() != null) {
            identity.put(USER_ID_HEADER, claims.getSubject());
        }
        if (claims.get("role") != null) {
            identity.put(USER_ROLE_HEADER, String.valueOf(claims.get("role")));
        }
        if (claims.get("username") != null) {
            identity.put(USERNAME_HEADER, String.valueOf(claims.get("username")));
        }
        return identity;
    }

    /**
     * 用网关算出的身份覆盖请求头。
     * 没有身份的字段显式设为 null（包装器语义 = 删除），
     * 这样客户端伪造的 X-User-Id 永远不会透传到下游。
     */
    private HttpServletRequest withIdentity(HttpServletRequest request, Map<String, String> identity) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(USER_ID_HEADER, identity.get(USER_ID_HEADER));
        headers.put(USER_ROLE_HEADER, identity.get(USER_ROLE_HEADER));
        headers.put(USERNAME_HEADER, identity.get(USERNAME_HEADER));
        return new HeaderOverrideRequestWrapper(request, headers);
    }

    private boolean isPermitted(String method, String path) {
        for (PermitRule rule : rules) {
            if (!"*".equals(rule.method()) && !rule.method().equalsIgnoreCase(method)) {
                continue;
            }
            if (matches(rule.pattern(), path)) {
                return true;
            }
        }
        return false;
    }

    private boolean matches(String pattern, String path) {
        if (pattern.endsWith("/**")) {
            String prefix = pattern.substring(0, pattern.length() - 3);
            return path.equals(prefix) || path.startsWith(prefix + "/");
        }
        return pattern.equals(path);
    }

    private String resolveToken(HttpServletRequest request) {
        String header = request.getHeader(AUTH_HEADER);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return null;
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }

    private String pathOf(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String context = request.getContextPath();
        return context != null && !context.isEmpty() ? uri.substring(context.length()) : uri;
    }

    private void unauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", 401);
        body.put("message", message);
        body.put("data", null);
        response.getWriter().write(json.writeValueAsString(body));
    }
}
