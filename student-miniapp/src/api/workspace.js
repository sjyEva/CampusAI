import { extensionOf, fileTypes, forgetFile, keepSelectedFile, makeFile, newId, selectedText } from '../utils/files.js'

export const categories = ['课件', '讲义', '习题', '课堂笔记', '复习笔记', '其他']
export const feedbackKinds = ['课程内容', '资料问题', '教学建议', '其他']

export function entryKey(entry) {
  return entry.origin === 'personal' ? 'personal:' + entry.id
    : [entry.origin, entry.courseId, entry.id, entry.version].join(':')
}

export function localDate(timestamp) {
  const date = new Date(timestamp)
  if (Number.isNaN(date.getTime())) return ''
  return [date.getFullYear(), date.getMonth() + 1, date.getDate()].join('/')
}

function storageKey(session) {
  if (!session?.owner) throw new Error('未确定学生身份')
  return 'CampusAI-Workspace-v1:' + session.owner
}

function initialWorkspace(session) {
  const empty = { profile: { nickname: session.nickname, avatar: '' }, todos: [], collapsed: {}, library: [], favorites: [], feedback: [] }
  if (session.mode !== 'demo') return empty
  const date = new Date(2026, 10, 25).getTime()
  const sample = {
    id: 'demo-note', origin: 'personal', kind: 'note', name: '需求分析复盘',
    filename: '需求分析复盘.txt', courseId: '2', courseName: '软件工程', category: '复习笔记',
    text: '验收标准应写成可观测、可核对的条件。\n复习时先写功能需求，再补上响应时间与异常处理要求。',
    editableText: true, updatedAt: date
  }
  return {
    ...empty,
    todos: [
      { id: 'review', text: '复习软件需求与分析', done: false },
      { id: 'file', text: '下载计算机导论课件', done: false },
      { id: 'practice', text: '整理数据结构笔记', done: true }
    ],
    library: [sample],
    favorites: [sample],
    feedback: [{
      id: 'demo-feedback', courseId: '2', courseName: '软件工程', kind: '教学建议',
      text: '希望增加一份带实际案例的需求分析讲义。', createdAt: date
    }]
  }
}

export function getWorkspace(session) {
  return uni.getStorageSync(storageKey(session)) || initialWorkspace(session)
}

function changeWorkspace(session, change) {
  const snapshot = getWorkspace(session)
  change(snapshot)
  try {
    uni.setStorageSync(storageKey(session), snapshot)
  } catch {
    throw new Error('本机存储写入失败，请检查可用空间后重试')
  }
  return snapshot
}

export function setSection(session, section, collapsed) {
  if (!['todo', 'courses'].includes(section)) throw new Error('未知首页区块')
  return changeWorkspace(session, data => { data.collapsed[section] = Boolean(collapsed) })
}

export function saveTodo(session, patch) {
  return changeWorkspace(session, data => {
    const item = data.todos.find(todo => todo.id === patch.id)
    if (patch.id && !item) throw new Error('待办已不存在')
    if (patch.text != null) {
      const text = patch.text.trim()
      if (!text || text.length > 64) throw new Error('请输入 1 至 64 字的待办内容')
      if (!item) data.todos.push({ id: newId(), text, done: false })
      else item.text = text
    }
    if (item && typeof patch.done === 'boolean') item.done = patch.done
  })
}

export function removeTodo(session, id) {
  return changeWorkspace(session, data => { data.todos = data.todos.filter(item => item.id !== id) })
}

export function moveTodo(session, id, direction) {
  return changeWorkspace(session, data => {
    const from = data.todos.findIndex(item => item.id === id)
    const to = from + direction
    if (from < 0 || to < 0 || to >= data.todos.length || Math.abs(direction) !== 1) return
    const [item] = data.todos.splice(from, 1)
    data.todos.splice(to, 0, item)
  })
}

export function saveProfile(session, profile) {
  const nickname = profile.nickname.trim()
  if (!nickname || nickname.length > 20) throw new Error('请输入 1 至 20 字的昵称')
  return changeWorkspace(session, data => { data.profile = { nickname, avatar: profile.avatar || '' } })
}

