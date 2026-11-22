// Route API wrapper adapted from RuoYi-Vue3. See LICENSE-RuoYi-Vue3.
import request from './request.js'

export const getRouters = (token) => {
  return request({
    url: '/getRouters',
    method: 'get',
    token,
  })
}
