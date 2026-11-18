import useAuthStore, { readAccessToken } from './store'

let generation = 0
let logoutInProgress = false
let logoutChannel

export function authSnapshot() {
  const { token, user } = useAuthStore.getState()
  return { generation, token, userId: user?.id }
}

export function setAccessToken(token, newLogin = false) {
  const user = readAccessToken(token)
  if (!user || !Number.isSafeInteger(user.id) || user.id <= 0) return false
  if (newLogin || authSnapshot().userId !== user.id) generation++
  useAuthStore.setState({ token, user })
  return true
}

export function clearAuthSession() {
  generation++
  useAuthStore.getState().logout()
}

export function beginLogout() {
  logoutInProgress = true
  return ++generation
}

export function finishLogout(attempt, succeeded) {
  logoutInProgress = false
  if (succeeded && attempt === generation) {
    clearAuthSession()
    logoutChannel?.postMessage('logout')
  }
}

export function canRenew(snapshot) {
  return !logoutInProgress && generation === snapshot.generation
}

export function initializeAuthStorage() {
  try {
    localStorage.removeItem('auth-storage')
    localStorage.removeItem('access_token')
  } catch {
    // Authentication can still use memory and the server cookie.
  }
  if (!logoutChannel && typeof BroadcastChannel === 'function') {
    logoutChannel = new BroadcastChannel('ticketflow-auth')
    logoutChannel.addEventListener('message', event => {
      if (event.data === 'logout') clearAuthSession()
    })
  }
}
