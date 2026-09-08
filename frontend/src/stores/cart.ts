import { create } from 'zustand'
import request from '@/api/request'

interface CartState {
  count: number
  fetchCount: () => Promise<void>
  setCount: (val: number) => void
}

export const useCartStore = create<CartState>((set) => ({
  count: 0,

  async fetchCount() {
    try {
      const res: { count: number } = await request.get('/cart/count')
      set({ count: res.count })
    } catch {
      set({ count: 0 })
    }
  },

  setCount: (val: number) => set({ count: val })
}))
