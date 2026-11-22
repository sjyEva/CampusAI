// Auth API wrappers adapted from RuoYi-Vue3. See LICENSE-RuoYi-Vue3.
import request from './request.js'

export function login(username, password) {
  const data = { username, password }
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
