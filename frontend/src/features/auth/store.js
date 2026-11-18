import { create } from 'zustand'
import { persist } from 'zustand/middleware'

// 화면 인증 상태에 사용할 정보만 읽는다. JWT 서명은 서버에서 검증한다.
function readAccessToken(token) {
  try {
    const parts = token.split('.')
    if (parts.length !== 3 || parts.some(part => !/^[A-Za-z0-9_-]+$/.test(part))) return null

    const payload = parts[1].replace(/-/g, '+').replace(/_/g, '/')
    const base64 = payload.padEnd(Math.ceil(payload.length / 4) * 4, '=')
    const bytes = Uint8Array.from(atob(base64), character => character.charCodeAt(0))
    const user = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(bytes))

    if (user?.token_use !== 'access' || !Number.isFinite(user.exp) || user.exp <= Date.now() / 1000) {
      return null
    }
    return user
  } catch {
    return null
  }
}

const useAuthStore = create(
  persist(
    (set) => ({
      token: null,
      user: null,
      setToken: (token) => {
        const user = readAccessToken(token)
        set({ token: user ? token : null, user })
      },
      logout: () => set({ token: null, user: null }),
    }),
    {
      name: 'auth-storage',
      onRehydrateStorage: () => (state) => {
        if (state?.token || state?.user) state.setToken(state.token)
      },
    },
  ),
)

export default useAuthStore
