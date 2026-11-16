import axios, { type AxiosRequestConfig } from 'axios'
import { ElMessage } from 'element-plus'

export const TOKEN_KEY = 'preaudit.token'
export const USER_KEY = 'preaudit.user'

/** 统一请求实例：后端统一响应 { code, message, data }，code=0 为成功 */
const instance = axios.create({
  baseURL: '/api',
  timeout: 180000,
})

instance.interceptors.request.use((config) => {
  const token = localStorage.getItem(TOKEN_KEY)
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

instance.interceptors.response.use(
  (resp) => {
    const body = resp.data
    if (body instanceof Blob) {
      return body
    }
    if (body && typeof body.code === 'number') {
      if (body.code === 401) {
        localStorage.removeItem(TOKEN_KEY)
        localStorage.removeItem(USER_KEY)
        if (!location.pathname.startsWith('/login')) {
          location.replace('/login')
        }
        return Promise.reject(new Error(body.message || '未登录'))
      }
      if (body.code !== 0) {
        ElMessage.error(body.message || '请求失败')
        return Promise.reject(new Error(body.message || '请求失败'))
      }
      return body.data
    }
    return body
  },
  async (err) => {
    let msg = err?.message || '网络异常'
    const data = err?.response?.data
    if (data instanceof Blob) {
      try {
        const text = await data.text()
        const parsed = JSON.parse(text)
        msg = parsed.message || parsed.detail || msg
      } catch {
        // 忽略解析失败
      }
    } else if (data?.message) {
      msg = data.message
    }
    ElMessage.error(msg)
    return Promise.reject(err)
  },
)

/** 拦截器已解包 data，此处声明便捷类型 */
interface Http {
  get<T = any>(url: string, config?: AxiosRequestConfig): Promise<T>
  post<T = any>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T>
  put<T = any>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T>
  delete<T = any>(url: string, config?: AxiosRequestConfig): Promise<T>
}

const request = instance as unknown as Http

export async function download(url: string, filename: string, params?: Record<string, unknown>) {
  try {
    const blob = await request.get<Blob>(url, { params, responseType: 'blob' })
    const link = document.createElement('a')
    link.href = URL.createObjectURL(blob)
    link.download = filename
    document.body.appendChild(link)
    link.click()
    document.body.removeChild(link)
    URL.revokeObjectURL(link.href)
  } catch {
    ElMessage.error('导出失败，请稍后重试')
  }
}

export default request
