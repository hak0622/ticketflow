import { create } from 'zustand'
import { persist } from 'zustand/middleware'

/** JWT payload 디코딩 (라이브러리 없이 atob 사용) */
function decodeToken(token) {
  try {
    const payload = token.split('.')[1]
    return JSON.parse(atob(payload))
  } catch {
    return null
  }
}

function isExpired(user) {
  return user?.exp && user.exp * 1000 < Date.now()
}

const useAuthStore = create(
  persist(
    (set) => ({
      token: null,
      user: null,

      setToken: (token) => {
        const user = decodeToken(token)
        if (isExpired(user)) {
          set({ token: null, user: null })
          return
        }
        set({ token, user })
      },

      logout: () => set({ token: null, user: null }),
    }),
    {
      name: 'auth-storage', // localStorage 키
      // 재수화(rehydrate) 시 만료된 토큰 자동 제거
      onRehydrateStorage: () => (state) => {
        if (state?.token) {
          const user = decodeToken(state.token)
          if (isExpired(user)) {
            state.logout()
          }
        }
      },
    }
  )
)

export default useAuthStore
