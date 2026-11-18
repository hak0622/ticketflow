import { configureApiAuth } from '../../shared/api/axios'
import { renewAccessFromCookie } from './api'
import { authSnapshot, canRenew, clearAuthSession, initializeAuthStorage, setAccessToken } from './session'

let pendingRenewal

export function restoreBrowserSession(original = authSnapshot()) {
  if (!canRenew(original)) return Promise.resolve('superseded')

  if (pendingRenewal?.generation === original.generation) return pendingRenewal.promise
  if (original.token !== authSnapshot().token) return Promise.resolve('ready')

  const attempt = { generation: original.generation }
  attempt.promise = (async () => {
    try {
      const response = await renewAccessFromCookie()
      if (!canRenew(original)) return 'superseded'
      if (response.status === 401) {
        clearAuthSession()
        return 'anonymous'
      }
      if (response.status !== 201) return 'unavailable'
      const { accessToken } = await response.json()
      if (!canRenew(original)) return 'superseded'
      return setAccessToken(accessToken) ? 'ready' : 'unavailable'
    } catch {
      return canRenew(original) ? 'unavailable' : 'superseded'
    } finally {
      if (pendingRenewal === attempt) pendingRenewal = undefined
    }
  })()
  pendingRenewal = attempt
  return attempt.promise
}

export function initializeAuthApiIntegration() {
  initializeAuthStorage()
  configureApiAuth({ authSnapshot, renew: restoreBrowserSession })
}
