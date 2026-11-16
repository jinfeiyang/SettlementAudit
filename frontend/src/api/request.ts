import axios from 'axios'
import { ElMessage } from 'element-plus'

/** 统一请求实例：后端统一响应 { code, message, data }，code=0 为成功 */
const request = axios.create({
  baseURL: '/api',
  timeout: 60000,
})

request.interceptors.response.use(
  (resp) => {
    const body = resp.data
    if (body && typeof body.code === 'number' && body.code !== 0) {
      ElMessage.error(body.message || '请求失败')
      return Promise.reject(new Error(body.message))
    }
    return body?.data ?? body
  },
  (err) => {
    ElMessage.error(err?.message || '网络异常')
    return Promise.reject(err)
  },
)

export default request
