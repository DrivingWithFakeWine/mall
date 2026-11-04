package com.dking.shop.service;

import com.dking.shop.dto.LoginDTO;
import com.dking.shop.dto.PasswordChangeDTO;
import com.dking.shop.dto.ProfileUpdateDTO;
import com.dking.shop.dto.RegisterDTO;
import com.dking.shop.entity.DoroUser;
import com.dking.shop.vo.LoginVO;
import com.dking.shop.vo.UserVO;

public interface DoroUserService {
    void register(RegisterDTO dto);

    LoginVO login(LoginDTO dto);

    UserVO getUserById(Long userId);

    /** 修改昵称与手机号，返回最新资料；手机号冲突抛业务异常 */
    UserVO updateProfile(Long userId, ProfileUpdateDTO dto);

    /** 校验旧密码后修改密码 */
    void changePassword(Long userId, PasswordChangeDTO dto);
}
