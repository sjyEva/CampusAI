// Auth API wrappers adapted from RuoYi-Vue3. See LICENSE-RuoYi-Vue3.
import request from './request.js'

export function login(username, password, code, uuid) {
  const data = { username, password, code, uuid }
  return request({
    url: '/login',
    method: 'post',
    data,
  })
}

export function getInfo(token) {
  return request({
    url: '/getInfo',
    method: 'get',
    token,
  })
}

export function logout(token) {
  return request({
    url: '/logout',
    method: 'post',
    token,
  })
}

export function getCodeImg() {
  return request({ url: '/captchaImage', method: 'get' })
}
