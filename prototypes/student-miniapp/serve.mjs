import { createServer } from 'node:http'
import { readFile } from 'node:fs/promises'

const port = Number(process.env.CAMPUSAI_PREVIEW_PORT || 5180)
const backend = new URL(process.env.CAMPUSAI_BACKEND_URL || 'http://127.0.0.1:8080')
if (!['localhost', '127.0.0.1', '[::1]'].includes(backend.hostname) ||
    !['http:', 'https:'].includes(backend.protocol) || backend.username || backend.password) {
  throw new Error('CAMPUSAI_BACKEND_URL 必须是本机 HTTP 或 HTTPS 地址')
}

const page = new URL('./index.html', import.meta.url)
const hosts = new Set(['localhost:' + port, '127.0.0.1:' + port])
const reads = /^\/(?:getInfo|campusai\/courses(?:\/[1-9]\d*(?:\/chapters|\/rag\/(?:capabilities|materials(?:\/[a-z0-9-]+\/file)?|sources\/[a-z0-9-]+))?)?)$/
const writes = /^\/(?:login|logout|campusai\/courses\/[1-9]\d*\/rag\/ask)$/

function send(response, status, message) {
  response.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' })
  response.end(JSON.stringify({ code: status, msg: message }))
}

const server = createServer(async (request, response) => {
  try {
    if (!hosts.has(request.headers.host)) return send(response, 403, '请通过本机预览地址访问')
    if (request.headers.origin && request.headers.origin !== 'http://' + request.headers.host) {
      return send(response, 403, '仅支持本机页面发起联调')
    }
    const url = new URL(request.url, 'http://' + request.headers.host)
    if (request.method === 'GET' && (url.pathname === '/' || url.pathname === '/index.html')) {
      const html = await readFile(page)
      response.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store' })
      return response.end(html)
    }
    if (!url.pathname.startsWith('/api/')) return send(response, 404, '页面不存在')
    const path = url.pathname.slice(4)
    const isRead = request.method === 'GET' && reads.test(path)
    const isWrite = request.method === 'POST' && writes.test(path)
    if (!isRead && !isWrite) return send(response, 404, '接口未开放给学生端预览')

    const chunks = []
    let size = 0
    for await (const chunk of request) {
      size += chunk.length
      if (size > 16_384) return send(response, 413, '请求内容太长')
      chunks.push(chunk)
    }
    const headers = {}
    if (request.headers.authorization) headers.Authorization = request.headers.authorization
    if (isWrite) headers['Content-Type'] = 'application/json'
    const result = await fetch(new URL(path + url.search, backend), {
      method: request.method,
      headers,
      body: isWrite ? Buffer.concat(chunks) : undefined,
      redirect: 'manual',
      signal: AbortSignal.timeout(80_000)
    })
    if (result.status >= 300 && result.status < 400) return send(response, 502, '本机后端返回了意外的重定向')
    response.writeHead(result.status, {
      'Content-Type': result.headers.get('content-type') || 'application/octet-stream',
      'Cache-Control': 'no-store'
    })
    response.end(Buffer.from(await result.arrayBuffer()))
  } catch (error) {
    const message = error.name === 'TimeoutError' ? '后端响应超时，请重试' : '无法连接 CampusAI 本机后端，请确认服务已启动'
    if (!response.headersSent) send(response, 502, message)
    else response.end()
  }
})

server.listen(port, '127.0.0.1', () => {
  console.log('CampusAI 学生端预览 http://127.0.0.1:' + port + '/')
  console.log('本机 API 转发至 ' + backend.origin)
})
