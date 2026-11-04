package com.dking.shop.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.dking.shop.dto.LoginDTO;
import com.dking.shop.dto.PasswordChangeDTO;
import com.dking.shop.dto.ProfileUpdateDTO;
import com.dking.shop.dto.RegisterDTO;
import com.dking.shop.entity.DoroUser;
import com.dking.shop.exception.BusinessException;
import com.dking.shop.mapper.DoroUserMapper;
import com.dking.shop.service.DoroUserService;
import com.dking.shop.util.JwtProvider;
import com.dking.shop.vo.LoginVO;
import com.dking.shop.vo.UserVO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
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
    @Resource
    private JwtProvider jwtProvider;


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
        // 空手机号存 NULL：uk_telephone 允许多个 NULL，但只允许一个空字符串
        String telephone = dto.getTelephone() == null ? null : dto.getTelephone().trim();
        doroUser.setTelephone(telephone == null || telephone.isEmpty() ? null : telephone);
        doroUser.setPassword(passwordEncoder.encode(dto.getPassword()));
        doroUser.setRole(0);  // 0 普通用户 / 1 管理员
        doroUser.setStatus(1);  // 1 正常 / 0 封禁
        try {
            doroUserMapper.insert(doroUser);
        } catch (DuplicateKeyException e) {
            // 并发注册时预检查会漏看，唯一索引是最终防线
            throw new BusinessException("用户名或手机号已被注册");
        }
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
        if (user.getStatus() == 0) {
            throw new BusinessException("账号已被封禁");
        }

        String accessToken = jwtProvider.createAccessToken(user);
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
        user.setTelephone(doroUser.getTelephone());
        user.setAvatar(doroUser.getAvatar());
        return user;
    }

    @Override
    public UserVO updateProfile(Long userId, ProfileUpdateDTO dto) {
        DoroUser user = doroUserMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }

        // 空手机号统一存 NULL：uk_telephone 允许多个 NULL，但只允许一个空字符串
        String telephone = dto.getTelephone() == null ? null : dto.getTelephone().trim();
        if (telephone != null && telephone.isEmpty()) {
            telephone = null;
        }

        try {
            // 用 Wrapper 显式 set，才能把手机号清成 NULL
            doroUserMapper.update(null, new LambdaUpdateWrapper<DoroUser>()
                    .eq(DoroUser::getUserId, userId)
                    .set(DoroUser::getNickName, dto.getNickname())
                    .set(DoroUser::getTelephone, telephone));
        } catch (DuplicateKeyException e) {
            throw new BusinessException("手机号已被使用");
        }

//        log.info("用户资料更新, userId={}", userId);
        return getUserById(userId);
    }

    @Override
    public void changePassword(Long userId, PasswordChangeDTO dto) {
        DoroUser user = doroUserMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException("用户不存在");
        }
        if (!passwordEncoder.matches(dto.getOldPassword(), user.getPassword())) {
            log.warn("修改密码失败, userId={}, 原因: 旧密码不正确", userId);
            throw new BusinessException("旧密码不正确");
        }

        DoroUser update = new DoroUser();
        update.setUserId(userId);
        update.setPassword(passwordEncoder.encode(dto.getNewPassword()));
        doroUserMapper.updateById(update);

        log.info("用户修改密码成功, userId={}", userId);
    }
}
