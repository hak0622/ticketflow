import axios from 'axios'

let getAccessToken = () => null
let onUnauthorized = () => {}

const axiosInstance = axios.create({
  baseURL: '/api',
  timeout: 10000,
})

export function configureApiAuth(config = {}) {
  getAccessToken = typeof config.getAccessToken === 'function'
    ? config.getAccessToken
    : () => null

  onUnauthorized = typeof config.onUnauthorized === 'function'
    ? config.onUnauthorized
    : () => {}
}

// 요청 인터셉터 — 외부에서 주입된 인증 토큰 조회기로 헤더에 주입
axiosInstance.interceptors.request.use((config) => {
  const token = getAccessToken()
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

// 응답 인터셉터 — 401 시 외부에서 주입된 인증 실패 핸들러 실행
axiosInstance.interceptors.response.use(
  (response) => response,
  (error) => {
    if (error.response?.status === 401) {
      onUnauthorized()
    }
    return Promise.reject(error)
  }
)

export default axiosInstance
