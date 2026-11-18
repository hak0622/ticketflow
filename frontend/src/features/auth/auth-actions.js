import { serverLogout } from './api'
import { clearAuthSession, getAccessToken, setAccessToken } from './session'

export function applyOAuthToken(token) {
  setAccessToken(token)
  return Boolean(token) && getAccessToken() === token
}

export function clearClientAuth() {
  clearAuthSession()
}

export async function performLogout() {
  await serverLogout()
  clearClientAuth()
}
