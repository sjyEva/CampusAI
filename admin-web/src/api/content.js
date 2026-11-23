import {
  chapterAccuracy,
  hotQuestions,
  materialVisits,
  materials,
  qaRecords,
  questions,
  sampleUsers,
  studentRecords,
} from '../mock/sample.js'

// Paths below are the intended REST contracts. Week 1 returns prototype samples
// until A/B expose the corresponding endpoints. Swap the body for request() when they do.

function mock(path, data) {
  return Promise.resolve({ mocked: true, path, data })
}

export function listMaterials() {
  return mock('GET /campusai/materials', materials)
}

export function listQuestions() {
  return mock('GET /campusai/questions', questions)
}

export function listQaRecords() {
  return mock('GET /campusai/qa', qaRecords)
}

export function getLearningStats() {
  return mock('GET /campusai/stats', {
    courseVisits: 1284,
    visitors: 96,
    enrolled: 120,
    // People sum to visitors; visits sum to courseVisits.
    visitFrequency: [
      { label: '几乎天天来', people: 25, visits: 925 },
      { label: '每周来几次', people: 40, visits: 312 },
      { label: '只来过 1–2 次', people: 31, visits: 47 },
    ],
    materialVisitCount: 3906,
    exercises: 418,
    accuracy: '76%',
    questions: 215,
    groundedRate: '81%',
    hotQuestions,
    chapterAccuracy,
    materialRows: materialVisits,
    studentRecords,
  })
}

export function listUsers() {
  return mock('GET /system/users', sampleUsers)
}
