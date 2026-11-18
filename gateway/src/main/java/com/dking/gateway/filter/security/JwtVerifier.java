package com.dking.gateway.filter.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;

/**
 * JWT 校验器（HS256 共享密钥）。
 *
 * <p>和用户服务（Shop）用同一个密钥：网关只做校验、不签发。
 * 拆成微服务后密钥只存在网关和用户服务两处，业务服务完全不碰 token。</p>
 */
@Component
public class JwtVerifier {

    private final SecretKey key;

    public JwtVerifier(@Value("${app.jwt.secret}") String secret) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException("app.jwt.secret 至少需要 32 字节（256 位）");
        }
        this.key = Keys.hmacShaKeyFor(bytes);
    }

    /**
     * 验签 + 校验过期时间。任何不合法都会抛异常。
     *
     * @throws io.jsonwebtoken.JwtException 签名不对、过期、格式错误
     */
    public Claims verify(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
