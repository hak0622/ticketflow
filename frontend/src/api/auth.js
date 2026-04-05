import axios from 'axios'

/**
 * 회원가입 - POST /user (form-encoded)
 * Spring @Controller가 form data를 기대하므로 URLSearchParams 사용
 */
export const register = (email, password) => {
  const params = new URLSearchParams()
  params.append('email', email)
  params.append('password', password)
  return axios.post('/user', params, {
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
  })
}

/**
 * 로그인 - OAuth2 redirect
 * 브라우저를 /oauth2/authorization/google 로 이동시키면
 * Spring이 처리 후 configured redirect-url?token=... 로 돌아옴
 */
export const loginWithGoogle = () => {
  window.location.href = '/oauth2/authorization/google'
}

/**
 * 로그아웃 - GET /logout (Spring Security 로그아웃)
 * 서버 세션 제거 후 React에서 local token도 삭제
 */
export const serverLogout = () =>
  axios.get('/logout', { maxRedirects: 0 }).catch(() => {})
