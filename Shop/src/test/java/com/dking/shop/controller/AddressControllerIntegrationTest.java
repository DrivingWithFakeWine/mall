package com.dking.shop.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.dking.shop.entity.DoroUser;
import com.dking.shop.entity.UserAddress;
import com.dking.shop.mapper.DoroUserMapper;
import com.dking.shop.mapper.UserAddressMapper;
import com.dking.shop.util.JwtProvider;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"local", "test"})
@Transactional
class AddressControllerIntegrationTest {

    @Resource
    private MockMvc mockMvc;

    @Resource
    private DoroUserMapper doroUserMapper;

    @Resource
    private UserAddressMapper userAddressMapper;

    @Resource
    private JwtProvider jwtProvider;

    @Test
    void add_withoutToken_shouldReturn401() throws Exception {
        mockMvc.perform(post("/address/add")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validAddressJson(1)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void add_invalidPhone_shouldReturnValidationError() throws Exception {
        DoroUser user = createUser();

        mockMvc.perform(post("/address/add")
                        .header("Authorization", "Bearer " + token(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"receiverName\":\"张三\",\"receiverPhone\":\"123\","
                                + "\"province\":\"北京市\",\"city\":\"北京市\",\"district\":\"海淀区\","
                                + "\"detail\":\"测试路1号\",\"isDefault\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("手机号格式不正确"));
    }

    @Test
    void add_firstAddress_shouldBecomeDefault() throws Exception {
        DoroUser user = createUser();

        mockMvc.perform(post("/address/add")
                        .header("Authorization", "Bearer " + token(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validAddressJson(0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        Long count = userAddressMapper.selectCount(new LambdaQueryWrapper<UserAddress>()
                .eq(UserAddress::getUserId, user.getUserId()));
        assertEquals(1, count.intValue());

        UserAddress address = userAddressMapper.selectOne(new LambdaQueryWrapper<UserAddress>()
                .eq(UserAddress::getUserId, user.getUserId()));
        assertEquals(1, address.getIsDefault());
    }

    @Test
    void list_shouldReturnAddresses() throws Exception {
        DoroUser user = createUser();
        String token = token(user);

        mockMvc.perform(post("/address/add")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validAddressJson(1)))
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(get("/address/list")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].isDefault").value(1));
    }

    @Test
    void update_otherUsersAddress_shouldReturnNotFound() throws Exception {
        DoroUser user = createUser();
        DoroUser other = createUser();
        UserAddress otherAddress = createAddress(other.getUserId());

        mockMvc.perform(put("/address/" + otherAddress.getId())
                        .header("Authorization", "Bearer " + token(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validAddressJson(1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value("地址不存在"));
    }

    @Test
    void remove_otherUsersAddress_shouldReturnNotFound() throws Exception {
        DoroUser user = createUser();
        DoroUser other = createUser();
        UserAddress otherAddress = createAddress(other.getUserId());

        mockMvc.perform(delete("/address/" + otherAddress.getId())
                        .header("Authorization", "Bearer " + token(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value("地址不存在"));
    }

    private DoroUser createUser() {
        DoroUser user = new DoroUser();
        user.setUserName("u" + Long.toString(IdWorker.getId(), 36));
        user.setNickName("测试用户");
        user.setPassword("irrelevant");
        user.setTelephone("13" + String.format("%09d",
                Math.floorMod(IdWorker.getId(), 1_000_000_000L)));
        user.setRole(0);
        user.setStatus(1);
        doroUserMapper.insert(user);
        return user;
    }

    private UserAddress createAddress(Long userId) {
        UserAddress address = new UserAddress();
        address.setUserId(userId);
        address.setReceiverName("张三");
        address.setReceiverPhone("13800000000");
        address.setProvince("北京市");
        address.setCity("北京市");
        address.setDistrict("海淀区");
        address.setDetail("测试路1号");
        address.setIsDefault(0);
        userAddressMapper.insert(address);
        return address;
    }

    private String validAddressJson(int isDefault) {
        return "{\"receiverName\":\"张三\",\"receiverPhone\":\"13800000000\","
                + "\"province\":\"北京市\",\"city\":\"北京市\",\"district\":\"海淀区\","
                + "\"detail\":\"测试路1号\",\"isDefault\":" + isDefault + "}";
    }

    private String token(DoroUser user) {
        return jwtProvider.createAccessToken(user);
    }
}
