import axios from 'axios'
import useAuthStore from '../store/authStore'

const axiosInstance = axios.create({
  baseURL: '/api',
  timeout: 10000,
})

// 요청 인터셉터 — Zustand 스토어에서 직접 토큰을 읽어 헤더에 주입
axiosInstance.interceptors.request.use((config) => {
  const token = useAuthStore.getState().token
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

// 응답 인터셉터 — 401 시 스토어 초기화 후 로그인 페이지로
axiosInstance.interceptors.response.use(
  (response) => response,
  (error) => {
    if (error.response?.status === 401) {
      useAuthStore.getState().logout()
      window.location.href = '/login'
    }
    return Promise.reject(error)
  }
)

export default axiosInstance
