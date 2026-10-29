package com.dking.shop.controller;

import com.dking.shop.common.Result;
import com.dking.shop.dto.AddressSaveDTO;
import com.dking.shop.service.AddressService;
import com.dking.shop.vo.UserAddressVO;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/address")
public class AddressController {

    @Resource
    private AddressService addressService;

    @GetMapping("/list")
    public Result<List<UserAddressVO>> list(@AuthenticationPrincipal Jwt jwt) {
        return Result.success(addressService.list(Long.valueOf(jwt.getSubject())));
    }

    @PostMapping("/add")
    public Result<Void> add(@AuthenticationPrincipal Jwt jwt,
                            @RequestBody @Valid AddressSaveDTO dto) {
        addressService.add(Long.valueOf(jwt.getSubject()), dto);
        return Result.success();
    }

    @PutMapping("/{id}")
    public Result<Void> update(@AuthenticationPrincipal Jwt jwt,
                               @PathVariable Long id,
                               @RequestBody @Valid AddressSaveDTO dto) {
        addressService.update(Long.valueOf(jwt.getSubject()), id, dto);
        return Result.success();
    }

    @DeleteMapping("/{id}")
    public Result<Void> remove(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        addressService.remove(Long.valueOf(jwt.getSubject()), id);
        return Result.success();
    }
}
