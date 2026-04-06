import { configureApiAuth } from '../../shared/api/axios'
import { clearAuthSession, getAccessToken } from './session'

let isHandlingUnauthorized = false
let isInitialized = false

export function initializeAuthApiIntegration() {
  if (isInitialized) return

  configureApiAuth({
    getAccessToken,
    onUnauthorized: () => {
      if (isHandlingUnauthorized) return

      isHandlingUnauthorized = true
      clearAuthSession()
      window.location.href = '/login'
    },
  })

  isInitialized = true
}
