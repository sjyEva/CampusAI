import request from './request.js'

export function listCourses(token, signal) {
  return request({ url: '/campusai/courses', token, signal })
}

export function getCourse(token, id, signal) {
  return request({ url: `/campusai/courses/${encodeURIComponent(id)}`, token, signal })
}

export function listChapters(token, id, signal) {
  return request({ url: `/campusai/courses/${encodeURIComponent(id)}/chapters`, token, signal })
}
