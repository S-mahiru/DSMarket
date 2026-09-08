package com.dsmarket.modules.address.controller;

import com.dsmarket.common.domain.ApiResponse;
import com.dsmarket.common.util.SecurityUtils;
import com.dsmarket.modules.address.entity.Address;
import com.dsmarket.modules.address.service.AddressService;
import lombok.RequiredArgsConstructor;
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
@RequestMapping("/api/v1/addresses")
@RequiredArgsConstructor
public class AddressController {

    private final AddressService addressService;

    @GetMapping
    public ApiResponse<List<Address>> list() {
        return ApiResponse.success(addressService.list(SecurityUtils.requireUserId()));
    }

    @GetMapping("/{id}")
    public ApiResponse<Address> get(@PathVariable Long id) {
        return ApiResponse.success(addressService.get(SecurityUtils.requireUserId(), id));
    }

    @PostMapping
    public ApiResponse<Address> create(@RequestBody Address address) {
        return ApiResponse.success(addressService.create(SecurityUtils.requireUserId(), address));
    }

    @PutMapping("/{id}")
    public ApiResponse<Address> update(@PathVariable Long id, @RequestBody Address address) {
        return ApiResponse.success(addressService.update(SecurityUtils.requireUserId(), id, address));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        addressService.delete(SecurityUtils.requireUserId(), id);
        return ApiResponse.success();
    }

    @PutMapping("/{id}/default")
    public ApiResponse<Void> setDefault(@PathVariable Long id) {
        addressService.setDefault(SecurityUtils.requireUserId(), id);
        return ApiResponse.success();
    }
}
