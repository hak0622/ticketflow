import axios from 'axios'

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