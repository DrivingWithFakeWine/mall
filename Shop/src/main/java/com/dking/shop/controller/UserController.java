package com.dking.shop.controller;

import com.dking.shop.common.Result;
import com.dking.shop.dto.LoginDTO;
import com.dking.shop.dto.RegisterDTO;
import com.dking.shop.entity.DoroUser;
import com.dking.shop.service.DoroUserService;
import com.dking.shop.vo.LoginVO;
import com.dking.shop.vo.UserVO;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/user")
public class UserController {
    @Resource
    private DoroUserService doroUserService;

    @PostMapping("/register")
    public Result<Void> register(@RequestBody @Valid RegisterDTO dto) {
        doroUserService.register(dto);
        return Result.success();
    }

    @PostMapping("/login")
    public Result<LoginVO> login(@RequestBody @Valid LoginDTO dto) {
        return Result.success(doroUserService.login(dto));
    }

    @GetMapping("/me")
    public Result<UserVO> me(@AuthenticationPrincipal Jwt jwt) {
        Long userId = Long.valueOf(jwt.getSubject());
        return Result.success(doroUserService.getUserById(userId));
    }
}
