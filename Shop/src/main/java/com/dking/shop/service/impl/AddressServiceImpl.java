package com.dking.shop.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.dking.shop.dto.AddressSaveDTO;
import com.dking.shop.entity.UserAddress;
import com.dking.shop.exception.BusinessException;
import com.dking.shop.exception.NotFoundException;
import com.dking.shop.mapper.UserAddressMapper;
import com.dking.shop.service.AddressService;
import com.dking.shop.vo.UserAddressVO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
public class AddressServiceImpl implements AddressService {

    private static final int MAX_ADDRESS_COUNT = 20;
    private static final Integer DEFAULT_YES = 1;
    private static final Integer DEFAULT_NO = 0;

    @Resource
    private UserAddressMapper userAddressMapper;

    @Override
    public List<UserAddressVO> list(Long userId) {
        List<UserAddress> addresses = userAddressMapper.selectList(
                new LambdaQueryWrapper<UserAddress>()
                        .eq(UserAddress::getUserId, userId)
                        // 默认地址在前，其余按创建时间倒序
                        .orderByDesc(UserAddress::getIsDefault)
                        .orderByDesc(UserAddress::getCreateTime)
                        .orderByDesc(UserAddress::getId));

        log.info("查询收货地址列表, userId={}, count={}", userId, addresses.size());
        return addresses.stream().map(this::toVO).toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void add(Long userId, AddressSaveDTO dto) {
        Long count = userAddressMapper.selectCount(
                new LambdaQueryWrapper<UserAddress>().eq(UserAddress::getUserId, userId));
        if (count >= MAX_ADDRESS_COUNT) {
            throw new BusinessException("收货地址最多保存 " + MAX_ADDRESS_COUNT + " 条");
        }

        // 第一条地址自动成为默认，避免用户新注册后结算页没有可用地址
        boolean makeDefault = DEFAULT_YES.equals(dto.getIsDefault()) || count == 0;

        if (makeDefault) {
            clearDefaults(userId, null);
        }

        UserAddress address = new UserAddress();
        address.setUserId(userId);
        address.setReceiverName(dto.getReceiverName());
        address.setReceiverPhone(dto.getReceiverPhone());
        address.setProvince(dto.getProvince());
        address.setCity(dto.getCity());
        address.setDistrict(dto.getDistrict());
        address.setDetail(dto.getDetail());
        address.setIsDefault(makeDefault ? DEFAULT_YES : DEFAULT_NO);
        userAddressMapper.insert(address);

        log.info("新增收货地址, userId={}, addressId={}, isDefault={}",
                userId, address.getId(), address.getIsDefault());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long userId, Long id, AddressSaveDTO dto) {
        UserAddress existing = userAddressMapper.selectOne(
                new LambdaQueryWrapper<UserAddress>()
                        .eq(UserAddress::getId, id)
                        .eq(UserAddress::getUserId, userId));
        if (existing == null) {
            throw new NotFoundException("地址不存在");
        }

        boolean makeDefault = DEFAULT_YES.equals(dto.getIsDefault());
        if (makeDefault) {
            clearDefaults(userId, id);
        }

        int rows = userAddressMapper.update(null, new LambdaUpdateWrapper<UserAddress>()
                .eq(UserAddress::getId, id)
                .eq(UserAddress::getUserId, userId)
                .set(UserAddress::getReceiverName, dto.getReceiverName())
                .set(UserAddress::getReceiverPhone, dto.getReceiverPhone())
                .set(UserAddress::getProvince, dto.getProvince())
                .set(UserAddress::getCity, dto.getCity())
                .set(UserAddress::getDistrict, dto.getDistrict())
                .set(UserAddress::getDetail, dto.getDetail())
                .set(UserAddress::getIsDefault, makeDefault ? DEFAULT_YES : DEFAULT_NO));
        if (rows == 0) {
            throw new NotFoundException("地址不存在");
        }

        log.info("修改收货地址, userId={}, addressId={}, isDefault={}",
                userId, id, makeDefault ? DEFAULT_YES : DEFAULT_NO);
    }

    @Override
    public void remove(Long userId, Long id) {
        // 逻辑删除由全局配置接管（UPDATE deleted = 1）
        int rows = userAddressMapper.delete(new LambdaQueryWrapper<UserAddress>()
                .eq(UserAddress::getId, id)
                .eq(UserAddress::getUserId, userId));
        if (rows == 0) {
            throw new NotFoundException("地址不存在");
        }
        log.info("删除收货地址, userId={}, addressId={}", userId, id);
    }

    /**
     * 清除该用户的默认地址标记。
     *
     * @param exceptId 需要保留标记的地址 id；新增场景传 null（新地址尚未插入）
     */
    private void clearDefaults(Long userId, Long exceptId) {
        LambdaUpdateWrapper<UserAddress> wrapper = new LambdaUpdateWrapper<UserAddress>()
                .eq(UserAddress::getUserId, userId)
                .eq(UserAddress::getIsDefault, DEFAULT_YES)
                .set(UserAddress::getIsDefault, DEFAULT_NO);
        if (exceptId != null) {
            wrapper.ne(UserAddress::getId, exceptId);
        }
        userAddressMapper.update(null, wrapper);
    }

    private UserAddressVO toVO(UserAddress address) {
        UserAddressVO vo = new UserAddressVO();
        vo.setId(address.getId());
        vo.setReceiverName(address.getReceiverName());
        vo.setReceiverPhone(address.getReceiverPhone());
        vo.setProvince(address.getProvince());
        vo.setCity(address.getCity());
        vo.setDistrict(address.getDistrict());
        vo.setDetail(address.getDetail());
        vo.setIsDefault(address.getIsDefault());
        return vo;
    }
}
