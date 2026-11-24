# PR3 会话修复与验证

日期：2026-11-24。修复基于 PR3 提交 `38ce3b2`，位于本地 `bling` 分支，尚未提交。

## 本次范围

只修复以下两项，不调整模拟业务页面、课程筛选行为或示例提示：

1. 退出/切换账号、重复加载后，旧课程请求不能覆盖新请求的数据、错误信息或加载状态；旧请求的 401 不能清除新账号的会话。
2. 课程加载失败后点击“重新加载”，若当前会话已失效，必须清理登录与课程状态并返回登录页，不能显示为“没有可见课程”。

实现位置：

| 文件 | 变化 |
| --- | --- |
| `admin-web/src/composables/courseScope.js` | 重置或新加载时取消旧请求；写入状态前检查请求身份与当前令牌；旧请求结束不影响新请求的 loading |
| `admin-web/src/composables/courseLoader.js` | 首次加载和重试共用的加载函数，统一处理当前会话的 401 |
| `admin-web/src/utils/authError.js` | 清理会话前核对错误所属请求的令牌，忽略前一账号的 401 |
| `admin-web/src/layouts/AdminLayout.vue` | 使用统一加载函数；点击退出时立即使旧课程请求失效 |
| `admin-web/src/views/CoursesView.vue` | 重试按钮使用统一加载函数；章节详情的 401 也携带发起请求时的令牌，避免旧详情请求误退出新账号 |
| `admin-web/tests/course-session.test.mjs` | 13 项可重复运行的回归测试 |
| `admin-web/package.json` | 增加 `npm test`，无需新增依赖 |

## 1. 自动验证

```powershell
Set-Location 'E:\School Study\Software_Engineering\期末大作业\CampusAI-pr3\admin-web'
npm.cmd test
npm.cmd run build
```

本机已经安装依赖。其他组员首次验证可先执行 `npm.cmd ci`，Node.js 要求至少 22.12.0。

测试预期末尾显示：

```text
# tests 13
# pass 13
# fail 0
```

测试直接调用真实的课程状态模块、加载函数、请求封装与 401 处理代码，用可控 fetch 模拟网络，不需要启动后端，也不调用 DeepSeek。

覆盖：

- admin 的旧成功响应在 teacher 登录并加载成功后返回，teacher 的课程和选中项不变。
- 旧请求分别返回业务码 401、HTTP 401、网络错误，不清除新账号的会话和课程。
- 退出后迟到的响应不重新填入课程。
- 令牌变化但未显式重置时，旧响应也不写入。
- 新请求仍在等待时，旧请求结束不能关闭其加载状态。
- 401 进入处理函数前已经换号时，不误退出新账号。
- 网络失败后重试，分别收到 HTTP 200 + 业务码 401、HTTP 401，均清理状态并跳回登录。
- 网络失败后正常重试能够恢复；503 不会错误退出；无令牌时不发请求。

其中模拟延迟响应的 fetch 故意不响应取消，确保即使响应或解析工作已经排队，旧数据仍无法写入。测试中的其他教师课程为合成数据，不修改数据库。

本轮结果：13/13 通过；`npm.cmd run build` 通过；构建仍有原有的主 JS 体积提示，不影响本次修复。自动测试属于模块回归验证，不替代浏览器真实交互验收。

## 2. 启动页面

如果 PR2 后端仍按默认端口 18080 运行，可以复用它：

```powershell
Set-Location 'E:\School Study\Software_Engineering\期末大作业\CampusAI-pr3\admin-web'
$env:CAMPUSAI_BACKEND_URL = 'http://127.0.0.1:18080'
npm.cmd run dev
```

浏览器打开终端显示的地址。前端已经启动过时，先 Ctrl+C 停止，再设置变量并重启。若后端实际使用其他端口，同步替换上面的地址。默认演示密码为 `CampusAI-Dev-2026`。

## 3. 浏览器查看“重试收到 401”

1. 使用 teacher 登录，进入“课程与章节”。
2. 打开开发者工具，在 Network request blocking（网络请求阻止）中添加 `*/api/campusai/courses` 并启用，只阻止课程列表请求。
3. 刷新页面。身份接口仍能访问，课程列表应显示连接错误和“重新加载”按钮。
4. 关闭这条请求阻止规则，但暂时不要点击重试或刷新页面。
5. 在 Console 执行下面的代码。它只在后端撤销当前演示会话，保留页面里的旧登录态，用来模拟会话到期；不会输出令牌。

```javascript
await fetch('/api/logout', {
  method: 'POST',
  headers: {
    Authorization: 'Bearer ' + sessionStorage.getItem('campusai-admin-token')
  }
}).then(response => response.json())
```

6. 确认响应 `code` 为 200，再点击页面“重新加载”。
7. 预期自动回到登录页，而不是显示“没有可见课程”。Console 执行 `sessionStorage.getItem('campusai-admin-token') === null` 应得到 `true`。
8. 使用 teacher 再次登录，课程列表应能恢复。

此步骤只撤销当前浏览器标签页使用的演示会话，不停止 PR2 后端，也不影响模型密钥。

## 4. 浏览器查看“旧请求晚到”

自动测试已经稳定覆盖该竞态。下面的可选步骤能直接在页面上观察。

1. 退出到登录页，不要刷新；打开 Console 执行以下脚本。它只挂起下一次课程列表响应，其余请求正常执行。

```javascript
(() => {
  const originalFetch = window.fetch
  let captured = false
  let release
  window.campusAiReview = {
    release: () => release?.(),
    restore: () => { window.fetch = originalFetch }
  }
  window.fetch = async (url, options) => {
    const capture = !captured && String(url).endsWith('/api/campusai/courses')
    if (capture) captured = true
    const response = await originalFetch.call(window, url, options)
    if (!capture) return response
    const detached = new Response(await response.text(), {
      status: response.status,
      headers: response.headers
    })
    console.log('旧课程响应已挂起，现在可以退出并切换账号')
    await new Promise(resolve => { release = resolve })
    return detached
  }
})()
```

2. 使用 admin 登录，看到 Console 的“旧课程响应已挂起”后，立即退出，再用 teacher 登录。不要刷新页面，否则挂起的请求会随页面销毁。
3. teacher 的课程列表加载完成后，执行 `window.campusAiReview.release()`，释放 admin 的旧响应。
4. 预期 teacher 保持登录，角色与课程列表不被旧响应覆盖，没有突然跳回登录页。
5. 执行 `window.campusAiReview.restore(); delete window.campusAiReview` 恢复 fetch，或者刷新页面恢复。

默认演示库的 admin 与 teacher 都能看到 CS101、SE101，单凭课程名称不一定能看出区别；自动测试专门给两个账号提供不同课程，以检验实际隔离。若想观察旧 401 不会退出新账号，把脚本中的 `const detached = new Response(...)` 整段替换为下面代码，重复以上步骤：

```javascript
const detached = new Response(JSON.stringify({ code: 401, msg: '模拟旧会话失效' }), {
  status: 200,
  headers: { 'Content-Type': 'application/json' }
})
```

## 5. 提交前核对

```powershell
Set-Location 'E:\School Study\Software_Engineering\期末大作业\CampusAI-pr3'
git status --short --branch
git diff --check
git diff -- admin-web
```

本地 `bling` 跟踪 `origin/bling`，对应现有 PR3 分支。`git diff` 默认不显示未跟踪文件内容；提交时还需要包含新建的 `courseLoader.js`、测试目录以及本文档。本轮没有执行 git add、commit、push 或 merge。
