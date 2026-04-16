import axios from 'axios'

const BACKEND = import.meta.env.VITE_BACKEND_URL || ''

/**
 * 회원가입 - POST /user (form-encoded)
 */
export const register = (email, password) => {
  const params = new URLSearchParams()
  params.append('email', email)
  params.append('password', password)

  return axios.post(`${BACKEND}/user`, params, {
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    withCredentials: true,
  })
}

/**
 * 로그인 - OAuth2 redirect
 */
export const loginWithGoogle = () => {
  window.location.href = `${BACKEND}/oauth2/authorization/google`
}

/**
 * 로그아웃 - GET /logout
 */
export const serverLogout = () =>
  axios.get(`${BACKEND}/logout`, {
    maxRedirects: 0,
    withCredentials: true,
  }).catch(() => {})