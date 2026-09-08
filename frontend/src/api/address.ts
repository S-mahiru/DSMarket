import request from '@/api/request'
import type { Address } from '@/types/address'

export function getAddresses(): Promise<Address[]> {
  return request.get('/addresses')
}

export function createAddress(data: Partial<Address>): Promise<Address> {
  return request.post('/addresses', data)
}

export function updateAddress(id: number, data: Partial<Address>) {
  return request.put(`/addresses/${id}`, data)
}

export function deleteAddress(id: number) {
  return request.delete(`/addresses/${id}`)
}

export function setDefaultAddress(id: number) {
  return request.put(`/addresses/${id}/default`)
}
