package com.dsmarket.modules.address.service;

import com.dsmarket.modules.address.entity.Address;

import java.util.List;

public interface AddressService {

    List<Address> list(Long userId);

    Address get(Long userId, Long id);

    Address create(Long userId, Address address);

    Address update(Long userId, Long id, Address address);

    void delete(Long userId, Long id);

    void setDefault(Long userId, Long id);
}
