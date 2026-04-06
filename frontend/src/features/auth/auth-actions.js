import { serverLogout } from './api'
import { clearAuthSession, setAccessToken } from './session'

export function applyOAuthToken(token) {
  setAccessToken(token)
}

export function clearClientAuth() {
  clearAuthSession()
}

export async function performLogout() {
  await serverLogout()
  clearClientAuth()
}
