import request from '../utils/request.js'

export function getCourses() {
  return request({ url: '/campusai/courses' })
}

export function getCourse(id) {
  return request({ url: '/campusai/courses/' + encodeURIComponent(id) })
}
