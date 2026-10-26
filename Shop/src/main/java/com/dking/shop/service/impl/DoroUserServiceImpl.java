package com.dking.shop.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dking.shop.dto.LoginDTO;
import com.dking.shop.dto.RegisterDTO;
import com.dking.shop.entity.DoroUser;
import com.dking.shop.exception.BusinessException;
import com.dking.shop.mapper.DoroUserMapper;
import com.dking.shop.service.DoroUserService;
import com.dking.shop.util.JwtUtil;
import com.dking.shop.vo.LoginVO;
import com.dking.shop.vo.UserVO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Slf4j
@Service
public class DoroUserServiceImpl  implements DoroUserService {
    @Resource
    private DoroUserMapper doroUserMapper;
    @Resource
    private PasswordEncoder passwordEncoder;


    @Override
    public void register(RegisterDTO dto) {
        Long count = doroUserMapper.selectCount(
                new LambdaQueryWrapper<DoroUser>().eq(DoroUser::getUserName, dto.getUsername()));
        if (count > 0) {
            throw new BusinessException("用户名已经存在");
        }

        DoroUser doroUser = new DoroUser();
        doroUser.setUserName(dto.getUsername());
        doroUser.setNickName(dto.getNickname());
        doroUser.setTelephone(dto.getTelephone());
        doroUser.setPassword(passwordEncoder.encode(dto.getPassword()));
        doroUser.setRole(0);  // 非管理员
        doroUser.setStatus(0);  // 非封禁
        doroUserMapper.insert(doroUser);
        log.info("用户注册成功, userId={}, username={}", doroUser.getUserId(), doroUser.getUserName());
    }

    @Override
    public LoginVO login(LoginDTO dto) {
        DoroUser user = doroUserMapper.selectOne(
                new LambdaQueryWrapper<DoroUser>().eq(DoroUser::getUserName, dto.getUsername()));

        // 统一话术：不区分"用户不存在"和"密码错误"，防止别人撞库探测
        if (user == null || !passwordEncoder.matches(dto.getPassword(), user.getPassword())) {
            log.warn("登录失败, username={}, 原因: 用户名或密码错误", dto.getUsername());
            throw new BusinessException("用户名或密码错误");
        }
        if (user.getStatus() == 1) {
            throw new BusinessException("账号已被封禁");
        }

        String accessToken = JwtUtil.createAccessToken(user);
        log.info("用户登录成功, userId={}, username={}", user.getUserId(), user.getUserName());
        DoroUser update = new DoroUser();
        update.setUserId(user.getUserId());
        update.setLastLoginTime(LocalDateTime.now());
        doroUserMapper.updateById(update);                  // 只更新这一个字段

        return new LoginVO(accessToken, user.getUserId(), user.getNickName());
    }

    @Override
    public UserVO getUserById(Long userId) {
        DoroUser doroUser = doroUserMapper.selectById(userId);
        if (doroUser == null) throw new BusinessException("用户不存在");
        UserVO user = new UserVO();
        user.setId(doroUser.getUserId());
        user.setNickname(doroUser.getNickName());
        user.setUsername(doroUser.getUserName());
        user.setAvatar(doroUser.getAvatar());
        return user;
    }


}
