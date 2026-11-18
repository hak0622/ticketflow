import { serverLogout } from './api'
import { beginLogout, finishLogout, setAccessToken } from './session'

export function applyOAuthToken(token) {
  return setAccessToken(token, true)
}

export async function performLogout() {
  const attempt = beginLogout()
  let succeeded = false
  try {
    await serverLogout()
    succeeded = true
  } finally {
    finishLogout(attempt, succeeded)
  }
}
