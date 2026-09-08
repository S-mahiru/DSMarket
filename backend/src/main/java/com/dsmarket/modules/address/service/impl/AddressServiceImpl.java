package com.dsmarket.modules.address.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dsmarket.common.exception.BusinessException;
import com.dsmarket.common.exception.ErrorCode;
import com.dsmarket.modules.address.entity.Address;
import com.dsmarket.modules.address.mapper.AddressMapper;
import com.dsmarket.modules.address.service.AddressService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AddressServiceImpl implements AddressService {

    private final AddressMapper addressMapper;

    @Override
    public List<Address> list(Long userId) {
        return addressMapper.selectList(new LambdaQueryWrapper<Address>()
                .eq(Address::getUserId, userId).orderByDesc(Address::getIsDefault).orderByDesc(Address::getId));
    }

    @Override
    public Address get(Long userId, Long id) {
        return requireOwned(userId, id);
    }

    @Override
    @Transactional
    public Address create(Long userId, Address address) {
        address.setId(null);
        address.setUserId(userId);
        address.setIsDefault(address.getIsDefault() == null ? 0 : address.getIsDefault());
        if (address.getIsDefault() == 1) {
            clearDefault(userId);
        } else if (list(userId).isEmpty()) {
            address.setIsDefault(1); // 首个地址自动设为默认
        }
        addressMapper.insert(address);
        return address;
    }

    @Override
    @Transactional
    public Address update(Long userId, Long id, Address address) {
        Address existing = requireOwned(userId, id);
        if (address.getReceiverName() != null) existing.setReceiverName(address.getReceiverName());
        if (address.getReceiverPhone() != null) existing.setReceiverPhone(address.getReceiverPhone());
        if (address.getProvince() != null) existing.setProvince(address.getProvince());
        if (address.getCity() != null) existing.setCity(address.getCity());
        if (address.getDistrict() != null) existing.setDistrict(address.getDistrict());
        if (address.getDetailAddress() != null) existing.setDetailAddress(address.getDetailAddress());
        if (address.getZipCode() != null) existing.setZipCode(address.getZipCode());
        if (address.getLabel() != null) existing.setLabel(address.getLabel());
        if (address.getIsDefault() != null) {
            if (address.getIsDefault() == 1) {
                clearDefault(userId);
            }
            existing.setIsDefault(address.getIsDefault());
        }
        addressMapper.updateById(existing);
        return existing;
    }

    @Override
    public void delete(Long userId, Long id) {
        requireOwned(userId, id);
        addressMapper.deleteById(id);
    }

    @Override
    @Transactional
    public void setDefault(Long userId, Long id) {
        requireOwned(userId, id);
        clearDefault(userId);
        Address update = new Address();
        update.setId(id);
        update.setIsDefault(1);
        addressMapper.updateById(update);
    }

    private void clearDefault(Long userId) {
        Address update = new Address();
        update.setIsDefault(0);
        addressMapper.update(update, new LambdaQueryWrapper<Address>().eq(Address::getUserId, userId));
    }

    private Address requireOwned(Long userId, Long id) {
        Address address = addressMapper.selectById(id);
        if (address == null || !address.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND.getCode(), "地址不存在");
        }
        return address;
    }
}
