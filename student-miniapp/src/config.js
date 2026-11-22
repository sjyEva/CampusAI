// Adapted from RuoYi-App config.js (MIT). See LICENSE-RuoYi-App.
let defaultBaseUrl = 'http://127.0.0.1:8080'
// #ifdef H5
defaultBaseUrl = '/api'
// #endif

export default {
  baseUrl: (import.meta.env?.VITE_CAMPUSAI_API_BASE_URL?.trim() || defaultBaseUrl).replace(/\/+$/, '')
}
