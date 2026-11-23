// Week-1 stand-in for business APIs that B and E have not delivered.
// Field names follow the finalized prototype so the pages can be swapped onto real responses.

export const materials = [
  {
    name: '残差连接讲义.pdf',
    chapter: '第 4 章 深层网络',
    type: 'PDF',
    parse: '成功',
    parseKind: 'ok',
    locate: '12 页 / 28 片段',
    publish: '已发布',
    publishKind: 'ok',
  },
  {
    name: '反向传播笔记.docx',
    chapter: '第 3 章 训练方法',
    type: 'DOCX',
    parse: '成功',
    parseKind: 'ok',
    locate: '按段落切分',
    publish: '未发布',
    publishKind: 'muted',
  },
  {
    name: '正则化与过拟合.txt',
    chapter: '第 3 章 训练方法',
    type: 'TXT',
    parse: '解析中',
    parseKind: 'muted',
    locate: '—',
    publish: '不可发布',
    publishKind: 'muted',
  },
  {
    name: 'CNN 卷积讲义.pdf',
    chapter: '第 5 章 卷积网络',
    type: 'PDF',
    parse: '失败',
    parseKind: 'bad',
    locate: '扫描件，未提取到文本',
    publish: '不可发布',
    publishKind: 'muted',
  },
]

export const questions = [
  {
    stem: '残差连接主要通过什么缓解深层网络训练困难？',
    type: '单选',
    topic: '残差连接',
    chapter: '第 4 章',
    status: '已发布',
    options: ['A. 增加网络宽度', 'B. 引入快捷连接，缓解梯度消失', 'C. 减少训练数据'],
    answer: 'B',
    analysis: '残差连接；对应第 4 章讲义第 6 页',
  },
  {
    stem: 'Dropout 可以用于减轻过拟合。',
    type: '判断',
    topic: '过拟合',
    chapter: '第 3 章',
    status: '已发布',
    options: ['A. 正确', 'B. 错误'],
    answer: 'A',
    analysis: 'Dropout 在训练时随机丢弃神经元，用于减轻过拟合。',
  },
  {
    stem: '下列哪些方法有助于缓解梯度消失？',
    type: '多选',
    topic: '梯度消失',
    chapter: '第 4 章',
    status: '草稿',
    options: ['A. 残差连接', 'B. 合理的权重初始化', 'C. 单纯堆叠无快捷连接的深层网络'],
    answer: 'A, B',
    analysis: '残差连接和合适的初始化有助于梯度传播。',
  },
]

export const qaRecords = [
  {
    question: '残差连接怎样帮助训练深层网络？',
    answerState: '已生成，附来源',
    answerKind: 'ok',
    source: '残差连接讲义.pdf，第 6 页',
    feedback: '有帮助 18',
    feedbackKind: 'ok',
    filter: '有帮助',
    answer: '残差连接通过快捷通路把浅层特征直接传到深层，缓解梯度消失，使更深的网络更易训练。',
    detail: '来源：残差连接讲义.pdf · 第 4 章 / 第 6 页。反馈：有帮助 18。',
  },
  {
    question: '梯度消失如何缓解？',
    answerState: '未找到依据',
    answerKind: 'muted',
    source: '—',
    feedback: '反馈问题 3',
    feedbackKind: 'bad',
    filter: '未找到依据',
    answer: '',
    detail: '',
  },
  {
    question: '反向传播的计算顺序是什么？',
    answerState: '仅返回原文（模型不可用）',
    answerKind: '',
    source: '反向传播笔记.docx，第 2 节',
    feedback: '无反馈',
    feedbackKind: 'muted',
    filter: '无反馈',
    answer: '模型不可用，仅返回检索到的原文。',
    detail: '来源：反向传播笔记.docx · 第 3 章 / 第 2 节。反馈：无反馈。',
  },
]

export const hotQuestions = [
  { question: '残差连接怎样帮助训练深层网络？', count: 46 },
  { question: '反向传播的计算顺序是什么？', count: 33 },
  { question: '如何预防过拟合？', count: 27 },
  { question: '梯度消失如何缓解？', count: 21 },
]

export const chapterAccuracy = [
  { chapter: '第 1 章 绪论', rate: '88%', attempts: 96, low: false },
  { chapter: '第 2 章 基础模型', rate: '81%', attempts: 90, low: false },
  { chapter: '第 3 章 训练方法', rate: '58%', attempts: 88, low: true },
  { chapter: '第 4 章 深层网络', rate: '73%', attempts: 84, low: false },
  { chapter: '第 5 章 卷积网络', rate: '69%', attempts: 60, low: false },
]

export const materialVisits = [
  { name: '残差连接讲义.pdf', visits: 820, saves: 54 },
  { name: '反向传播笔记.docx', visits: 611, saves: 41 },
  { name: '优化方法概览.pdf', visits: 390, saves: 22 },
]

export const studentRecords = [
  { name: '陈用礼', recent: '残差连接讲义，今天 14:02', rate: '82%', asks: 6 },
  { name: '张涵', recent: '第 3 章练习，昨天 21:16', rate: '61%', asks: 4 },
  { name: '洪少敏', recent: '课程问答，昨天 09:40', rate: '74%', asks: 9 },
]

export const sampleUsers = [
  { name: '卢冰玲', account: 'lubl@szu.edu.cn', role: '教师', scope: '深度学习、计算机视觉', enabled: true },
  { name: '思佳怡', account: 'sijy@szu.edu.cn', role: '系统管理员', scope: '平台', enabled: true },
  { name: '张涵', account: 'zhangh@szu.edu.cn', role: '学生', scope: '已加入深度学习', enabled: true },
]

export const sampleChapters = [
  { title: '第 1 章 绪论', status: '已发布', ok: true },
  { title: '第 3 章 训练方法', status: '已发布', ok: true },
  { title: '第 4 章 深层网络', status: '已发布', ok: true },
  { title: '第 5 章 卷积网络', status: '草稿', ok: false },
]
