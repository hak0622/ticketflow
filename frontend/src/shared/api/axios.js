import axios from 'axios'

let getAccessToken = () => null
let onUnauthorized = () => {}

const axiosInstance = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '/api',
  timeout: 10000,
  withCredentials: true,
})

export function configureApiAuth(config = {}) {
  getAccessToken = typeof config.getAccessToken === 'function'
    ? config.getAccessToken
    : () => null

  onUnauthorized = typeof config.onUnauthorized === 'function'
    ? config.onUnauthorized
    : () => {}
}

axiosInstance.interceptors.request.use((config) => {
  const token = getAccessToken()
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

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