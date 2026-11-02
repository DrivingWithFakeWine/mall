package com.dking.shop.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.dking.shop.entity.DoroUser;
import com.dking.shop.mapper.DoroUserMapper;
import com.dking.shop.util.JwtProvider;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"local", "test"})
@Transactional
class UserControllerIntegrationTest {

    @Resource
    private MockMvc mockMvc;

    @Resource
    private DoroUserMapper doroUserMapper;

    @Resource
    private PasswordEncoder passwordEncoder;

    @Resource
    private JwtProvider jwtProvider;

    @Test
    void register_success() throws Exception {
        String username = uniqueUsername();
        String telephone = uniquePhone();

        mockMvc.perform(post("/user/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"nickname\":\"测试用户\","
                                + "\"password\":\"123456\",\"telephone\":\"" + telephone + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("success"));

        assertEquals(1, countByUsername(username));
    }

    @Test
    void register_invalidUsername_shouldReturnValidationError() throws Exception {
        mockMvc.perform(post("/user/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"1abc\",\"nickname\":\"测试用户\","
                                + "\"password\":\"123456\",\"telephone\":\"" + uniquePhone() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("用户名为4-16位，字母开头"));
    }

    @Test
    void register_duplicateUsername_shouldReturnBusinessError() throws Exception {
        DoroUser user = createUser("123456");

        mockMvc.perform(post("/user/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + user.getUserName() + "\",\"nickname\":\"测试用户\","
                                + "\"password\":\"123456\",\"telephone\":\"" + uniquePhone() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("用户名已经存在"));
    }

    @Test
    void login_success_shouldReturnToken() throws Exception {
        DoroUser user = createUser("123456");

        mockMvc.perform(post("/user/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + user.getUserName() + "\",\"password\":\"123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.data.userId").value(user.getUserId()));
    }

    @Test
    void login_wrongPassword_shouldReturnBusinessError() throws Exception {
        DoroUser user = createUser("123456");

        mockMvc.perform(post("/user/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + user.getUserName() + "\",\"password\":\"654321\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("用户名或密码错误"));
    }

    @Test
    void me_withoutToken_shouldReturn401() throws Exception {
        mockMvc.perform(get("/user/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void me_withToken_shouldReturnCurrentUser() throws Exception {
        DoroUser user = createUser("123456");

        mockMvc.perform(get("/user/me")
                        .header("Authorization", "Bearer " + jwtProvider.createAccessToken(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.username").value(user.getUserName()));
    }

    private DoroUser createUser(String rawPassword) {
        DoroUser user = new DoroUser();
        user.setUserName(uniqueUsername());
        user.setNickName("测试用户");
        user.setPassword(passwordEncoder.encode(rawPassword));
        user.setTelephone(uniquePhone());
        user.setRole(0);
        user.setStatus(1);
        doroUserMapper.insert(user);
        return user;
    }

    private int countByUsername(String username) {
        return doroUserMapper.selectCount(new LambdaQueryWrapper<DoroUser>()
                .eq(DoroUser::getUserName, username)).intValue();
    }

    private String uniqueUsername() {
        return "u" + Long.toString(IdWorker.getId(), 36);
    }

    private String uniquePhone() {
        return "13" + String.format("%09d", Math.floorMod(IdWorker.getId(), 1_000_000_000L));
    }
}
