import axios from 'axios'

let authSnapshot = () => ({})
let renew = async () => 'unavailable'

const axiosInstance = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '/api',
  timeout: 10000,
  withCredentials: true,
})

export function configureApiAuth(config) {
  authSnapshot = config.authSnapshot
  renew = config.renew
}

axiosInstance.interceptors.request.use(config => {
  const snapshot = authSnapshot()
  config.authSnapshot = snapshot
  if (snapshot.token) config.headers.Authorization = `Bearer ${snapshot.token}`
  return config
}, undefined, { synchronous: true })

function checkRequest(config) {
  const previous = config?.authSnapshot
  if (config?.signal?.aborted || (previous?.token && previous.generation !== authSnapshot().generation)) {
    throw new axios.CanceledError('Authentication context changed', config)
  }
}

axiosInstance.interceptors.response.use(
  response => {
    checkRequest(response.config)
    return response
  },
  async error => {
    const config = error.config
    checkRequest(config)
    const original = config?.authSnapshot
    if (error.response?.status !== 401 || !original?.token || config.authRetried) throw error

    const result = await renew(original)
    if (result !== 'ready') throw error
    checkRequest(config)
    const current = authSnapshot()
    if (!current.token || original.userId !== current.userId) throw error
    if (config.method?.toLowerCase() === 'get') {
      config.authRetried = true
      return axiosInstance.request(config)
    }

    error.authRestored = true
    throw error
  },
)

export default axiosInstance
