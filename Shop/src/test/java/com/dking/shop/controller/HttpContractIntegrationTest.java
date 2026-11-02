package com.dking.shop.controller;

import com.dking.shop.entity.DoroUser;
import com.dking.shop.util.JwtProvider;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP 契约：路由、方法、白名单，以及框架异常映射为正确的 HTTP 状态。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"local", "test"})
@Transactional
class HttpContractIntegrationTest {

    @Resource
    private MockMvc mockMvc;

    @Resource
    private JwtProvider jwtProvider;

    @Test
    void unknownPath_withToken_shouldReturn404() throws Exception {
        mockMvc.perform(get("/no-such-path")
                        .header("Authorization", "Bearer " + anyUserToken()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value("资源不存在"));
    }

    @Test
    void wrongMethod_shouldReturn405() throws Exception {
        mockMvc.perform(get("/user/register"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value(405))
                .andExpect(jsonPath("$.message").value("请求方法不支持"));
    }

    @Test
    void malformedJson_shouldReturn400() throws Exception {
        mockMvc.perform(post("/user/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("not-a-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("请求参数错误"));
    }

    @Test
    void unsupportedContentType_shouldReturn415() throws Exception {
        mockMvc.perform(post("/user/register")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("plain text"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value(415))
                .andExpect(jsonPath("$.message").value("不支持的 Content-Type"));
    }

    @Test
    void pathVariableTypeMismatch_shouldReturn400() throws Exception {
        mockMvc.perform(get("/order/abc")
                        .header("Authorization", "Bearer " + anyUserToken()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void productList_withoutToken_shouldBePublic() throws Exception {
        mockMvc.perform(get("/product/list"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void categoryTree_withoutToken_shouldBePublic() throws Exception {
        mockMvc.perform(get("/category/tree"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    private String anyUserToken() {
        DoroUser user = new DoroUser();
        user.setUserId(1L);
        user.setUserName("anonymous");
        user.setRole(0);
        return jwtProvider.createAccessToken(user);
    }
}
