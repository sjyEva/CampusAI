import config from '../config.js'
import { getCourses, getCourse } from './courses.js'
import { demoCourses } from './demo-data.js'
import { demoFiles } from './demo-files.js'
import { getToken, removeToken } from '../utils/auth.js'
import { extensionOf, makeFile } from '../utils/files.js'
import request from '../utils/request.js'

const coursePath = id => '/campusai/courses/' + encodeURIComponent(id) + '/rag'
const materialPath = (id, material) => coursePath(id) + '/materials/' + encodeURIComponent(material)
const citationPrompt = '课件里怎样核对引用？'

function demoCourse(id) {
  const course = demoCourses.find(item => String(item.id) === String(id))
  if (!course) throw new Error('课程不存在或无访问权限')
  return course
}

export async function studyCourses(session) {
  if (session.mode === 'demo') return demoCourses
  const { data } = await getCourses()
  if (!Array.isArray(data)) throw new Error('课程列表格式有误')
  return data
}

export async function studyCourse(session, id) {
  if (session.mode === 'demo') return demoCourse(id)
  const { data } = await getCourse(id)
  if (!data?.name) throw new Error('课程详情格式有误')
  return data
}

function materialsForDemo(course) {
  const notes = course.notes.map(([filename, location, text], index) => ({
    id: 'note-' + index, filename, name: filename, location, text, category: '讲义'
  }))
  const documents = [
    ['pdf', '课程讲义', '讲义'],
    ['pptx', '课堂课件', '课件'],
    ['ppt', '课件兼容版', '课件'],
    ['docx', '练习文档', '习题']
  ].map(([ext, name, category]) => ({
    id: ext, name, filename: course.name + ' · ' + name + '（格式样例）.' + ext,
    category, location: '格式样例'
  }))
  return [...notes, ...documents].map(item => ({
    ...item, origin: 'demo', courseId: String(course.id), courseName: course.name, version: 1
  }))
}

function publishedEntry(course, item) {
  return {
    origin: 'live', id: item.id, version: item.version, courseId: String(course.id), courseName: course.name,
    filename: item.filename, name: item.filename, category: '发布资料', location: '已发布 · 版本 ' + item.version
  }
}

export async function studyMaterials(session, course) {
  if (session.mode === 'demo') return materialsForDemo(demoCourse(course.id))
  const { data } = await request({ url: coursePath(course.id) + '/materials' })
  if (!Array.isArray(data)) throw new Error('资料列表格式有误')
  return data.filter(item => item.published && item.parseStatus === 'READY').map(item => publishedEntry(course, item))
}

export async function studyMaterial(session, course, id, version) {
  if (session.mode === 'demo') {
    const material = materialsForDemo(demoCourse(course.id)).find(item => item.id === id && item.version === version)
    if (!material) throw new Error('演示资料不可用')
    return material
  }
  const { data } = await request({ url: materialPath(course.id, id) })
  if (!data?.published || data.parseStatus !== 'READY') throw new Error('资料尚未发布或已下架')
  if (data.version !== version) throw new Error('资料已更新，请重新打开最新版本')
  return publishedEntry(course, data)
}

export function sourceLocation(source) {
  if (source.slideNumber) return '第 ' + source.slideNumber + ' 张幻灯片'
  if (source.sheetName) return source.sheetName + (source.cellRange ? ' · ' + source.cellRange : '')
  if (source.pageNumber) return '第 ' + source.pageNumber + ' 页'
  if (source.paragraphStart) {
    const end = source.paragraphEnd
    return (source.heading ? source.heading + ' · ' : '') + '第 ' + source.paragraphStart
      + (end && end !== source.paragraphStart ? '–' + end : '') + ' 段'
  }
  return source.location || '原文片段'
}

function demoSources(course) {
  const files = materialsForDemo(course)
  const first = files[0]
  const slides = files.find(item => item.id === 'pptx')
  return [
    { ...first, chunkId: 'demo-note', materialId: first.id, paragraphStart: 1 },
    { ...slides, chunkId: 'demo-slides', materialId: slides.id, slideNumber: 2,
      text: '引用定位保留资料名与原文位置，支持核对检索依据。' }
  ]
}

export function exampleQuestions(session, course) {
  if (session.mode !== 'demo') return []
  return [demoCourse(course.id).prompt, citationPrompt]
}

