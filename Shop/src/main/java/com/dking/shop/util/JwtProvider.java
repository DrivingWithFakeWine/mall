package com.dking.shop.util;

import com.dking.shop.entity.DoroUser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 签发器。密钥与有效期从配置读取（环境变量 JWT_SECRET / JWT_EXPIRATION_MS），
 * 不再硬编码在代码里。
 */
@Component
public class JwtProvider {

    private final SecretKey key;
    private final long expirationMs;

    public JwtProvider(@Value("${app.jwt.secret}") String secret,
                       @Value("${app.jwt.expiration-ms:86400000}") long expirationMs) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException("app.jwt.secret 至少需要 32 字节（256 位）");
        }
        this.key = Keys.hmacShaKeyFor(bytes);
        this.expirationMs = expirationMs;
    }

    public SecretKey key() {
        return key;
    }

    public String createAccessToken(DoroUser user) {
        return Jwts.builder()
                .subject(String.valueOf(user.getUserId()))
                .claim("username", user.getUserName())
                .claim("role", user.getRole())
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expirationMs))
                .signWith(key)
                .compact();
    }
}
