import { create } from 'zustand'

interface AppState {
  sidebarCollapsed: boolean
  globalLoading: boolean
  toggleSidebar: () => void
  setLoading: (val: boolean) => void
}

export const useAppStore = create<AppState>((set) => ({
  sidebarCollapsed: false,
  globalLoading: false,

  toggleSidebar: () => set((s) => ({ sidebarCollapsed: !s.sidebarCollapsed })),

  setLoading: (val: boolean) => set({ globalLoading: val })
}))
