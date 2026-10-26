package com.dking.shop.service;

import com.dking.shop.dto.LoginDTO;
import com.dking.shop.dto.RegisterDTO;
import com.dking.shop.entity.DoroUser;
import com.dking.shop.vo.LoginVO;
import com.dking.shop.vo.UserVO;

public interface DoroUserService {
    void register(RegisterDTO dto);

    LoginVO login(LoginDTO dto);

    UserVO getUserById(Long userId);
}
