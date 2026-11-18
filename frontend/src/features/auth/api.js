import axios from 'axios'

let pendingExchange

export function renewAccessFromCookie() {
  return fetch('/api/token', {
    method: 'POST',
    credentials: 'same-origin',
    cache: 'no-store',
    redirect: 'error',
    signal: AbortSignal.timeout(10000),
  })
}

export function exchangeLoginSession() {
  if (!pendingExchange) {
    pendingExchange = fetch('/oauth2/exchange', {
      method: 'POST',
      credentials: 'same-origin',
      cache: 'no-store',
      redirect: 'error',
      signal: AbortSignal.timeout(10000),
    }).then(response => {
      if (!response.ok) throw new Error('OAuth login exchange failed')
      return response.json()
    }).finally(() => {
      pendingExchange = undefined
    })
  }
  return pendingExchange
}

/**
 * 회원가입 - POST /user (form-encoded)
 */
export const register = (email, password) => {
  const params = new URLSearchParams()
  params.append('email', email)
  params.append('password', password)

  return axios.post('/user', params, {
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    withCredentials: true,
  })
}

/**
 * 로그인 - OAuth2 redirect (Vercel 프록시 경유)
 */
export const loginWithGoogle = () => {
  window.location.href = '/oauth2/authorization/google'
}

export async function serverLogout() {
  const response = await fetch('/logout', {
    method: 'POST',
    credentials: 'same-origin',
    cache: 'no-store',
    redirect: 'error',
    signal: AbortSignal.timeout(10000),
  })
  if (response.status !== 204) throw new Error('Logout did not complete')
}
