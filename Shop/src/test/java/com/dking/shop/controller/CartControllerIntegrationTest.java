package com.dking.shop.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.dking.shop.entity.Cart;
import com.dking.shop.entity.DoroUser;
import com.dking.shop.entity.ProductSku;
import com.dking.shop.entity.ProductSpu;
import com.dking.shop.entity.ProductStock;
import com.dking.shop.mapper.CartMapper;
import com.dking.shop.mapper.DoroUserMapper;
import com.dking.shop.mapper.ProductSkuMapper;
import com.dking.shop.mapper.ProductSpuMapper;
import com.dking.shop.mapper.ProductStockMapper;
import com.dking.shop.util.JwtProvider;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"local", "test"})
@Transactional
class CartControllerIntegrationTest {

    @Resource
    private MockMvc mockMvc;

    @Resource
    private DoroUserMapper doroUserMapper;

    @Resource
    private CartMapper cartMapper;

    @Resource
    private ProductSpuMapper productSpuMapper;

    @Resource
    private ProductSkuMapper productSkuMapper;

    @Resource
    private ProductStockMapper productStockMapper;

    @Resource
    private JwtProvider jwtProvider;

    @Test
    void add_withoutToken_shouldReturn401() throws Exception {
        mockMvc.perform(post("/cart/add")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"skuId\":1,\"quantity\":1}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void add_invalidQuantity_shouldReturnValidationError() throws Exception {
        DoroUser user = createUser();
        Long skuId = createProduct();

        mockMvc.perform(post("/cart/add")
                        .header("Authorization", "Bearer " + token(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"skuId\":" + skuId + ",\"quantity\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("数量至少为 1"));
    }

    @Test
    void add_success_shouldPersistCart() throws Exception {
        DoroUser user = createUser();
        Long skuId = createProduct();

        mockMvc.perform(post("/cart/add")
                        .header("Authorization", "Bearer " + token(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"skuId\":" + skuId + ",\"quantity\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        Cart cart = cartMapper.selectOne(new LambdaQueryWrapper<Cart>()
                .eq(Cart::getUserId, user.getUserId())
                .eq(Cart::getSkuId, skuId));
        assertEquals(1, cart.getQuantity());
    }

    @Test
    void list_afterAdd_shouldReturnItem() throws Exception {
        DoroUser user = createUser();
        Long skuId = createProduct();
        String token = token(user);

        mockMvc.perform(post("/cart/add")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"skuId\":" + skuId + ",\"quantity\":2}"))
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(get("/cart/list")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].skuId").value(skuId))
                .andExpect(jsonPath("$.data.items[0].quantity").value(2));
    }

    @Test
    void remove_otherUsersCart_shouldReturnNotFound() throws Exception {
        DoroUser user = createUser();
        DoroUser other = createUser();
        Long skuId = createProduct();

        Cart otherCart = new Cart();
        otherCart.setUserId(other.getUserId());
        otherCart.setSkuId(skuId);
        otherCart.setQuantity(1);
        otherCart.setSelected(1);
        cartMapper.insert(otherCart);

        mockMvc.perform(delete("/cart/" + otherCart.getId())
                        .header("Authorization", "Bearer " + token(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value("购物车项不存在"));
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

    private Long createProduct() {
        ProductSpu spu = new ProductSpu();
        spu.setCategoryId(1L);
        spu.setName("集成测试商品");
        spu.setCode("IT-SPU-" + IdWorker.getId());
        spu.setPriceMin(new BigDecimal("9.90"));
        spu.setStatus(1);
        productSpuMapper.insert(spu);

        ProductSku sku = new ProductSku();
        sku.setSpuId(spu.getId());
        sku.setSkuCode("IT-SKU-" + IdWorker.getId());
        sku.setSpecDesc("默认规格");
        sku.setPrice(new BigDecimal("9.90"));
        sku.setStatus(1);
        productSkuMapper.insert(sku);

        ProductStock stock = new ProductStock();
        stock.setSkuId(sku.getId());
        stock.setAvailableStock(100);
        stock.setLockedStock(0);
        productStockMapper.insert(stock);
        return sku.getId();
    }

    private String token(DoroUser user) {
        return jwtProvider.createAccessToken(user);
    }
}
