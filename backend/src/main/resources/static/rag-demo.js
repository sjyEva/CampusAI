(() => {
  "use strict";

  const byId = (id) => document.getElementById(id);
  const ui = Object.fromEntries([
    "login-panel", "login-form", "username", "password", "login-button", "login-message",
    "session-info", "session-name", "logout-button", "welcome-note", "workspace",
    "course-select", "course-description", "course-empty", "material-count", "refresh-materials",
    "upload-form", "upload-file", "upload-filename", "upload-button", "materials-loading",
    "materials-list", "materials-empty", "materials-empty-text", "materials-message",
    "question-form", "question", "question-count", "question-message", "ask-button", "search-button",
    "model-badge", "model-note", "answer-empty", "answer-loading", "answer-content", "toast"
  ].map((id) => [id, byId(id)]));
  const SESSION_KEY = "campusai.rag-demo.session";
  const state = {
    token: readSession(), user: null, role: null, courses: [], courseId: null,
    epoch: 0, authEpoch: 0, materialRequestId: 0, busy: false, mutating: false, requests: new Map(), toastTimer: null
  };
  const roleLabels = { teacher: "教师", student: "学生", admin: "管理员" };
  const fallbackReasons = {
    unconfigured: "当前暂未配置模型，仅返回课程原文。",
    timeout: "模型响应超时，已保留可以核对的课程原文。",
    provider_error: "模型服务暂不可用，已保留相关原文。",
    invalid_response: "模型未返回有效回答，请以检索原文为准。",
    invalid_citation: "模型返回的引用未通过核对，已改为展示原文。",
    insufficient_evidence: "当前依据不足以生成回答，请进一步核对原文或补充问题。",
    interrupted: "本次生成中断，请核对原文或稍后重试。"
  };

  class ApiError extends Error {
    constructor(message, code) { super(message); this.name = "ApiError"; this.code = code; }
  }

  function element(tag, className, text) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (text !== undefined && text !== null) node.textContent = String(text);
    return node;
  }

  function actionButton(label, handler, className = "text-button") {
    const button = element("button", className, label);
    button.type = "button";
    button.addEventListener("click", handler);
    return button;
  }

  function readSession() {
    try { return sessionStorage.getItem(SESSION_KEY) || ""; }
    catch (_) { return ""; }
  }

  function saveSession(token) {
    state.token = token;
    try {
      if (token) sessionStorage.setItem(SESSION_KEY, token);
      else sessionStorage.removeItem(SESSION_KEY);
    } catch (_) { /* The page also works with an in-memory session. */ }
  }

  function message(node, text) {
    node.textContent = text || "";
    node.hidden = !text;
  }

  function toast(text, isError = false) {
    clearTimeout(state.toastTimer);
    ui.toast.textContent = text;
    ui.toast.classList.toggle("is-error", isError);
    ui.toast.hidden = false;
    state.toastTimer = setTimeout(() => { ui.toast.hidden = true; }, isError ? 6500 : 4200);
  }

  function abortRequests(courseOnly = false) {
    for (const [controller, isCourseRequest] of state.requests) {
      if (!courseOnly || isCourseRequest) controller.abort();
    }
  }

  async function api(path, options = {}) {
    const {
      method = "GET", body, authenticated = true, courseScoped = true,
      timeout = 45000, download = false
    } = options;
    const controller = new AbortController();
    let timedOut = false;
    state.requests.set(controller, courseScoped);
    const timer = setTimeout(() => { timedOut = true; controller.abort(); }, timeout);
    const headers = { Accept: download ? "application/octet-stream, application/json" : "application/json" };
    if (authenticated && state.token) headers.Authorization = `Bearer ${state.token}`;
    let requestBody = body;
    if (body !== undefined && !(body instanceof FormData)) {
      headers["Content-Type"] = "application/json";
      requestBody = JSON.stringify(body);
    }
    try {
      const response = await fetch(path, {
        method, headers, body: requestBody, signal: controller.signal,
        cache: "no-store", credentials: "same-origin"
      });
      const isJson = (response.headers.get("content-type") || "").includes("json");
      if (download && response.ok && !isJson) return await response.blob();
      let payload;
      try { payload = await response.json(); }
      catch (_) { throw new ApiError("服务返回异常，请稍后重试。", response.status); }
      const code = Number(payload.code);
      if (!response.ok || code !== 200) {
        if ((code === 401 || response.status === 401) && authenticated) {
          resetSession("登录已过期，请重新登录。");
        }
        throw new ApiError(payload.msg || "操作未完成，请稍后重试。", code || response.status);
      }
      if (download) throw new ApiError("未获取到资料文件，请刷新后重试。", 500);
      return payload;
    } catch (error) {
      if (timedOut) throw new ApiError("等待服务响应超时，请稍后重试。", 408);
      if (error.name === "AbortError" || error instanceof ApiError) throw error;
      throw new ApiError("连接服务失败，请确认服务仍在运行后重试。", 0);
    } finally {
      clearTimeout(timer);
      state.requests.delete(controller);
    }
  }

  function coursePath(suffix = "", courseId = state.courseId) {
    return `/campusai/courses/${encodeURIComponent(courseId)}/rag${suffix}`;
  }

  function current(epoch) { return epoch === state.epoch && Boolean(state.token); }
  function canManage() { return state.role === "teacher" || state.role === "admin"; }
  function isAbort(error) { return error.name === "AbortError"; }

  function resetAnswer() {
    ui["answer-content"].replaceChildren();
    ui["answer-content"].hidden = true;
    ui["answer-empty"].hidden = false;
    ui["answer-loading"].hidden = true;
    message(ui["question-message"], "");
  }

  function updateQuestionControls() {
    const disabled = !state.courseId || !state.token || state.busy || state.mutating || !ui.question.value.trim();
    ui["ask-button"].disabled = disabled;
    ui["search-button"].disabled = disabled;
    ui["question-count"].textContent = `${ui.question.value.length} / 500`;
    ui.question.disabled = state.busy || state.mutating || !state.courseId;
    document.querySelectorAll("[data-question]").forEach((button) => {
      button.disabled = state.busy || state.mutating || !state.courseId;
    });
  }

  function setQueryBusy(busy, mode = "ask") {
    state.busy = busy;
    ui["ask-button"].querySelector("span").textContent = busy && mode === "ask" ? "正在查找…" : "提问并核对";
    ui["search-button"].textContent = busy && mode === "search" ? "正在检索…" : "只检索原文";
    updateQuestionControls();
  }

  function updateMaterialControls() {
    ui["course-select"].disabled = state.mutating || !state.courses.length;
    ui["upload-file"].disabled = state.mutating || !state.courseId;
    ui["upload-button"].disabled = state.mutating || !state.courseId || !ui["upload-file"].files.length;
    ui["upload-button"].textContent = state.mutating ? "正在处理资料…" : "上传并解析";
    ui["refresh-materials"].disabled = state.mutating || !state.courseId;
    ui["materials-list"].querySelectorAll("button").forEach((button) => {
      button.disabled = state.mutating;
    });
    updateQuestionControls();
  }

  function invalidateResults() {
    state.epoch += 1;
    abortRequests(true);
    setQueryBusy(false);
    resetAnswer();
  }

  function resetSession(reason = "") {
    state.authEpoch += 1;
    state.epoch += 1;
    abortRequests();
    saveSession("");
    Object.assign(state, { user: null, role: null, courses: [], courseId: null, busy: false, mutating: false });
    document.body.classList.remove("is-authenticated");
    ui["login-panel"].hidden = false;
    ui["workspace"].hidden = true;
    ui["session-info"].hidden = true;
    ui["welcome-note"].hidden = true;
    ui.password.value = "";
    ui["login-button"].disabled = false;
    ui["login-button"].querySelector("span").textContent = "进入工作台";
    ui["materials-list"].replaceChildren();
    ui["course-select"].replaceChildren(element("option", "", "请先登录"));
    ui["upload-file"].value = "";
    ui["upload-filename"].textContent = "选择一份课程资料";
    ui.question.value = "";
    resetAnswer();
    updateQuestionControls();
    message(ui["login-message"], reason);
  }

  async function restoreWorkspace(authEpoch) {
    const [profile, courses] = await Promise.all([
      api("/getInfo", { courseScoped: false }),
      api("/campusai/courses", { courseScoped: false })
    ]);
    if (authEpoch !== state.authEpoch) return;
    state.user = profile.user;
    state.role = profile.roles?.[0];
    state.courses = Array.isArray(courses.data) ? courses.data : [];
    ui["session-name"].textContent = `${roleLabels[state.role] || "用户"} · ${state.user?.nickName || state.user?.userName || "已登录"}`;
    document.body.classList.add("is-authenticated");
    ui["login-panel"].hidden = true;
    ui["workspace"].hidden = false;
    ui["session-info"].hidden = false;
    ui["welcome-note"].hidden = false;
    ui["upload-form"].hidden = !canManage();
    ui["course-select"].replaceChildren();
    for (const course of state.courses) {
      const option = element("option", "", course.name);
      option.value = String(course.id);
      ui["course-select"].append(option);
    }
    if (!state.courses.length) {
      const option = element("option", "", "暂无可访问课程");
      option.value = "";
      ui["course-select"].append(option);
    }
    await selectCourse(state.courses.length ? String(state.courses[0].id) : "");
  }

  ui["login-form"].addEventListener("submit", async (event) => {
    event.preventDefault();
    if (ui["login-button"].disabled) return;
    const authEpoch = ++state.authEpoch;
    message(ui["login-message"], "");
    ui["login-button"].disabled = true;
    ui["login-button"].querySelector("span").textContent = "正在登录…";
    try {
      const result = await api("/login", {
        method: "POST", body: { username: ui.username.value, password: ui.password.value },
        authenticated: false, courseScoped: false
      });
      if (authEpoch !== state.authEpoch) return;
      if (typeof result.token !== "string" || !result.token) throw new ApiError("登录未返回有效会话，请重试。", 500);
      saveSession(result.token);
      ui.password.value = "";
      await restoreWorkspace(authEpoch);
    } catch (error) {
      if (!isAbort(error) && authEpoch === state.authEpoch) resetSession(error.message);
    } finally {
      ui["login-button"].disabled = false;
      ui["login-button"].querySelector("span").textContent = "进入工作台";
    }
  });

  ui["logout-button"].addEventListener("click", async () => {
    ui["logout-button"].disabled = true;
    try { await api("/logout", { method: "POST", courseScoped: false, timeout: 10000 }); }
    catch (error) { if (!isAbort(error) && error.code !== 401) toast("已退出当前页面；服务未确认注销，请稍后重新登录。", true); }
    finally { resetSession(); ui["logout-button"].disabled = false; }
  });

  async function selectCourse(id) {
    state.epoch += 1;
    abortRequests(true);
    state.courseId = id || null;
    state.mutating = false;
    setQueryBusy(false);
    resetAnswer();
    ui.question.value = "";
    ui["upload-file"].value = "";
    ui["upload-filename"].textContent = "选择一份课程资料";
    ui["materials-list"].replaceChildren();
    ui["material-count"].textContent = "0";
    message(ui["materials-message"], "");
    const course = state.courses.find((item) => String(item.id) === id);
    ui["course-description"].textContent = course ? `${course.courseCode} · ${course.description || course.name}` : "";
    ui["course-empty"].hidden = Boolean(course);
    ui["materials-empty"].hidden = Boolean(course);
    updateQuestionControls();
    updateMaterialControls();
    if (course) await loadMaterials();
  }

  ui["course-select"].addEventListener("change", () => selectCourse(ui["course-select"].value));
  ui["refresh-materials"].addEventListener("click", () => {
    invalidateResults();
    loadMaterials();
  });

  async function loadMaterials() {
    if (!state.courseId) return;
    const epoch = state.epoch;
    const requestId = ++state.materialRequestId;
    ui["materials-loading"].hidden = false;
    ui["materials-empty"].hidden = true;
    message(ui["materials-message"], "");
    try {
      const result = await api(coursePath("/materials"));
      if (!current(epoch) || requestId !== state.materialRequestId) return;
      const materials = Array.isArray(result.data) ? result.data : [];
      ui["materials-list"].replaceChildren(...materials.map(renderMaterial));
      ui["material-count"].textContent = String(materials.length);
      ui["materials-empty"].hidden = materials.length > 0;
      ui["materials-empty-text"].textContent = canManage()
        ? "上传第一份讲义，解析并发布后即可开始检索。"
        : "这门课程暂时没有已发布资料，请等待教师发布。";
      updateMaterialControls();
    } catch (error) {
      if (current(epoch) && requestId === state.materialRequestId && !isAbort(error)) message(ui["materials-message"], error.message);
    } finally {
      if (current(epoch) && requestId === state.materialRequestId) ui["materials-loading"].hidden = true;
    }
  }

  function renderMaterial(material) {
    const card = element("article", "material-item");
    const top = element("div", "material-top");
    const extension = String(material.filename).split(".").pop().toUpperCase().slice(0, 4);
    top.append(element("span", "file-icon", extension));
    const title = element("div", "material-title");
    title.append(element("h3", "", material.filename));
    const meta = element("div", "material-meta");
    const ready = material.parseStatus === "READY";
    const stateLabel = material.published ? "已发布" : ready ? "待发布" : material.parseStatus === "FAILED" ? "解析失败" : "处理中";
    const stateClass = material.published ? "" : ready ? " pending" : " failed";
    meta.append(element("span", `state-badge${stateClass}`, stateLabel));
    meta.append(element("span", "", `${material.chunkCount} 个片段 · v${material.version}`));
    title.append(meta);
    top.append(title);
    card.append(top);
    const actions = element("div", "material-actions");
    const courseId = state.courseId;
    actions.append(actionButton("下载", () => downloadFile(material, courseId)));
    if (canManage()) {
      if (ready) actions.append(actionButton(material.published ? "下架" : "发布", () => changeMaterial(material, "publication")));
      const more = element("details", "material-more");
      more.append(element("summary", "", "更多 ···"));
      const menu = element("div", "material-menu");
      const replacement = element("input");
      replacement.type = "file";
      replacement.accept = ".pdf,.docx,.txt";
      replacement.hidden = true;
      replacement.addEventListener("change", () => {
        const file = replacement.files[0];
        if (file) changeMaterial(material, "replace", file);
        replacement.value = "";
      });
      menu.append(actionButton("替换文件", () => { more.open = false; replacement.click(); }));
      menu.append(actionButton("重新解析", () => { more.open = false; changeMaterial(material, "reprocess"); }));
      menu.append(actionButton("删除资料", () => { more.open = false; changeMaterial(material, "delete"); }, "text-button danger"));
      more.append(menu, replacement);
      actions.append(more);
    }
    card.append(actions);
    if (material.errorMessage) card.append(element("p", "material-error", material.errorMessage));
    return card;
  }

  function checkedFile(file) {
    if (!file || file.size === 0) throw new ApiError("请选择一份非空资料文件。", 400);
    if (file.size > 10 * 1024 * 1024) throw new ApiError("资料不能超过 10 MB，请按章节拆分后上传。", 400);
    if (!/\.(pdf|docx|txt)$/i.test(file.name)) throw new ApiError("请选择 PDF、DOCX 或 TXT 文件。", 400);
    const data = new FormData();
    data.append("file", file);
    return data;
  }

  ui["upload-file"].addEventListener("change", () => {
    ui["upload-filename"].textContent = ui["upload-file"].files[0]?.name || "选择一份课程资料";
    updateMaterialControls();
  });

  ui["upload-form"].addEventListener("submit", async (event) => {
    event.preventDefault();
    if (state.mutating || !state.courseId) return;
    let body;
    try { body = checkedFile(ui["upload-file"].files[0]); }
    catch (error) { toast(error.message, true); return; }
    invalidateResults();
    const epoch = state.epoch;
    state.mutating = true;
    updateMaterialControls();
    resetAnswer();
    try {
      const result = await api(coursePath("/materials"), { method: "POST", body, timeout: 60000 });
      if (!current(epoch)) return;
      ui["upload-file"].value = "";
      ui["upload-filename"].textContent = "选择一份课程资料";
      if (result.data.parseStatus === "READY") toast("资料已解析，点击“发布”后即可供学生检索。");
      else toast(`资料已保存，但解析失败：${result.data.errorMessage || "请检查文件后重试。"}`, true);
      await loadMaterials();
    } catch (error) {
      if (current(epoch) && !isAbort(error)) toast(error.message, true);
    } finally {
      if (current(epoch)) { state.mutating = false; updateMaterialControls(); }
    }
  });

  async function changeMaterial(material, action, file) {
    if (state.mutating || !state.courseId) return;
    if (action === "delete" && !window.confirm(`删除“${material.filename}”？删除后将无法继续检索或下载这份资料。`)) return;
    let body;
    try { if (action === "replace") body = checkedFile(file); }
    catch (error) { toast(error.message, true); return; }
    invalidateResults();
    const epoch = state.epoch;
    const base = coursePath(`/materials/${encodeURIComponent(material.id)}`);
    const actions = {
      publication: { path: `${base}/publication`, method: "PATCH", body: { published: !material.published } },
      reprocess: { path: `${base}/reprocess`, method: "POST" },
      replace: { path: base, method: "PUT", body },
      delete: { path: base, method: "DELETE" }
    };
    const request = actions[action];
    state.mutating = true;
    updateMaterialControls();
    resetAnswer();
    try {
      const result = await api(request.path, { method: request.method, body: request.body, timeout: 60000 });
      if (!current(epoch)) return;
      if (result.data?.parseStatus === "FAILED") toast(`解析失败：${result.data.errorMessage || "请检查文件。"}`, true);
      else {
        const labels = {
          publication: material.published ? "资料已下架。" : "资料已发布，可以开始检索。",
          reprocess: "资料已重新核对解析状态。",
          replace: "资料已更新，请确认解析结果并重新发布。",
          delete: "资料已删除。"
        };
        toast(labels[action]);
      }
      await loadMaterials();
    } catch (error) {
      if (current(epoch) && !isAbort(error)) toast(error.message, true);
    } finally {
      if (current(epoch)) { state.mutating = false; updateMaterialControls(); }
    }
  }

  async function downloadFile(material, courseId, chunkId) {
    const epoch = state.epoch;
    try {
      // A result may have been superseded since the last question. Recheck its source first.
      if (chunkId) await api(coursePath(`/sources/${encodeURIComponent(chunkId)}`, courseId));
      const id = material.materialId || material.id;
      const blob = await api(coursePath(`/materials/${encodeURIComponent(id)}/file`, courseId), { download: true });
      if (!current(epoch)) return;
      const url = URL.createObjectURL(blob);
      const link = element("a");
      link.href = url;
      link.download = material.filename;
      document.body.append(link);
      link.click();
      link.remove();
      setTimeout(() => URL.revokeObjectURL(url), 1000);
    } catch (error) {
      if (current(epoch) && !isAbort(error)) toast(error.message, true);
    }
  }

  ui.question.addEventListener("input", updateQuestionControls);
  ui.question.addEventListener("keydown", (event) => {
    if ((event.ctrlKey || event.metaKey) && event.key === "Enter") {
      event.preventDefault();
      runQuestion("ask");
    }
  });
  document.querySelectorAll("[data-question]").forEach((button) => {
    button.addEventListener("click", () => {
      ui.question.value = button.dataset.question;
      updateQuestionControls();
      ui.question.focus();
    });
  });
  ui["question-form"].addEventListener("submit", (event) => { event.preventDefault(); runQuestion("ask"); });
  ui["search-button"].addEventListener("click", () => runQuestion("search"));

  async function runQuestion(mode) {
    const question = ui.question.value.trim();
    if (!question || state.busy || state.mutating || !state.courseId) return;
    const epoch = state.epoch;
    resetAnswer();
    ui["answer-empty"].hidden = true;
    ui["answer-loading"].hidden = false;
    setQueryBusy(true, mode);
    try {
      const result = mode === "search"
        ? await api(coursePath(`/search?q=${encodeURIComponent(question)}&topK=3`))
        : await api(coursePath("/ask"), { method: "POST", body: { question } });
      if (!current(epoch)) return;
      renderAnswer(question, result.data, mode);
    } catch (error) {
      if (current(epoch) && !isAbort(error)) {
        message(ui["question-message"], error.message);
        ui["answer-empty"].hidden = false;
      }
    } finally {
      if (current(epoch)) {
        setQueryBusy(false);
        ui["answer-loading"].hidden = true;
      }
    }
  }

  function updateModelMode(result) {
    const generated = result.status === "generated";
    if (!generated && result.status !== "retrieval_only") return;
    const text = generated ? "引用问答已连接" : "仅返回课程原文";
    ui["model-badge"].replaceChildren(element("span", "status-dot"), document.createTextNode(text));
    if (generated) ui["model-note"].textContent = "回答附有资料引用。请展开原文，核对引用内容是否支持回答。";
    else if (result.status === "retrieval_only") ui["model-note"].textContent = fallbackReasons[result.reason] || "本次只返回课程原文，请直接核对下方来源。";
  }

  function renderAnswer(question, result, mode) {
    const container = ui["answer-content"];
    container.replaceChildren();
    const queryLine = element("p", "result-question");
    queryLine.append(element("span", "", "本次问题"), document.createTextNode(question));
    container.append(queryLine);
    const summary = element("section", "answer-summary card");
    const status = result.status;
    const evidence = mode === "search" ? result.hits || [] : result.evidence || [];
    const citations = result.citations || [];
    const labels = {
      generated: "已生成引用回答", retrieval_only: "仅返回课程原文", no_evidence: "暂未找到有效依据",
      sources_changed: "资料已更新，请重新查询", ok: "已找到相关原文"
    };
    summary.append(element("div", `answer-status${status === "no_evidence" || status === "sources_changed" ? " warning" : ""}`, labels[status] || "查询结果"));
    let answer = result.answer;
    if (mode === "search") {
      answer = status === "sources_changed" ? "查询过程中资料状态发生变化，请重新检索。"
        : evidence.length ? "以下是当前课程中与问题相关的原文。展开来源可查看完整片段，也可以下载资料核对。"
          : "未在当前课程已发布资料中找到有效依据。可以补充课程术语，或请教师确认是否已发布相关资料。";
    }
    summary.append(element("p", "answer-text", answer || "请核对下方原文。"));
    if (mode === "ask") {
      updateModelMode(result);
      if (result.reason && fallbackReasons[result.reason]) summary.append(element("p", "answer-reason", fallbackReasons[result.reason]));
    }
    container.append(summary);
    if (evidence.length) {
      const heading = element("div", "sources-heading");
      heading.append(element("h3", "", `相关原文 · ${evidence.length}`), element("span", "", "展开来源，核对完整片段"));
      container.append(heading);
      const citationNumbers = new Map(citations.map((source, index) => [source.chunkId, index + 1]));
      evidence.forEach((hit, index) => container.append(renderSource(hit.source, index + 1, citationNumbers.get(hit.source.chunkId))));
    }
    container.hidden = false;
    ui["answer-empty"].hidden = true;
  }

  function sourceLocation(source) {
    let position;
    if (source.pageNumber != null) position = `第 ${source.pageNumber} 页`;
    else if (source.paragraphStart === source.paragraphEnd) position = `第 ${source.paragraphStart} 段`;
    else position = `第 ${source.paragraphStart}—${source.paragraphEnd} 段`;
    return `${position} · v${source.version}${source.heading ? ` · ${source.heading}` : ""}`;
  }

  function renderSource(source, index, citationNumber) {
    const epoch = state.epoch;
    const courseId = state.courseId;
    const card = element("article", "source-card card");
    const header = element("div", "source-header");
    header.append(element("span", "source-number", String(index).padStart(2, "0")));
    const heading = element("div", "source-heading");
    heading.append(element("h3", "", source.filename), element("p", "source-location", sourceLocation(source)));
    header.append(heading);
    if (citationNumber) header.append(element("span", "citation-tag", `回答引用 [${citationNumber}]`));
    card.append(header);
    const original = String(source.text || "");
    const preview = element("p", "source-preview", original.length > 170 ? `${original.slice(0, 170)}…` : original);
    card.append(preview);
    const full = element("div", "source-full");
    full.id = `source-${source.chunkId}`;
    full.hidden = true;
    const actions = element("div", "source-actions");
    const expand = actionButton("展开原文 ↓", async () => {
      if (!full.hidden) {
        full.hidden = true;
        expand.textContent = "展开原文 ↓";
        expand.setAttribute("aria-expanded", "false");
        return;
      }
      full.hidden = false;
      full.replaceChildren(element("p", "source-current", "正在核对当前来源…"));
      expand.disabled = true;
      expand.setAttribute("aria-expanded", "true");
      try {
        const result = await api(coursePath(`/sources/${encodeURIComponent(source.chunkId)}`, courseId));
        if (!current(epoch)) return;
        const fresh = result.data;
        full.replaceChildren(element("p", "source-current", `当前来源 · ${sourceLocation(fresh)}`), element("p", "", fresh.text));
        expand.textContent = "收起原文 ↑";
      } catch (error) {
        if (current(epoch) && !isAbort(error)) {
          full.replaceChildren(element("p", "source-error", error.message));
          if (error.code === 404 || error.code === 403) {
            preview.textContent = "这条来源已更新或不可访问，请重新检索。";
            download.disabled = true;
          }
          expand.textContent = "收起提示 ↑";
        }
      } finally { if (current(epoch)) expand.disabled = false; }
    });
    expand.setAttribute("aria-expanded", "false");
    expand.setAttribute("aria-controls", full.id);
    const download = actionButton("下载资料 ↗", () => downloadFile(source, courseId, source.chunkId));
    actions.append(expand, download);
    card.append(actions, full);
    return card;
  }

  if (state.token) {
    const authEpoch = ++state.authEpoch;
    ui["login-button"].disabled = true;
    ui["login-button"].querySelector("span").textContent = "正在恢复登录…";
    restoreWorkspace(authEpoch).catch((error) => {
      if (!isAbort(error) && authEpoch === state.authEpoch) resetSession(error.message);
    }).finally(() => {
      ui["login-button"].disabled = false;
      ui["login-button"].querySelector("span").textContent = "进入工作台";
    });
  }
})();
