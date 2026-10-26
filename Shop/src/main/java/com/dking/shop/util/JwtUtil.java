package com.dking.shop.util;

import com.dking.shop.entity.DoroUser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;


public class JwtUtil {

    public static final String SECRET = "doroshop-jwt-secret-at-least-32-chars!!";
    private static final long EXPIRATION = 24*60*60*1000;   // 过期时间，之后要放入yaml中

    public static String createAccessToken(DoroUser doroUser) {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        return Jwts.builder()
                .subject(String.valueOf(doroUser.getUserId()))
                .claim("username", doroUser.getUserName())
                .claim("role", doroUser.getRole())
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + EXPIRATION))
                .signWith(key)
                .compact();
    }

    public static String createRefreshToken(DoroUser doroUser) {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        return Jwts.builder()
                .subject(String.valueOf(doroUser.getUserId()))
                .claim("username", doroUser.getUserName())
                .claim("role", doroUser.getRole())
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + EXPIRATION))
                .signWith(key)
                .compact();
    }
}
