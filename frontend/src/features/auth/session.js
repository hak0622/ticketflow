import useAuthStore from './store'

export function getAccessToken() {
  return useAuthStore.getState().token
}

export function setAccessToken(token) {
  useAuthStore.getState().setToken(token)
}

export function clearAuthSession() {
  useAuthStore.getState().logout()
}
