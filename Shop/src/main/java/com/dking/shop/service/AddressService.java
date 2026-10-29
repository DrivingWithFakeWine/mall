package com.dking.shop.service;

import com.dking.shop.dto.AddressSaveDTO;
import com.dking.shop.vo.UserAddressVO;

import java.util.List;

public interface AddressService {

    /** 当前用户全部收货地址：默认在前，其余按创建时间倒序 */
    List<UserAddressVO> list(Long userId);

    /** 新增收货地址；设为默认时清除其它默认，第一条地址自动成为默认 */
    void add(Long userId, AddressSaveDTO dto);

    /** 修改收货地址；只能改自己的，设为默认时清除其它默认 */
    void update(Long userId, Long id, AddressSaveDTO dto);

    /** 删除收货地址（逻辑删除），只能删自己的 */
    void remove(Long userId, Long id);
}
