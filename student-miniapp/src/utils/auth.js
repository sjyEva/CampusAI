// Adapted from RuoYi-App utils/auth.js (MIT). See LICENSE-RuoYi-App.
const TokenKey = 'CampusAI-Token'

export function getToken() {
  return uni.getStorageSync(TokenKey)
}

export function setToken(token) {
  return uni.setStorageSync(TokenKey, token)
}

export function removeToken() {
  return uni.removeStorageSync(TokenKey)
}