export async function studyAnswer(session, course, question) {
  if (session.mode === 'demo') {
    const sample = demoCourse(course.id)
    const [note, slides] = demoSources(sample)
    const normalize = value => value.replace(/[\s?？。！!]/g, '')
    if (normalize(question) === normalize(sample.prompt)) {
      return { status: 'demo', answer: sample.answer, sources: [note] }
    }
    if (normalize(question) === normalize(citationPrompt)) {
      return { status: 'demo', answer: '可通过资料名和引用位置打开课件，核对对应页面。[1]', sources: [slides] }
    }
    return { status: 'no_evidence', answer: '演示资料未找到相关依据，可尝试示例问题。', sources: [] }
  }
  const { data } = await request({ url: coursePath(course.id) + '/ask', method: 'POST', data: { question }, timeout: 75000 })
  if (typeof data?.answer !== 'string') throw new Error('问答响应格式有误')
  const citations = Array.isArray(data.citations) && data.citations.length ? data.citations : (data.evidence || []).map(item => item.source)
  return { ...data, sources: citations.filter(source => source?.chunkId) }
}

export async function studySource(session, course, chunkId, expected) {
  let source
  if (session.mode === 'demo') source = demoSources(demoCourse(course.id)).find(item => item.chunkId === chunkId)
  else ({ data: source } = await request({ url: coursePath(course.id) + '/sources/' + encodeURIComponent(chunkId) }))
  if (!source || source.chunkId !== chunkId || String(source.courseId) !== String(course.id) ||
      (expected && (expected.materialId !== source.materialId || expected.version !== source.version))) {
    throw new Error('引用已失效，请重新提问')
  }
  return source
}

export async function studyPreview(entry, offset = 0) {
  const query = '?version=' + entry.version + '&offset=' + offset
  const { data } = await request({ url: materialPath(entry.courseId, entry.id) + '/preview' + query })
  if (!Array.isArray(data?.sources)) throw new Error('资料预览响应格式有误')
  return data
}

export function demoPreview(entry) {
  const format = extensionOf(entry.filename)
  if (!['pdf', 'ppt', 'pptx'].includes(format)) return []
  return [1, 2].map(page => ({
    image: '/static/study-preview/' + format + '-' + page + (format === 'pdf' ? '.png' : '.jpg'),
    label: '第 ' + page + (format === 'pdf' ? ' 页' : ' 张')
  }))
}

export function materialUrl(entry, chunkId) {
  const pairs = [['origin', entry.origin], ['id', entry.id], ['courseId', entry.courseId], ['version', entry.version], ['chunk', chunkId]]
  return '/pages/library/reader?' + pairs.filter(([, value]) => value != null && value !== '')
    .map(([key, value]) => key + '=' + encodeURIComponent(value)).join('&')
}

function requestOriginal(entry, session) {
  const url = config.baseUrl + materialPath(entry.courseId, entry.id) + '/file?version=' + entry.version
  return new Promise((resolve, reject) => {
    uni.request({
      url, header: { Authorization: 'Bearer ' + session.token }, responseType: 'arraybuffer',
      timeout: 25000,
      success(response) {
        if (getToken() !== session.token) return reject(new Error('登录状态已变化，请重新打开资料'))
        const mime = Object.entries(response.header || {}).find(([name]) => name.toLowerCase() === 'content-type')?.[1] || ''
        if (response.statusCode === 200 && mime.includes('application/octet-stream')) return resolve(response.data)
        let error
        try {
          const bytes = new Uint8Array(response.data).slice(0, 2048)
          const encoded = Array.from(bytes, char => '%' + char.toString(16).padStart(2, '0')).join('')
          error = JSON.parse(decodeURIComponent(encoded))
        } catch {}
        if (response.statusCode === 401 || error?.code === 401) removeToken()
        reject(new Error(error?.msg || '无法下载当前版本的资料'))
      },
      fail() { reject(new Error('无法下载资料，请检查连接')) }
    })
  })
}

export async function originalFile(entry, session) {
  if (entry.file) return { path: entry.file, owned: false }
  if (entry.origin === 'live') {
    const bytes = await requestOriginal(entry, session)
    return { path: await makeFile(entry.filename, bytes), owned: true }
  }
  const fixture = entry.origin === 'demo' ? demoFiles[entry.id] : null
  const path = fixture ? await makeFile(entry.filename, fixture.base64, 'base64')
    : await makeFile(entry.filename, entry.text || '', 'utf8')
  return { path, owned: true }
}
