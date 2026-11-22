// Adapted from RuoYi-App api/login.js (MIT). See LICENSE-RuoYi-App.
import request from '../utils/request.js'

export function login(credentials) {
  return request({
    url: '/login',
    headers: { isToken: false },
    method: 'POST',
    data: credentials
  })
}

export function getInfo() {
  return request({ url: '/getInfo' })
}

export function logout() {
  return request({ url: '/logout', method: 'POST' })
}

export function getCodeImg() {
  return request({ url: '/captchaImage', headers: { isToken: false } })
}
