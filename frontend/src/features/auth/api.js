import axios from 'axios'

let pendingExchange

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

/**
 * 로그아웃 - GET /logout
 */
export const serverLogout = () =>
  axios.get('/logout', {
    maxRedirects: 0,
    withCredentials: true,
  }).catch(() => {})