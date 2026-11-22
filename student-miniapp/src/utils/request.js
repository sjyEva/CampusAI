// Adapted from RuoYi-App utils/request.js (MIT). See LICENSE-RuoYi-App.
import config from '../config.js'
import { getToken, removeToken } from './auth.js'

export default function request(options) {
  const token = getToken()
  const header = {}
  const needsToken = options.headers?.isToken !== false
  if (token && needsToken) {
    header.Authorization = 'Bearer ' + token
  }

  return new Promise((resolve, reject) => {
    uni.request({
      url: config.baseUrl + options.url,
      method: options.method || 'GET',
      data: options.data,
      header,
      timeout: 10000,
      success(response) {
        const payload = response.data
        const failedHttp = response.statusCode < 200 || response.statusCode >= 300
        const code = failedHttp ? response.statusCode : payload?.code
        if (!failedHttp && code === 200) {
          resolve(payload)
          return
        }

        if (needsToken && code === 401 && token && getToken() === token) {
          removeToken()
        }
        const error = new Error(payload?.msg || '后端返回了无法处理的响应')
        error.code = code
        reject(error)
      },
      fail() {
        reject(new Error('无法连接后端，请检查服务地址后重试'))
      }
    })
  })
}
