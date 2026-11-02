package com.dking.shop.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.dking.shop.entity.Cart;
import com.dking.shop.entity.DoroUser;
import com.dking.shop.entity.Order;
import com.dking.shop.entity.ProductSku;
import com.dking.shop.entity.ProductSpu;
import com.dking.shop.entity.ProductStock;
import com.dking.shop.entity.UserAddress;
import com.dking.shop.mapper.CartMapper;
import com.dking.shop.mapper.DoroUserMapper;
import com.dking.shop.mapper.OrderMapper;
import com.dking.shop.mapper.ProductSkuMapper;
import com.dking.shop.mapper.ProductSpuMapper;
import com.dking.shop.mapper.ProductStockMapper;
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

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"local", "test"})
@Transactional
class OrderControllerIntegrationTest {

    @Resource
    private MockMvc mockMvc;

    @Resource
    private DoroUserMapper doroUserMapper;

    @Resource
    private CartMapper cartMapper;

    @Resource
    private OrderMapper orderMapper;

    @Resource
    private ProductSpuMapper productSpuMapper;

    @Resource
    private ProductSkuMapper productSkuMapper;

    @Resource
    private ProductStockMapper productStockMapper;

    @Resource
    private UserAddressMapper userAddressMapper;

    @Resource
    private JwtProvider jwtProvider;

    @Test
    void detail_notFound_shouldReturn404Code() throws Exception {
        DoroUser user = createUser();

        mockMvc.perform(get("/order/" + IdWorker.getId())
                        .header("Authorization", "Bearer " + token(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value("订单不存在"));
    }

    @Test
    void submit_missingAddressId_shouldReturnValidationError() throws Exception {
        DoroUser user = createUser();

        mockMvc.perform(post("/order/submit")
                        .header("Authorization", "Bearer " + token(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"remark\":\"测试\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("收货地址不能为空"));
    }

    @Test
    void submit_withoutSelectedCart_shouldReturnBusinessError() throws Exception {
        DoroUser user = createUser();
        UserAddress address = createAddress(user.getUserId());

        mockMvc.perform(post("/order/submit")
                        .header("Authorization", "Bearer " + token(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"addressId\":" + address.getId() + ",\"remark\":\"测试\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("请先选择要结算的商品"));
    }

    @Test
    void submit_success_shouldCreateOrderAndDeductStock() throws Exception {
        DoroUser user = createUser();
        UserAddress address = createAddress(user.getUserId());
        Long skuId = createProduct();
        createCart(user.getUserId(), skuId, 1);

        mockMvc.perform(post("/order/submit")
                        .header("Authorization", "Bearer " + token(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"addressId\":" + address.getId() + ",\"remark\":\"集成测试\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.orderNo").isNotEmpty());

        Long orderCount = orderMapper.selectCount(new LambdaQueryWrapper<Order>()
                .eq(Order::getUserId, user.getUserId()));
        assertEquals(1, orderCount.intValue());

        ProductStock stock = productStockMapper.selectOne(new LambdaQueryWrapper<ProductStock>()
                .eq(ProductStock::getSkuId, skuId));
        assertEquals(9, stock.getAvailableStock());
        assertEquals(1, stock.getLockedStock());

        Long cartCount = cartMapper.selectCount(new LambdaQueryWrapper<Cart>()
                .eq(Cart::getUserId, user.getUserId())
                .eq(Cart::getSkuId, skuId));
        assertEquals(0, cartCount.intValue());
    }

    @Test
    void pay_notFound_shouldReturn404Code() throws Exception {
        DoroUser user = createUser();

        mockMvc.perform(post("/order/" + IdWorker.getId() + "/pay")
                        .header("Authorization", "Bearer " + token(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value("订单不存在"));
    }

    @Test
    void cancel_notFound_shouldReturn404Code() throws Exception {
        DoroUser user = createUser();

        mockMvc.perform(post("/order/" + IdWorker.getId() + "/cancel")
                        .header("Authorization", "Bearer " + token(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value("订单不存在"));
    }

    @Test
    void list_shouldReturnPage() throws Exception {
        DoroUser user = createUser();

        mockMvc.perform(get("/order/list")
                        .header("Authorization", "Bearer " + token(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
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
        address.setIsDefault(1);
        userAddressMapper.insert(address);
        return address;
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
        stock.setAvailableStock(10);
        stock.setLockedStock(0);
        productStockMapper.insert(stock);
        return sku.getId();
    }

    private void createCart(Long userId, Long skuId, int quantity) {
        Cart cart = new Cart();
        cart.setUserId(userId);
        cart.setSkuId(skuId);
        cart.setQuantity(quantity);
        cart.setSelected(1);
        cartMapper.insert(cart);
    }

    private String token(DoroUser user) {
        return jwtProvider.createAccessToken(user);
    }
}
