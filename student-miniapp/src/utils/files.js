export const fileTypes = ['pdf', 'ppt', 'pptx', 'doc', 'docx', 'xls', 'xlsx', 'txt', 'md']
export const extensionOf = name => String(name || '').split('.').pop().toLowerCase()
export const isTextFile = name => ['txt', 'md'].includes(extensionOf(name))
export const newId = () => Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 10)

const maxBytes = 10 * 1024 * 1024
const nativeError = (error, fallback) => new Error(error?.errMsg?.includes('cancel') ? '已取消选择' : fallback)
const nativeCall = (method, options, message) => new Promise((resolve, reject) => {
  method({ ...options, success: resolve, fail: error => reject(nativeError(error, message)) })
})

export async function chooseFile() {
  let result
  // #ifdef MP-WEIXIN
  result = await nativeCall(wx.chooseMessageFile, { count: 1, type: 'file', extension: fileTypes }, '无法打开微信文件列表')
  // #endif
  // #ifdef H5
  result = await nativeCall(uni.chooseFile, { count: 1, extension: fileTypes.map(type => '.' + type) }, '无法选择文件')
  // #endif
  const raw = result?.tempFiles?.[0]
  if (!raw?.name || !fileTypes.includes(extensionOf(raw.name)) || raw.size <= 0 || raw.size > maxBytes) {
    throw new Error('请选择支持的非空文件，单个文件不超过 10 MB')
  }
  return { name: raw.name, size: raw.size, path: raw.path, raw }
}

export async function chooseAvatar() {
  const answer = await nativeCall(uni.chooseImage, { count: 1, sizeType: ['compressed'] }, '无法选择头像')
  const file = answer.tempFiles[0]
  if (file.size > 700 * 1024) throw new Error('请选择 700 KB 以内的图片')
  const path = answer.tempFilePaths[0]
  const extension = extensionOf(file.name || path)
  const mime = ['png', 'webp', 'gif'].includes(extension) ? 'image/' + extension : 'image/jpeg'
  // #ifdef MP-WEIXIN
  const manager = wx.getFileSystemManager()
  const encoded = await nativeCall(options => manager.readFile(options), { filePath: path, encoding: 'base64' }, '无法读取头像')
  return 'data:' + mime + ';base64,' + encoded.data
  // #endif
  // #ifdef H5
  const response = await fetch(path)
  const blob = await response.blob()
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(reader.result)
    reader.onerror = () => reject(new Error('无法读取头像'))
    reader.readAsDataURL(blob.slice(0, blob.size, blob.type || mime))
  })
  // #endif
}

export async function selectedText(file) {
  if (!isTextFile(file.name)) return { text: '', editableText: false }
  const maxPreviewBytes = 120000
  let rawText
  // #ifdef MP-WEIXIN
  const manager = wx.getFileSystemManager()
  const answer = await nativeCall(options => manager.readFile(options), {
    filePath: file.path, encoding: 'utf8', length: Math.min(file.size, maxPreviewBytes)
  }, '无法读取文本，请使用 UTF-8 文件')
  rawText = answer.data
  // #endif
  // #ifdef H5
  rawText = await file.raw.slice(0, maxPreviewBytes).text()
  // #endif
  const text = rawText.replace(/^\uFEFF/, '')
  return { text: text.slice(0, 12000), editableText: file.size <= maxPreviewBytes && text.length <= 12000 }
}

export async function keepSelectedFile(file) {
  const key = 'campusai-' + newId() + '.' + extensionOf(file.name)
  // #ifdef MP-WEIXIN
  const manager = wx.getFileSystemManager()
  const path = wx.env.USER_DATA_PATH + '/' + key
  await nativeCall(options => manager.copyFile(options), { srcPath: file.path, destPath: path }, '无法保存文件，请检查本机存储')
  return path
  // #endif
  // #ifdef H5
  return browserFile('put', key, file.raw)
  // #endif
}

export async function makeFile(name, data, encoding) {
  const key = 'campusai-' + newId() + '.' + extensionOf(name)
  // #ifdef MP-WEIXIN
  const manager = wx.getFileSystemManager()
  const path = wx.env.USER_DATA_PATH + '/' + key
  await nativeCall(options => manager.writeFile(options), { filePath: path, data, encoding }, '无法保存文件，请检查本机存储')
  return path
  // #endif
  // #ifdef H5
  const bytes = encoding === 'base64' ? Uint8Array.from(atob(data), char => char.charCodeAt(0)) : data
  const type = extensionOf(name) === 'pdf' ? 'application/pdf' : isTextFile(name) ? 'text/plain;charset=utf-8' : 'application/octet-stream'
  return browserFile('put', key, new Blob([bytes], { type }))
  // #endif
}

export async function forgetFile(path) {
  if (!path) return
  // #ifdef MP-WEIXIN
  if (path.startsWith(wx.env.USER_DATA_PATH + '/campusai-')) {
    const manager = wx.getFileSystemManager()
    await nativeCall(options => manager.unlink(options), { filePath: path }, '文件已不可用').catch(() => {})
  }
  // #endif
  // #ifdef H5
  if (path.startsWith('campusai-')) await browserFile('delete', path).catch(() => {})
  // #endif
}

export async function openOriginal(path, name) {
  // #ifdef MP-WEIXIN
  const fileType = extensionOf(name)
  if (!isTextFile(name)) {
    return nativeCall(uni.openDocument, { filePath: path, fileType, showMenu: true }, '无法打开原文件，可尝试下载')
  }
  // #endif
  return exportOriginal(path, name)
}

export async function exportOriginal(path, name) {
  // #ifdef MP-WEIXIN
  if (typeof wx.shareFileMessage === 'function') {
    return nativeCall(wx.shareFileMessage, { filePath: path, fileName: name }, '无法导出文件，请在微信真机中重试')
  }
  if (!isTextFile(name)) return openOriginal(path, name)
  throw new Error('当前环境无法导出文件，请在微信真机中重试')
  // #endif
  // #ifdef H5
  const blob = await browserFile('get', path)
  if (!blob) throw new Error('本机文件已不可用')
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = name
  link.click()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
  // #endif
}

// #ifdef H5
let database

function browserFile(method, key, value) {
  if (!database) {
    database = new Promise((resolve, reject) => {
      const request = indexedDB.open('campusai-student-files', 1)
      request.onupgradeneeded = () => request.result.createObjectStore('files')
      request.onsuccess = () => resolve(request.result)
      request.onerror = () => reject(new Error('浏览器文件存储不可用'))
    }).catch(error => {
      database = undefined
      throw error
    })
  }
  return database.then(db => new Promise((resolve, reject) => {
    const transaction = db.transaction('files', method === 'get' ? 'readonly' : 'readwrite')
    const files = transaction.objectStore('files')
    const result = method === 'put' ? files.put(value, key) : files[method](key)
    transaction.oncomplete = () => resolve(method === 'get' ? result.result : key)
    transaction.onabort = transaction.onerror = () => reject(new Error('浏览器文件存储操作失败'))
  }))
}
// #endif