export function setFavorite(session, entry, enabled) {
  const key = entryKey(entry)
  return changeWorkspace(session, data => {
    data.favorites = data.favorites.filter(item => entryKey(item) !== key)
    if (enabled) {
      const { id, origin, name, filename, courseId, courseName, version, category } = entry
      data.favorites.unshift({ id, origin, name, filename, courseId, courseName, version, category })
    }
  })
}

export function favoriteEntries(workspace) {
  const personal = new Map(workspace.library.map(entry => [entry.id, entry]))
  return workspace.favorites.flatMap(item => {
    if (item.origin !== 'personal') return [item]
    return personal.has(item.id) ? [personal.get(item.id)] : []
  })
}

export async function savePersonalEntry(session, form, picked) {
  const old = form.id ? getWorkspace(session).library.find(entry => entry.id === form.id) : null
  if (form.id && (!old || old.updatedAt !== form.updatedAt)) throw new Error('资料已更新，请重新打开后编辑')
  const kind = old?.kind || form.kind
  const name = form.name.trim() || (!old && kind === 'file' ? picked?.name : '')
  if (!name || name.length > 100) throw new Error('请输入 1 至 100 字的名称')
  if (!categories.includes(form.category)) throw new Error('请选择资料分类')
  const text = typeof form.text === 'string' ? form.text : ''
  if (text.length > 12000) throw new Error('正文最多 12,000 字')
  if (kind === 'note' && !text.trim()) throw new Error('请填写笔记正文')
  if (kind !== 'note' && kind !== 'file') throw new Error('未知资料类型')
  if (picked && (!fileTypes.includes(extensionOf(picked.name)) || picked.size <= 0 || picked.size > 10 * 1024 * 1024)) {
    throw new Error('文件格式或大小不符合要求')
  }
  if (kind === 'file' && !old?.file && !picked) throw new Error('请选择文件')

  const id = old?.id || newId()
  const item = {
    id, origin: 'personal', kind, name, filename: old?.filename, file: old?.file,
    text: old?.text || '', editableText: old?.editableText || false,
    courseId: String(form.courseId || ''), courseName: form.courseName || '', category: form.category,
    updatedAt: Math.max(Date.now(), (old?.updatedAt || 0) + 1)
  }
  if (kind === 'note') {
    item.filename = name.replace(/[\\/:*?"<>|]/g, '_') + '.txt'
    item.text = text.trim()
    item.editableText = true
  } else if (picked) {
    Object.assign(item, await selectedText(picked))
    item.filename = picked.name
    item.file = await keepSelectedFile(picked)
  } else if (item.editableText && form.text != null && text !== old.text) {
    item.file = await makeFile(item.filename, text, 'utf8')
    item.text = text
  }
  try {
    changeWorkspace(session, data => {
      if (old && data.library.find(entry => entry.id === id)?.updatedAt !== old.updatedAt) {
        throw new Error('资料已更新，请重新打开后编辑')
      }
      data.library = [item, ...data.library.filter(entry => entry.id !== id)]
    })
  } catch (error) {
    if (item.file !== old?.file) await forgetFile(item.file)
    throw error
  }
  if (old?.file && item.file !== old.file) await forgetFile(old.file)
  return item
}

export function submitFeedback(session, course, kind, content) {
  const text = content.trim()
  if (!feedbackKinds.includes(kind)) throw new Error('请选择反馈分类')
  if (text.length < 8 || text.length > 500) throw new Error('请填写 8 至 500 字的反馈')
  return changeWorkspace(session, data => {
    data.feedback.unshift({ id: newId(), courseId: String(course.id), courseName: course.name, kind, text, createdAt: Date.now() })
  })
}

export function searchEntries(entries, query) {
  const words = query.trim().toLowerCase().split(/\s+/).filter(Boolean)
  if (!words.length) return entries
  return entries.filter(entry => {
    const fields = [entry.name, entry.filename, entry.courseName, entry.category, entry.text].join(' ').toLowerCase()
    return words.every(word => fields.includes(word))
  })
}
