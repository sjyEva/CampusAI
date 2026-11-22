export class ApiError extends Error {
  constructor(message, code) {
    super(message)
    this.name = 'ApiError'
    this.code = code
  }
}

export default async function request({ url, method = 'get', data, token, signal }) {
  const headers = { Accept: 'application/json' }
  if (token) headers.Authorization = `Bearer ${token}`
  if (data !== undefined) headers['Content-Type'] = 'application/json'

  let response
  try {
    const timeout = AbortSignal.timeout(10000)
    response = await fetch(`/api${url}`, {
      method: method.toUpperCase(),
      headers,
      body: data === undefined ? undefined : JSON.stringify(data),
      signal: signal ? AbortSignal.any([signal, timeout]) : timeout,
    })
  } catch (error) {
    if (signal?.aborted) throw error
    throw new ApiError('无法连接 CampusAI 后端，请确认服务已启动。', 0)
  }

  const body = await response.json().catch(() => null)
  if (!response.ok || body?.code !== 200) {
    const code = response.ok ? body?.code : response.status
    throw new ApiError(body?.msg || `后端请求失败（HTTP ${response.status}）`, code)
  }
  return body
}
