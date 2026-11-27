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
    epoch: 0, authEpoch: 0, materialRequestId: 0, capabilityRequestId: 0,
    generationConfigured: null, capabilityStatus: "idle", answerTimeoutMs: 75000,
    busy: false, mutating: false, requests: new Map(), toastTimer: null
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

  async function streamAnswer(question, epoch, onProgress) {
    const controller = new AbortController();
    state.requests.set(controller, true);
    let timedOut = false, reader, finalAnswer = null, totalBytes = 0, draftChars = 0;
    const timer = setTimeout(() => { timedOut = true; controller.abort(); }, state.answerTimeoutMs);
    try {
      const response = await fetch(coursePath("/ask/stream"), {
        method: "POST", headers: { Accept: "text/event-stream", "Content-Type": "application/json",
          Authorization: `Bearer ${state.token}` },
        body: JSON.stringify({ question }), signal: controller.signal,
        cache: "no-store", credentials: "same-origin"
      });
      if (!response.ok || !(response.headers.get("content-type") || "").includes("text/event-stream")) {
        let payload = null;
        try { payload = await response.json(); } catch (_) { /* Never expose non-JSON provider/error pages. */ }
        const code = Number(payload?.code) || response.status;
        if (code === 401 || response.status === 401) resetSession("登录已过期，请重新登录。");
        throw new ApiError(payload?.msg || "未能建立流式问答连接，请稍后重试。", code);
      }
      if (!response.body || typeof response.body.getReader !== "function" || !window.CampusAiStream) {
        throw new ApiError("当前浏览器无法读取流式回答，请使用新版 Edge 或 Chrome。", 500);
      }
      const decoder = new TextDecoder("utf-8", { fatal: true });
      const parser = window.CampusAiStream.createParser((event) => {
        if (!current(epoch)) return;
        if (finalAnswer) throw new ApiError("流式回答重复结束，请重新提问。", 502);
        if (!["status", "delta", "done", "error"].includes(event.type)) return;
        let data;
        try { data = JSON.parse(event.data); }
        catch (_) { throw new ApiError("流式回答格式异常，请重新提问。", 502); }
        if (!data || typeof data !== "object" || Array.isArray(data)) throw new ApiError("流式回答格式异常，请重新提问。", 502);
        if (event.type === "error") {
          const code = Number(data.code) || 500;
          if (code === 401) resetSession("登录已过期，请重新登录。");
          throw new ApiError(typeof data.message === "string" ? data.message : "本次问答未完成，请重新提问。", code);
        }
        if (event.type === "delta") {
          if (typeof data.text !== "string" || (draftChars += data.text.length) > 20000) {
            throw new ApiError("流式回答超过限制或格式异常，请重新提问。", 502);
          }
          onProgress("delta", data.text);
        } else if (event.type === "status") {
          if (["retrieving", "generating", "validating"].includes(data.stage)) onProgress("status", data.stage);
        } else {
          if (!["generated", "retrieval_only", "no_evidence", "sources_changed"].includes(data.status)
              || !Array.isArray(data.citations) || !Array.isArray(data.evidence)
              || typeof data.answer !== "string") throw new ApiError("最终回答格式异常，请重新提问。", 502);
          if (data.status === "generated" && (!Array.isArray(data.claims) || !data.claims.length
              || !data.claims.every((claim) => typeof claim?.text === "string" && claim.text.trim()
                && claim.citationNumber === data.citations.findIndex((source) => source.chunkId === claim.chunkId) + 1
                && claimMatchesSource(claim, data.citations.find((source) => source.chunkId === claim.chunkId))))) {
            throw new ApiError("最终引文未通过核对，请重新提问或使用原文检索。", 502);
          }
          finalAnswer = data;
        }
      });
      reader = response.body.getReader();
      while (!finalAnswer) {
        const { value, done } = await reader.read();
        if (done) {
          parser.push(decoder.decode());
          parser.finish();
          break;
        }
        totalBytes += value.byteLength;
        if (totalBytes > 1024 * 1024) throw new ApiError("流式回答超过限制，请重新提问。", 502);
        parser.push(decoder.decode(value, { stream: true }));
      }
      if (!finalAnswer) throw new ApiError("连接在回答完成前中断，未保留未经核对的草稿，请重新提问。", 502);
      return finalAnswer;
    } catch (error) {
      if (timedOut) throw new ApiError("等待回答超时，未保留未经核对的草稿，请稍后重试。", 408);
      if (error.name === "AbortError" || error instanceof ApiError) throw error;
      throw new ApiError("流式问答连接中断，未保留未经核对的草稿，请重新提问。", 502);
    } finally {
      clearTimeout(timer);
      if (reader) { try { await reader.cancel(); } catch (_) { /* The request may already have been aborted. */ } }
      controller.abort();
      state.requests.delete(controller);
    }
  }

  function coursePath(suffix = "", courseId = state.courseId) {
    return `/campusai/courses/${encodeURIComponent(courseId)}/rag${suffix}`;
  }

  function current(epoch) { return epoch === state.epoch && Boolean(state.token); }
  function canManage() { return state.role === "teacher" || state.role === "admin"; }
  function isAbort(error) { return error.name === "AbortError"; }

  function renderCapabilityMode() {
    let badge = "等待课程信息";
    let note = "选择课程后确认问答配置；每次查询会显示实际返回状态。";
    if (state.capabilityStatus === "loading") {
      badge = "正在确认配置";
      note = "正在读取当前课程的问答配置，原文检索仍可使用。";
    } else if (state.capabilityStatus === "ready") {
      badge = state.generationConfigured ? "引用问答已配置" : "原文检索模式";
      note = state.generationConfigured
        ? "回答会随模型实际返回逐步显示；完成后核对引用。生成期间的文字仅为待校验草稿。"
        : "当前未配置生成服务，提问将返回课程原文，便于直接核对来源。";
    } else if (state.capabilityStatus === "unknown") {
      badge = "问答配置待确认";
      note = "暂未确认生成服务配置。你仍可检索原文，提问结果会显示实际状态。";
    }
    ui["model-badge"].replaceChildren(element("span", "status-dot"), document.createTextNode(badge));
    ui["model-note"].textContent = note;
  }

  async function loadCapabilities() {
    if (!state.courseId) return;
    const epoch = state.epoch;
    const requestId = ++state.capabilityRequestId;
    state.generationConfigured = null;
    state.capabilityStatus = "loading";
    state.answerTimeoutMs = 75000;
    renderCapabilityMode();
    try {
      const result = await api(coursePath("/capabilities"), { timeout: 10000 });
      if (!current(epoch) || requestId !== state.capabilityRequestId) return;
      const capabilities = result.data;
      if (typeof capabilities?.generationConfigured !== "boolean"
          || !Number.isInteger(capabilities.answerTimeoutSeconds)
          || capabilities.answerTimeoutSeconds < 45 || capabilities.answerTimeoutSeconds > 75) {
        throw new ApiError("问答配置信息暂不可用。", 500);
      }
      state.generationConfigured = capabilities.generationConfigured;
      state.answerTimeoutMs = capabilities.answerTimeoutSeconds * 1000;
      state.capabilityStatus = "ready";
    } catch (error) {
      if (!current(epoch) || requestId !== state.capabilityRequestId || isAbort(error)) return;
      state.generationConfigured = null;
      state.capabilityStatus = "unknown";
      state.answerTimeoutMs = 75000;
    } finally {
      if (current(epoch) && requestId === state.capabilityRequestId) renderCapabilityMode();
    }
  }

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
    ui["ask-button"].querySelector("span").textContent = busy && mode === "ask" ? "正在回答…" : "提问并核对";
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
    if (state.capabilityStatus === "loading") {
      state.capabilityStatus = "unknown";
      state.generationConfigured = null;
      state.answerTimeoutMs = 75000;
    }
    renderCapabilityMode();
  }

  function resetSession(reason = "") {
    state.authEpoch += 1;
    state.epoch += 1;
    abortRequests();
    saveSession("");
    Object.assign(state, { user: null, role: null, courses: [], courseId: null, busy: false, mutating: false,
      generationConfigured: null, capabilityStatus: "idle", answerTimeoutMs: 75000 });
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
    renderCapabilityMode();
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
    invalidateResults();
    ui["logout-button"].disabled = true;
    try { await api("/logout", { method: "POST", courseScoped: false, timeout: 10000 }); }
    catch (error) { if (!isAbort(error) && error.code !== 401) toast("已退出当前页面；服务未确认注销，请稍后重新登录。", true); }
    finally { resetSession(); ui["logout-button"].disabled = false; }
  });

  async function selectCourse(id) {
    state.epoch += 1;
    abortRequests(true);
    state.courseId = id || null;
    state.generationConfigured = null;
    state.capabilityStatus = id ? "loading" : "idle";
    state.answerTimeoutMs = 75000;
    state.mutating = false;
    setQueryBusy(false);
    resetAnswer();
    renderCapabilityMode();
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
    if (course) await Promise.all([loadMaterials(), loadCapabilities()]);
  }

  ui["course-select"].addEventListener("change", () => selectCourse(ui["course-select"].value));
  ui["refresh-materials"].addEventListener("click", () => {
    invalidateResults();
    Promise.all([loadMaterials(), loadCapabilities()]);
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
      return true;
    } catch (error) {
      if (current(epoch) && requestId === state.materialRequestId && !isAbort(error)) message(ui["materials-message"], error.message);
      return false;
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
      replacement.accept = ".pdf,.docx,.txt,.ppt,.pptx,.xls,.xlsx";
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
    if (!/\.(pdf|docx|txt|pptx?|xlsx?)$/i.test(file.name)) throw new ApiError("请选择 PDF、DOCX、TXT、PPT/PPTX 或 XLS/XLSX 文件。", 400);
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
      await Promise.all([loadMaterials(), loadCapabilities()]);
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
    const version = encodeURIComponent(material.version);
    const actions = {
      publication: { path: `${base}/publication`, method: "PATCH", body: { published: !material.published, expectedVersion: material.version } },
      reprocess: { path: `${base}/reprocess?version=${version}`, method: "POST" },
      replace: { path: `${base}?version=${version}`, method: "PUT", body },
      delete: { path: `${base}?version=${version}`, method: "DELETE" }
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
      await Promise.all([loadMaterials(), loadCapabilities()]);
    } catch (error) {
      if (current(epoch) && !isAbort(error)) {
        if (error.code === 409) await refreshVersionConflict();
        else toast(error.message, true);
      }
    } finally {
      if (current(epoch)) { state.mutating = false; updateMaterialControls(); }
    }
  }

  async function refreshVersionConflict() {
    // Refresh only reads after a conflict; never replay a publication/update/delete automatically.
    invalidateResults();
    const epoch = state.epoch;
    state.mutating = true;
    ui["materials-list"].replaceChildren();
    ui["material-count"].textContent = "—";
    updateMaterialControls();
    try {
      const [materialsLoaded] = await Promise.all([loadMaterials(), loadCapabilities()]);
      if (current(epoch)) {
        const hint = materialsLoaded
          ? "资料版本已变化，列表已重新读取。请核对最新版本后再操作。"
          : "资料版本已变化，暂未能重新读取列表。请点击刷新，核对最新版本后再操作。";
        message(ui["materials-message"], hint);
        toast(hint, true);
      }
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
      const blob = await api(coursePath(`/materials/${encodeURIComponent(id)}/file?version=${encodeURIComponent(material.version)}`, courseId), { download: true });
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
      if (current(epoch) && !isAbort(error)) {
        if (error.code === 409) await refreshVersionConflict();
        else toast(error.message, true);
      }
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
    ui["answer-loading"].querySelector("strong").textContent = "正在查找课程资料";
    ui["answer-loading"].querySelector("p").textContent = "检索原文并核对当前来源，请稍候。";
    setQueryBusy(true, mode);
    try {
      const draft = mode === "ask" ? streamingPreview(question, epoch) : null;
      const result = mode === "search"
        ? (await api(coursePath(`/search?q=${encodeURIComponent(question)}&topK=3`))).data
        : await streamAnswer(question, epoch, draft.update);
      if (!current(epoch)) return;
      renderAnswer(question, result, mode);
    } catch (error) {
      if (current(epoch)) {
        resetAnswer();
        if (!isAbort(error)) message(ui["question-message"], error.message);
      }
    } finally {
      if (current(epoch)) {
        setQueryBusy(false);
        ui["answer-loading"].hidden = true;
      }
    }
  }

  function streamingPreview(question, epoch) {
    let text = "";
    const container = ui["answer-content"];
    const queryLine = element("p", "result-question");
    queryLine.append(element("span", "", "本次问题"), document.createTextNode(question));
    const summary = element("section", "answer-summary card streaming-answer");
    const label = element("div", "answer-status", "正在生成 · 引用待校验");
    const draftText = element("p", "answer-text streaming-text");
    const note = element("p", "answer-reason", "以下文字随模型实际返回显示，完成来源核对后才会形成最终回答。");
    summary.append(label, draftText, note);
    function show() {
      if (!summary.isConnected) container.replaceChildren(queryLine, summary);
      container.hidden = false;
      ui["answer-loading"].hidden = true;
    }
    return {
      update(type, value) {
        if (!current(epoch)) return;
        if (type === "delta") {
          text += value;
          draftText.textContent = text;
          show();
        } else if (value === "validating") {
          if (text) { show(); label.textContent = "正在核对引用与资料状态…"; }
          else ui["answer-loading"].querySelector("strong").textContent = "正在核对模型结果与来源";
        } else if (value === "generating") {
          ui["answer-loading"].querySelector("strong").textContent = "已检索到资料，正在等待模型首段回答";
          ui["answer-loading"].querySelector("p").textContent = "文字会随模型实际返回逐步显示，完成后再核对引用。";
        }
      }
    };
  }

  function claimMatchesSource(claim, source) {
    return claim && source && claim.chunkId === source.chunkId && typeof source.text === "string"
      && typeof claim.quote === "string" && claim.quote.length > 0
      && Number.isInteger(claim.quoteStart) && Number.isInteger(claim.quoteEnd)
      && claim.quoteStart >= 0 && claim.quoteEnd > claim.quoteStart && claim.quoteEnd <= source.text.length
      && source.text.slice(claim.quoteStart, claim.quoteEnd) === claim.quote;
  }

  function renderClaim(claim, sourceViews) {
    const item = element("li", "answer-claim");
    const sentence = element("p", "claim-text");
    sentence.append(document.createTextNode(claim.text), element("span", "claim-number", `[${claim.citationNumber}]`));
    item.append(sentence, element("p", "claim-quote-label", `逐字引文 · 引用 [${claim.citationNumber}]`));
    item.append(element("blockquote", "claim-quote", claim.quote));
    item.append(actionButton(`核对引文 [${claim.citationNumber}]`, async () => {
      const view = sourceViews.get(claim.chunkId);
      if (view) await view.revealQuote(claim);
    }, "text-button claim-verify"));
    return item;
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
    const sources = new Map(evidence.map((hit) => [hit.source.chunkId, hit.source]));
    citations.forEach((source) => { if (!sources.has(source.chunkId)) sources.set(source.chunkId, source); });
    const citationNumbers = new Map(citations.map((source, index) => [source.chunkId, index + 1]));
    const receivedClaims = Array.isArray(result.claims) ? result.claims : [];
    const claims = status === "generated" && receivedClaims.every((claim) => claim && typeof claim.text === "string"
      && claim.text.trim() && claim.citationNumber === citationNumbers.get(claim.chunkId)
      && claimMatchesSource(claim, sources.get(claim.chunkId))) ? receivedClaims : [];
    const sourceViews = new Map();
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
    if (status === "generated" && claims.length) {
      const claimList = element("ol", "answer-claims");
      claims.forEach((claim) => claimList.append(renderClaim(claim, sourceViews)));
      summary.append(claimList, element("p", "answer-reason", "引文已与返回原文逐字核对，仍需检查它是否充分支持这条说明。"));
    } else {
      summary.append(element("p", "answer-text", answer || "请核对下方原文。"));
    }
    if (mode === "ask") {
      if (result.reason && fallbackReasons[result.reason]) summary.append(element("p", "answer-reason", fallbackReasons[result.reason]));
    }
    container.append(summary);
    if (sources.size) {
      const heading = element("div", "sources-heading");
      heading.append(element("h3", "", `相关原文 · ${sources.size}`), element("span", "", "展开来源，核对完整片段"));
      container.append(heading);
      Array.from(sources.values()).forEach((source, index) => {
        const view = renderSource(source, index + 1, citationNumbers.get(source.chunkId), claims.filter((claim) => claim.chunkId === source.chunkId));
        sourceViews.set(source.chunkId, view);
        container.append(view.card);
      });
    }
    container.hidden = false;
    ui["answer-empty"].hidden = true;
  }

  function sourceLocation(source) {
    let position;
    if (source.slideNumber != null) position = `第 ${source.slideNumber} 张幻灯片`;
    else if (source.sheetName != null) position = `工作表「${source.sheetName}」${source.cellRange ? ` · ${source.cellRange}` : ""}`;
    else if (source.pageNumber != null) position = `第 ${source.pageNumber} 页`;
    else if (source.paragraphStart != null && source.paragraphStart === source.paragraphEnd) position = `第 ${source.paragraphStart} 段`;
    else if (source.paragraphStart != null) position = `第 ${source.paragraphStart}—${source.paragraphEnd} 段`;
    else position = "原文片段";
    return `${position} · v${source.version}${source.heading ? ` · ${source.heading}` : ""}`;
  }

  function highlightedSource(source, claims) {
    const paragraph = element("p");
    if (!claims.length || !claims.every((claim) => claimMatchesSource(claim, source))) {
      paragraph.textContent = source.text;
      return { paragraph, marks: [], valid: claims.length === 0 };
    }
    const ranges = claims.map((claim) => ({ start: claim.quoteStart, end: claim.quoteEnd }))
      .sort((a, b) => a.start - b.start || a.end - b.end);
    const merged = [];
    for (const range of ranges) {
      const previous = merged[merged.length - 1];
      if (previous && range.start <= previous.end) previous.end = Math.max(previous.end, range.end);
      else merged.push({ ...range });
    }
    let cursor = 0;
    const marks = [];
    for (const range of merged) {
      paragraph.append(document.createTextNode(source.text.slice(cursor, range.start)));
      const mark = element("mark", "source-highlight", source.text.slice(range.start, range.end));
      mark.tabIndex = -1;
      paragraph.append(mark);
      marks.push({ ...range, node: mark });
      cursor = range.end;
    }
    paragraph.append(document.createTextNode(source.text.slice(cursor)));
    return { paragraph, marks, valid: true };
  }

  function renderSource(source, index, citationNumber, claims = []) {
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
    const previewCharacters = Array.from(original);
    const preview = element("p", "source-preview", previewCharacters.length > 170 ? `${previewCharacters.slice(0, 170).join("")}…` : original);
    card.append(preview);
    const full = element("div", "source-full");
    full.id = `source-${source.chunkId}`;
    full.hidden = true;
    const actions = element("div", "source-actions");
    let sourceRequestId = 0;
    const expand = actionButton("展开原文 ↓", async () => {
      if (!full.hidden) {
        sourceRequestId += 1;
        full.hidden = true;
        expand.textContent = "展开原文 ↓";
        expand.setAttribute("aria-expanded", "false");
        return;
      }
      await revealQuote();
    });

    async function revealQuote(focusClaim = null) {
      const requestId = ++sourceRequestId;
      full.hidden = false;
      full.replaceChildren(element("p", "source-current", "正在核对当前来源…"));
      expand.disabled = true;
      expand.setAttribute("aria-expanded", "true");
      try {
        const result = await api(coursePath(`/sources/${encodeURIComponent(source.chunkId)}`, courseId));
        if (!current(epoch) || requestId !== sourceRequestId) return;
        const fresh = result.data;
        if (fresh.chunkId !== source.chunkId || fresh.version !== source.version) {
          throw new ApiError("来源版本已变化，请重新提问后核对。", 409);
        }
        const highlighted = highlightedSource(fresh, claims);
        full.replaceChildren(element("p", "source-current", `当前来源 · ${sourceLocation(fresh)}`), highlighted.paragraph);
        if (claims.length) full.append(element("p", highlighted.valid ? "source-current" : "source-error", highlighted.valid
          ? "高亮部分为回答的逐字引文，请结合上下文核对说明。"
          : "引文与当前来源不一致，未作高亮，请重新提问。"));
        expand.textContent = "收起原文 ↑";
        if (focusClaim) {
          const selected = highlighted.marks.find((mark) => mark.start <= focusClaim.quoteStart && mark.end >= focusClaim.quoteEnd);
          if (selected) {
            selected.node.scrollIntoView({ block: "nearest" });
            selected.node.focus({ preventScroll: true });
          } else full.scrollIntoView({ block: "nearest" });
        }
      } catch (error) {
        if (current(epoch) && requestId === sourceRequestId && !isAbort(error)) {
          full.replaceChildren(element("p", "source-error", error.message));
          if (error.code === 404 || error.code === 403 || error.code === 409) {
            preview.textContent = "这条来源已更新或不可访问，请重新检索。";
            download.disabled = true;
          }
          expand.textContent = "收起提示 ↑";
        }
      } finally { if (current(epoch) && requestId === sourceRequestId) expand.disabled = false; }
    }
    expand.setAttribute("aria-expanded", "false");
    expand.setAttribute("aria-controls", full.id);
    const download = actionButton("下载资料 ↗", () => downloadFile(source, courseId, source.chunkId));
    actions.append(expand, download);
    card.append(actions, full);
    return { card, revealQuote };
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
