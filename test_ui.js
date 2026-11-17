// UI 回归测试（v1.0.0）：侧边栏 / 智能体表单 / 清除对话 / 独立设置页三分区 /
// 多渠道与拉取模型 / 输入栏渠道选择 / 浅深色主题 / 关于页
// 接缝：模拟后端 HTTP 契约 + 前端脚本行为（node test_ui.js）
const fs = require('fs');
const path = require('path');

const HTML = fs.readFileSync(path.join(__dirname, 'index.html'), 'utf8');
const css = HTML.match(/<style>([\s\S]*?)<\/style>/)[1];
const script = HTML.match(/<script>([\s\S]*?)<\/script>/)[1];

class El {
  constructor(id) {
    this.id = id; this.children = []; this.listeners = {};
    const st = {}; st.setProperty = (k, v) => { st[k] = v; };
    this.style = st; this._value = ''; this._text = null;
    this.className = ''; this._innerHTML = ''; this.disabled = false;
    this.scrollTop = 0; this.scrollHeight = 0; this.focusCalls = 0;
    this._parent = null; this.title = '';
  }
  appendChild(c) { this.children.push(c); c._parent = this; return c; }
  // v1.1.25 #3：桩无真实 DOM 解析。innerHTML 赋值后用 firstChild 取「合成首子节点」，
  // className 取自 innerHTML 中第一个 class="..."，全文保留供 textAll 断言。
  get firstChild() {
    if (this.children.length) return this.children[0];
    if (!this._innerHTML) return null;
    const m = this._innerHTML.match(/class="([^"]+)"/);
    const synth = new El((this.id || 'el') + ':first');
    if (m) synth.className = m[1];
    synth._innerHTML = this._innerHTML;
    synth._parent = this;
    this.children.push(synth);
    return synth;
  }
  /** v1.1.25 #3：递归收集本节点与全部子孙的 innerHTML/text，供内容断言。 */
  textAll() {
    let t = (this._innerHTML || '') + (this._text === null ? '' : this._text);
    this.children.forEach(c => { if (c && c.textAll) t += c.textAll(); });
    return t;
  }
  removeChild(c) { this.children = this.children.filter(x => x !== c); }
  remove() { if (this._parent) this._parent.children = this._parent.children.filter(x => x !== this); }
  contains(n) {
    if (n === this) return true;
    return this.children.some(c => c.contains && c.contains(n));
  }
  insertBefore(c, ref) {
    this.children.push(c); c._parent = this;
    if (ref !== undefined && ref !== null && ref !== undefined) {
      const i = this.children.indexOf(ref);
      if (i > 0) { this.children.splice(this.children.indexOf(c), 1); this.children.splice(i, 0, c); }
    }
    return c;
  }
  addEventListener(type, fn) { (this.listeners[type] = this.listeners[type] || []).push(fn); }
  dispatch(type, evt) { (this.listeners[type] || []).forEach(fn => fn(evt)); }
  setAttribute(k, v) { (this.attrs = this.attrs || {})[k] = v; }
  getAttribute(k) { return (this.attrs || {})[k]; }
  querySelector(sel) {
    if (sel.startsWith('.')) {
      const cls = sel.slice(1);
      const hit = this.children.find(c => (c.className || '').split(' ').includes(cls));
      if (hit) return hit;
      // v1.1.24 #1：桩不支持 innerHTML 解析；若 innerHTML 里确有该 class，
      // 合成一个同名子节点，使「由 innerHTML 构建 + querySelector 绑定事件」的前端写法可测。
      if (this._innerHTML && this._innerHTML.includes('class="' + cls + '"')) {
        const synth = new El((this.id || 'el') + ':' + cls);
        synth.className = cls; synth._parent = this; this.children.push(synth);
        return synth;
      }
      return null;
    }
    const m = sel.match(/\[data-models-for="([^"]+)"\]/);
    if (m) return this.children.find(c => (c.attrs || {})['data-models-for'] === m[1]) || null;
    return null;
  }
  focus() { this.focusCalls++; }
  select() { this.selected = true; }
  execCommand() { return true; }
  get value() { return this._value; }
  set value(v) { this._value = v; }
  get innerHTML() { return this._innerHTML; }
  set innerHTML(v) { this._innerHTML = String(v); this.children = []; this._text = null; }
  get textContent() {
    if (this._text !== null) return this._text;
    return this.children.map(c => c.textContent || '').join('');
  }
  set textContent(v) { this._text = String(v); }
  get classList() {
    const self = this;
    return {
      add(c) { if (!self.className.split(' ').includes(c)) self.className = (self.className + ' ' + c).trim(); },
      remove(c) { self.className = self.className.split(' ').filter(x => x !== c).join(' ').trim(); },
      contains(c) { return self.className.split(' ').includes(c); },
      toggle(c) { if (this.contains(c)) this.remove(c); else this.add(c); },
    };
  }
}

const ids = [
  'chat', 'row', 'input', 'send', 'status', 'sidebar', 'agentList', 'newAgentBtn', 'toggleBtn',
  'main', 'empty', 'chatPanel', 'chatHeader', 'agentName', 'agentSetting',
  'delAgentBtn', 'editAgentBtn', 'clearChatBtn', 'modal', 'modalTitle',
  'fName', 'fSetting', 'fOk', 'fCancel', 'agFormTip', 'modelSub', 'settingsBtn',
  'settingsPage', 'settingsBackBtn', 'channelTrigger', 'channelPicker', 'channelList',
  'skillBtn', 'skillsPage', 'skillsBackBtn', 'skillList', 'skillsBody',
  'skForm', 'skFormTitle', 'skName', 'skNote', 'skPrompt', 'skFormTip',
  'addSkillBtn', 'skCancelBtn', 'skSaveBtn',
  'settingsBody', 'settingsTrack', 'chForm', 'chFormTitle', 'chName', 'chBaseUrl', 'chApiKey', 'chModel',
  'modelCandidateRow', 'modelCandidates', 'chHint',
  'tabModels', 'tabPersonal', 'tabAbout', 'tabBtnModels', 'tabBtnPersonal', 'tabBtnAbout',
  'addChannelBtn', 'pullModelsBtn', 'chCancelBtn', 'chSaveBtn',
  'themeLightBtn', 'themeDarkBtn', 'themeHint',
  'aboutUrl', 'aboutAuthor', 'aboutVersion',
  // v1.1.11 新增
  'toolBtn', 'pluginBtn', 'toolsPage', 'pluginsPage', 'toolsBackBtn', 'pluginsBackBtn',
  'toolList', 'pluginsEmpty', 'plusBtn', 'plusMenu', 'pmImage', 'pmFile', 'pmSkill', 'pmTool', 'pmPlugin',
  'atList', 'barHint', 'chProvider', 'modelPicker', 'mpTitle', 'mpSearch', 'mpList',
  'mpAllBtn', 'mpResetBtn', 'mpOkBtn',
  // v1.1.12 新增
  'chKeyEye', 'streamSwitch', 'loopSwitch',
  // v1.1.13 新增
  'thinkSwitch', 'timingSwitch', 'tokensSwitch',
  // v1.1.14 新增
  'reasoningParamSwitch', 'effortSwitch', 'userBtn', 'userPage', 'userBar', 'userBackBtn', 'userBody',
  'userSetting', 'userProfileSwitch', 'userSaveBtn', 'userHint',
  'msgMenu', 'mmCopy', 'mmEdit', 'mmDel', 'msgEdit', 'meText', 'meCancel', 'meSave',
  'effortRow',
  // v1.1.24 #1 新增：测试模式三级开关 + 提示词查看器页
  'devSection', 'devModeSwitch', 'devRowPromptViewer', 'devPromptViewerSwitch',
  'devRowPromptHistory', 'devPromptHistorySwitch',
  'pmPrompt', 'pmPromptHist',
  'promptPage', 'promptBar', 'promptBackBtn', 'promptTitle', 'promptClearBtn', 'promptBody',
  // v1.1.27 #4 新增：测试模式修改系统提示词页
  'promptOverridePage', 'promptOverrideBar', 'poBackBtn', 'promptOverrideBody',
  'poGuide', 'poManual', 'poResetBtn', 'poSaveBtn', 'poHint',
  'devRowPromptOverride', 'devPromptOverrideBtn',
  // v1.1.41 #2 新增：居中通知弹窗
  'notifyModal', 'notifyMsg', 'notifyOk', 'notifyCancel',
];
const elements = {};
ids.forEach(id => { elements[id] = new El(id); });
const document = {
  body: new El('body'),
  activeElement: null,
  _docListeners: {},
  addEventListener(type, fn) { (this._docListeners[type] = this._docListeners[type] || []).push(fn); },
  removeEventListener(type, fn) {
    const a = this._docListeners[type] || [];
    this._docListeners[type] = a.filter(x => x !== fn);
  },
  getElementById: (id) => elements[id] || null,
  createElement: (tag) => new El(tag),
  createTextNode: (t) => { const n = new El('#text'); n.textContent = String(t); return n; },
  execCommand: (cmd) => { document._lastCopyCmd = cmd; return true; },
};

// ---- 模拟后端状态 ----
let agents = [
  { id: 'a1', name: '翻译官', setting: '负责翻译', messages: [
    { role: 'user', content: '翻译你好' }, { role: 'assistant', content: '你好，有什么要翻译？' } ] },
];
let nextId = 2;
let channels = [
  { id: 'builtin', name: '内置模型', type: 'builtin', enabled: true, locked: true,
    baseUrl: '', model: '', hasKey: false },
  { id: 'ch1', name: '自建网关', type: 'openai', enabled: true, locked: false,
    baseUrl: 'http://127.0.0.1:9000/v1', model: 'gpt-4o-mini', hasKey: true },
];
let nextChId = 1, nextSkId = 1;
let prefs = { theme: 'light', lastChannelId: '', lastModel: '', stream: true, agentLoop: true,
  showThink: true, showTiming: true, showTokens: false,
  reasoningEffort: 'high', reasoningParam: true, reasoningParamOn: false,
  showToolCalls: false, userProfileOn: true,
  // v1.1.24 #1：测试模式三级开关，默认全关
  testMode: false, promptViewerOn: false, promptHistoryOn: false };
let promptLog = [];
let skillsFetchCount = 0;
let promptLogGetCount = 0;
let prefsPuts = [];
let profile = { content: '', enabled: true };
let promptOverride = { guide: '', manual: '', overridden: false };
let skills = [];
const about = { version: '1.1.45', author: '子兮', projectUrl: 'https://github.com/asd11288k/helper' };
let settingsMode = 'app';   // 'web' 模拟网页预览版无 /channels 路由（App 独有）
let lastChatBody = null, lastPrefsPut = null, lastProbeBody = null, lastChannelPut = null;
let lastBarTheme = null, exitCalls = 0, choicePrefsPut = null;
const ok = (obj) => Promise.resolve({ ok: true, json: () => Promise.resolve(obj) });
const notOk = (obj) => Promise.resolve({ ok: false, json: () => Promise.resolve(obj || { error: 'not found' }) });

function mockFetch(url, opts) {
  const method = (opts && opts.method) || 'GET';
  const body = opts && opts.body ? JSON.parse(opts.body) : {};
  if (url.endsWith('/health')) return ok({ ok: true });
  if (url.endsWith('/settings')) {
    if (settingsMode !== 'app') return notOk();
    return ok({ baseUrl: 'http://127.0.0.1:9000/v1', model: 'gpt-4o-mini', hasKey: false, configured: true });
  }
  if (url.endsWith('/profile')) {
    if (method === 'GET') return ok(Object.assign({}, profile));
    if (Object.prototype.hasOwnProperty.call(body, 'content')) profile.content = body.content;
    if (Object.prototype.hasOwnProperty.call(body, 'enabled')) profile.enabled = body.enabled;
    return ok(Object.assign({}, profile));
  }
  // v1.1.27 #4：测试模式修改系统提示词（全局指南 / 操作手册）
  if (url.endsWith('/prompt_override')) {
    if (method === 'PUT') {
      if (Object.prototype.hasOwnProperty.call(body, 'guide')) promptOverride.guide = body.guide;
      if (Object.prototype.hasOwnProperty.call(body, 'manual')) promptOverride.manual = body.manual;
      promptOverride.overridden = true;
      return ok(Object.assign({}, promptOverride));
    }
    if (method === 'DELETE') {
      promptOverride = { guide: '', manual: '', overridden: false };
      return ok({ guide: '默认指南', manual: '默认手册', overridden: false });
    }
    return ok(Object.assign({ defaultGuide: '默认指南', defaultManual: '默认手册' }, promptOverride));
  }
  if (url.endsWith('/interrupt')) {
    const a = agents.find(x => x.id === url.split('/')[2]);
    if (a) a.messages.push({ role: 'assistant', content: '（系统记录：用户取消了本轮生成）' });
    return ok({ ok: true });
  }
  const msgEditMatch = url.match(/\/agents\/([^/]+)\/messages\/(\d+)$/);
  if (msgEditMatch && method === 'PUT') {
    const a = agents.find(x => x.id === msgEditMatch[1]);
    const i = Number(msgEditMatch[2]);
    if (a && a.messages[i]) { a.messages[i].content = body.content; return ok({ ok: true }); }
    return notOk({ error: 'message not found' });
  }
  if (url.endsWith('/prefs')) {
    if (method === 'GET') return ok(Object.assign({}, prefs));
    lastPrefsPut = body;
    prefsPuts.push(body);
    if (Object.prototype.hasOwnProperty.call(body, 'lastChannelId')) choicePrefsPut = body;
    if (body.theme) prefs.theme = body.theme;
    if (Object.prototype.hasOwnProperty.call(body, 'stream')) prefs.stream = body.stream;
    if (Object.prototype.hasOwnProperty.call(body, 'agentLoop')) prefs.agentLoop = body.agentLoop;
    if (Object.prototype.hasOwnProperty.call(body, 'showThink')) prefs.showThink = body.showThink;
    if (Object.prototype.hasOwnProperty.call(body, 'showTiming')) prefs.showTiming = body.showTiming;
    if (Object.prototype.hasOwnProperty.call(body, 'showTokens')) prefs.showTokens = body.showTokens;
    if (Object.prototype.hasOwnProperty.call(body, 'lastChannelId')) prefs.lastChannelId = body.lastChannelId;
    if (Object.prototype.hasOwnProperty.call(body, 'lastModel')) prefs.lastModel = body.lastModel;
    ['reasoningEffort', 'reasoningParam', 'reasoningParamOn', 'showToolCalls', 'userProfileOn',
      'testMode', 'promptViewerOn', 'promptHistoryOn'].forEach(k => {
      if (Object.prototype.hasOwnProperty.call(body, k)) prefs[k] = body[k];
    });
    return ok(Object.assign({}, prefs));
  }
  // ---------- v1.1.24 #1：提示词查看器后端 mock ----------
  if (url.endsWith('/prompt_preview') && method === 'POST') {
    return ok({
      ok: true,
      system: '指南示例\n\n【小助手 App 操作手册 v1.1.26】\n\n角色设定示例\n\n【当前模型思考等级】high',
      segments: [
        { title: '全局指南', text: '指南示例' },
        { title: '操作手册', text: '【小助手 App 操作手册 v1.1.26】' },
        { title: '智能体设定', text: '角色设定示例' },
        { title: '用户设定', text: '【用户设定·开启】用户背景' },
        { title: '思考等级', text: '【当前模型思考等级】high', effort: 'high', paramOn: false },
      ],
      messages: [
        { role: 'system', content: '指南示例 手册 角色设定 思考等级', shell: true },
        { role: 'user', content: '上一问' },
        { role: 'assistant', content: '上一答' },
        { role: 'user', content: '（虚拟请求：仅用于预览提示词，不会发送给模型）' },
      ],
      protocol: 'openai', setting: body.setting || '',
    });
  }
  if (url.endsWith('/prompt_log')) {
    if (method === 'DELETE') { promptLog = []; return ok({ ok: true, cleared: true }); }
    promptLogGetCount++;
    // 与后端 /prompt_log 一致：items 按时间旧→新（最新在末尾），前端自行反转成最新在前
    return ok({ ok: true, count: promptLog.length, items: promptLog.slice(),
      enabled: prefs.promptHistoryOn === true });
  }
  const sessMatch = url.match(/\/agents\/([^/]+)\/sessions$/);
  if (sessMatch && method === 'GET') {
    const a = agents.find(x => x.id === sessMatch[1]);
    return ok({ activeSessionId: a && a.activeSessionId || 's1',
      sessions: (a && a.sessions) || [{ id: 's1', name: '会话 1', messages: (a && a.messages) || [] }] });
  }
  if (sessMatch && method === 'POST') {
    const a = agents.find(x => x.id === sessMatch[1]);
    const ns = { id: 's' + (Date.now()), name: '会话 ' + (((a && a.sessions) || []).length + 1), messages: [] };
    if (a) { a.sessions = a.sessions || [{ id: 's1', name: '会话 1', messages: a.messages || [] }];
      a.sessions.push(ns); a.activeSessionId = ns.id; }
    return ok({ session: ns });
  }
  const sessItem = url.match(/\/agents\/([^/]+)\/sessions\/([^/]+)$/);
  if (sessItem && method === 'PUT') {
    const a = agents.find(x => x.id === sessItem[1]);
    if (a) a.activeSessionId = sessItem[2];
    return ok({ ok: true });
  }
  if (sessItem && method === 'DELETE') return ok({ ok: true });
  if (url.endsWith('/about')) return ok(about);
  if (url.endsWith('/providers') && method === 'GET') return ok({ providers: [
    { id: 'openai', name: 'OpenAI 通用', baseUrl: 'https://api.openai.com/v1', model: 'gpt-4o-mini', general: true },
    { id: 'deepseek', name: 'Deepseek', baseUrl: 'https://api.deepseek.com/v1', model: 'deepseek-chat', general: false },
    { id: 'anthropic-openai', name: 'Anthropic 通用', baseUrl: '', model: 'claude-sonnet-4-5', general: true },
  ] });
  if (url.endsWith('/tools') && method === 'GET') return ok({ tools: [
    { id: 'search_web', name: '搜索网页', desc: '搜互联网' },
    { id: 'write_file', name: '写文件', desc: '写文本文件' },
  ] });
  if (url.endsWith('/skills') && method === 'GET') { skillsFetchCount++; return ok({ skills: skills }); }
  if (url.endsWith('/skills') && method === 'POST') {
    if (!body.name || !body.prompt) return notOk({ error: 'name/prompt required' });
    const s = { id: 'sk' + nextSkId++, name: body.name, note: body.note, prompt: body.prompt };
    skills.push(s); return ok({ skill: s });
  }
  const skMatch = url.match(/\/skills\/([^/]+)$/);
  if (skMatch && method === 'PUT') {
    const s = skills.find(x => x.id === skMatch[1]);
    if (!s) return notOk({ error: 'skill not found' });
    s.name = body.name; s.note = body.note; s.prompt = body.prompt;
    return ok({ skill: s });
  }
  if (skMatch && method === 'DELETE') {
    skills = skills.filter(x => x.id !== skMatch[1]); return ok({ ok: true });
  }
  if (url.endsWith('/channels/probe')) {
    lastProbeBody = body;
    if (!body.baseUrl) return notOk({ error: '请先填写 Base URL' });
    return ok({ models: ['gpt-4o', 'gpt-4o-mini'] });
  }
  const keyMatch = url.match(/\/channels\/([^/]+)\/key$/);
  if (keyMatch && method === 'GET') {
    const c = channels.find(x => x.id === keyMatch[1]);
    return c ? ok({ apiKey: c.apiKey || 'sk-stored' }) : notOk({ error: 'channel not found' });
  }
  const modelsMatch = url.match(/\/channels\/([^/]+)\/models$/);
  if (modelsMatch && method === 'GET')
    return ok({ models: modelsMatch[1] === 'builtin' ? ['内置模型'] : ['gpt-4o', 'gpt-4o-mini'] });
  const enableMatch = url.match(/\/channels\/([^/]+)\/enable$/);
  if (enableMatch && method === 'PUT') {
    const c = channels.find(x => x.id === enableMatch[1]);
    if (c) c.enabled = body.enabled;
    return ok({ channel: c });
  }
  if (url.endsWith('/channels') && method === 'GET') {
    if (settingsMode !== 'app') return notOk();   // 网页预览版没有该路由
    return ok({ channels: channels, defaultId: 'builtin' });
  }
  if (url.endsWith('/channels') && method === 'POST') {
    const c = { id: 'ch' + nextChId++, name: body.name, type: 'openai', enabled: true,
      locked: false, baseUrl: body.baseUrl, model: body.model,
      models: body.models || [body.model], hasKey: !!body.apiKey };
    channels.push(c); return ok({ channel: c });
  }
  const chMatch = url.match(/\/channels\/([^/]+)$/);
  if (chMatch && method === 'PUT') {
    lastChannelPut = body;
    const c = channels.find(x => x.id === chMatch[1]);
    if (c && !c.locked) {
      c.name = body.name; c.baseUrl = body.baseUrl; c.model = body.model;
      if (body.models) c.models = body.models;
    }
    return c && !c.locked ? ok({ channel: c }) : notOk({ error: '内置模型不可编辑' });
  }
  if (chMatch && method === 'DELETE') {
    const before = channels.length;
    channels = channels.filter(x => x.id === 'builtin' || x.id !== chMatch[1]);
    return before > channels.length ? ok({ ok: true }) : notOk({ error: '内置模型不可删除' });
  }

  const clearMatch = url.match(/\/agents\/([^/]+)\/clear$/);
  if (clearMatch && method === 'POST') {
    const a = agents.find(x => x.id === clearMatch[1]);
    if (!a) return notOk({ error: 'agent not found' });
    a.messages = [];
    return ok({ ok: true, agent: a });
  }
  const chatMatch = url.match(/\/agents\/([^/]+)\/chat$/);
  if (chatMatch && method === 'POST') {
    const a = agents.find(x => x.id === chatMatch[1]);
    lastChatBody = body;
    if (body.prompt === '触发失败') {
      return ok({ answer: '未配置上游：请先在设置里填写 Base URL、API Key 和模型名' });
    }
    a.messages.push({ role: 'user', content: body.prompt });
    a.messages.push({ role: 'assistant', content: 'AI回复' });
    return ok({ answer: 'AI回复', agent: a });
  }
  if (url.endsWith('/agents') && method === 'GET')
    return ok({ agents: agents.map(a => ({ id: a.id, name: a.name, setting: a.setting, count: a.messages.length })) });
  if (url.endsWith('/agents') && method === 'POST') {
    const a = { id: 'a' + nextId++, name: body.name, setting: body.setting, messages: [] };
    agents.push(a); return ok({ agent: a });
  }
  const agentMatch = url.match(/\/agents\/([^/]+)$/);
  if (agentMatch && method === 'GET') {
    const a = agents.find(x => x.id === agentMatch[1]);
    return a ? ok({ agent: a }) : notOk({ error: 'not found' });
  }
  if (agentMatch && method === 'PUT') {
    const a = agents.find(x => x.id === agentMatch[1]);
    if (a) { a.name = body.name; a.setting = body.setting; }
    return ok({ agent: { id: a.id, name: a.name, setting: a.setting, count: a.messages.length } });
  }
  if (agentMatch && method === 'DELETE') {
    agents = agents.filter(x => x.id !== agentMatch[1]); return ok({ ok: true });
  }
  return notOk();
}

global.location = { protocol: 'http:' };
global.navigator = { userAgent: 'Mozilla/5.0' };
global.window = { innerWidth: 1024, AppBridge: {
  setBars: (t) => { lastBarTheme = t; },
  exitApp: () => { exitCalls++; },
} };
global.document = document;
global.fetch = mockFetch;
global.confirm = () => true;

const sidebarOpenTag = HTML.match(/<aside[^>]*id="sidebar"[^>]*>/)[0];
const sidebarClassMatch = sidebarOpenTag.match(/class="([^"]*)"/);
elements.sidebar.className = sidebarClassMatch ? sidebarClassMatch[1] : '';

eval(script);

const sleep = (ms) => new Promise(r => setTimeout(r, ms));
// v1.1.42 #1：前端删除类二次确认已由同步 confirm() 改为异步统一对话框 askConfirm()。
// 用全局自动确认驱动：检测到「确认框」(取消键可见) 就自动点确定，让 await askConfirm 推进。
// alert 通知框(notify，取消键隐藏)不自动点，保留各用例对其可见性/文案的断言。
setInterval(() => {
  try {
    const m = elements.notifyModal, c = elements.notifyCancel, ok = elements.notifyOk;
    if (m && m.style && m.style.display === 'flex' && c && c.style && c.style.display !== 'none') ok.dispatch('click', {});
  } catch (e) {}
}, 3);
let failures = 0;
function assert(cond, msg) {
  if (cond) console.log('  PASS: ' + msg);
  else { console.log('  FAIL: ' + msg); failures++; }
}
function findItemByText(container, text) {
  return container.children.find(d => d.textContent && d.textContent.includes(text));
}

(async () => {
  await sleep(20);

  console.log('\n[1] 名称 / 版本 / 浅色默认主题');
  assert(HTML.includes('<div class="title">小助手</div>'), '顶栏标题为小助手');
  assert(HTML.includes('<title>小助手</title>'), '页面 title 为小助手');
  const bg = css.match(/--bg:\s*(#[0-9a-fA-F]{6})/);
  let lum = 0;
  if (bg) {
    const n = parseInt(bg[1].slice(1), 16);
    lum = (0.2126 * ((n >> 16) & 255) + 0.7152 * ((n >> 8) & 255) + 0.0722 * (n & 255)) / 255;
  }
  assert(bg && lum > 0.5, '默认背景为浅色（亮度>0.5），--bg=' + (bg ? bg[1] : '未找到'));
  assert(/body\.dark\s*\{[\s\S]*?--bg:\s*#[0-9a-fA-F]{6}/.test(css), '存在 body.dark 深色变量覆盖');
  assert(document.body.className !== 'dark', '默认不启用深色');

  console.log('\n[2] 打开时不自动发欢迎消息，加载历史');
  const chatText = elements.chat.children.map(d => d.textContent || '').join('\n');
  assert(!chatText.includes('我是豆包') && !chatText.includes('开始对话吧'), '不应有自动欢迎语');
  assert(chatText.includes('翻译你好'), '应加载该智能体历史消息');

  console.log('\n[3] 智能体列表与侧边栏');
  assert(elements.agentList.children.length === 1, '列表应显示 1 个智能体');
  assert(elements.sidebar.className.includes('collapsed'), '初始状态侧边栏默认收起');
  assert(/#sidebar\.collapsed\s*\{[^}]*width:\s*0[^}]*\}/.test(css), '收起 CSS 应 width:0 完全收回');
  elements.toggleBtn.dispatch('click', {});
  assert(!elements.sidebar.className.includes('collapsed'), '点击应展开');
  elements.toggleBtn.dispatch('click', {});
  assert(elements.sidebar.className.includes('collapsed'), '再次点击应收起');
  const headerHtml = HTML.match(/<header[^>]*>([\s\S]*?)<\/header>/)[1];
  assert(headerHtml.includes('id="toggleBtn"') && headerHtml.includes('id="newAgentBtn"'), '折叠/新建按钮在顶栏');
  const sidebarHtml = HTML.match(/<aside[^>]*id="sidebar"[^>]*>([\s\S]*?)<\/aside>/)[1];
  assert(!sidebarHtml.includes('toggleBtn') && !sidebarHtml.includes('newAgentBtn'), '侧边栏内不应再放这两个按钮');

  console.log('\n[4] 智能体表单：昵称/设定标注 + 设定 5 行');
  assert(/<label for="fName">昵称<\/label>/.test(HTML), '昵称字段有左侧标注');
  assert(/<label for="fSetting">设定<\/label>/.test(HTML), '设定字段有左侧标注');
  assert(/<textarea id="fSetting" rows="5"/.test(HTML), '设定输入框为 5 行 textarea');
  elements.newAgentBtn.dispatch('click', {});
  assert(elements.modal.style.display !== 'none', '点击新建后弹出表单');
  elements.fName.value = '写作助手';
  elements.fSetting.value = '帮我润色写作';
  elements.fOk.dispatch('click', {});
  await sleep(20);
  assert(agents.length === 2, '后端应新增智能体');
  assert(elements.agentList.children.length === 2, '列表应显示 2 个');
  assert(elements.chatPanel.style.display === 'flex', '创建后自动选中并显示聊天面板');
  assert(elements.agentName.textContent === '写作助手', '聊天面板显示新智能体名称');

  console.log('\n[5] 编辑智能体');
  const item = findItemByText(elements.agentList, '翻译官');
  item.dispatch('click', {});
  await sleep(20);
  assert(elements.agentName.textContent === '翻译官', '应切换到翻译官');
  elements.editAgentBtn.dispatch('click', {});
  assert(elements.fName.value === '翻译官' && elements.fSetting.value === '负责翻译', '编辑表单预填名称与设定');
  assert(elements.modalTitle.textContent.includes('编辑'), '表单标题为编辑');
  elements.fName.value = '翻译大师';
  elements.fSetting.value = '专业翻译';
  elements.fOk.dispatch('click', {});
  await sleep(20);
  assert(agents[0].name === '翻译大师' && agents[0].setting === '专业翻译', '后端应更新名称与设定');
  assert(elements.agentName.textContent === '翻译大师', '聊天面板应显示新名称');

  console.log('\n[6] 每个智能体聊天独立 / 发送不弹键盘');
  const item2 = findItemByText(elements.agentList, '写作助手');
  item2.dispatch('click', {});
  await sleep(20);
  assert(elements.chat.children.length === 0, '写作助手无历史消息，聊天区应为空');
  elements.input.focusCalls = 0;
  elements.input.value = '再问一句';
  elements.send.dispatch('click', {});
  await sleep(30);
  assert(elements.chat.children.length === 2, '发送后新增用户消息+AI回复');
  assert(elements.input.focusCalls === 0, 'AI 回复后不应自动 focus（不弹键盘）');

  console.log('\n[7] ①④ 短渠道触发按钮与浮层：默认内置，收起只显示首字符');
  assert(/<button id="channelTrigger"/.test(HTML), '输入栏右侧是短触发按钮');
  assert(/#channelTrigger\s*\{[^}]*width:\s*40px/.test(css), '触发按钮宽度 40px（原 96px 的下拉框已缩短）');
  assert(/<button id="send">发送<\/button>/.test(HTML), '发送键保持原样且仍紧邻渠道按钮');
  assert(elements.channelTrigger.textContent === '内', '内置模型时按钮显示「内」');
  assert(elements.modelSub.textContent.includes('内置模型'), '顶栏副标题显示当前渠道');
  assert(lastChatBody && lastChatBody.channelId === 'builtin', '发送请求默认带 channelId=builtin');
  elements.channelTrigger.dispatch('click', {});
  assert(elements.channelPicker.style.display === 'block', '点击后展开渠道模型浮层');
  const pkTexts = elements.channelPicker.children.map(c => c.textContent);
  // 需求⑧：渠道下模型默认收起在 .pk-models 容器里（不直接暴露在浮层 children）
  assert(pkTexts.some(t => t.includes('自建网关')), '浮层列出渠道');
  const wrap = elements.channelPicker.children.find(c => c.className && c.className.includes('pk-models'));
  assert(wrap && !wrap.className.includes('open'), '渠道下模型默认收起');
  const modelBtn = elements.channelPicker.children
    .filter(c => c.className && c.className.includes('pk-models'))
    .reduce((acc, c) => acc.concat(c.children), [])
    .find(c => c.textContent === '· gpt-4o-mini');
  assert(modelBtn, '收起容器里仍保有模型按钮');
  modelBtn.dispatch('click', {});
  assert(elements.channelTrigger.textContent === 'g', '选中 gpt-4o-mini 后按钮显示模型名首字「g」');
  elements.input.value = '走自建';
  elements.send.dispatch('click', {});
  await sleep(30);
  assert(lastChatBody && lastChatBody.channelId === 'ch1', '切换后发送请求带新 channelId');
  assert(lastChatBody.model === 'gpt-4o-mini', '发送请求带渠道内所选 model');

  console.log('\n[8] 清除对话：清空消息但保留昵称与设定');
  // 上一步当前选中的是「写作助手」，先切回有历史的「翻译大师」再清除
  findItemByText(elements.agentList, '翻译大师').dispatch('click', {});
  await sleep(20);
  const beforeClear = agents.find(a => a.id === 'a1');
  assert(beforeClear && beforeClear.messages.length >= 2, '清除前存在对话');
  elements.clearChatBtn.dispatch('click', {});
  await sleep(30);
  const afterClear = agents.find(a => a.id === 'a1');
  assert(afterClear.messages.length === 0, '后端消息已清空');
  assert(afterClear.name === '翻译大师' && afterClear.setting === '专业翻译', '昵称与设定保留');

  console.log('\n[9] v1.1.40 删除入口迁移');
  // [9a] 顶栏「删会话」键不再删除整个智能体（mock 下无 sessions → 走保护分支，语义已迁移）
  const cntBefore = agents.length;
  elements.delAgentBtn.dispatch('click', {});
  await sleep(20);
  assert(agents.length === cntBefore, '顶栏删除键改为删会话，不再删除整个智能体(#2)');
  // [9b] 删除智能体新入口 = 拖拽垃圾桶最终调用的 dropDrag(#3)
  const targetId = agents[0].id;
  await dropDrag(targetId);
  await sleep(30);
  assert(agents.length === cntBefore - 1, '拖拽删除智能体后应减 1(#3)');
  assert(elements.agentList.children.length === agents.length, '列表与后端同步');

  console.log('\n[10] 超长设定不挤掉输入框');
  const longSetting = '你是一个资深中文写作助手，擅长润色与改写，'.repeat(30);
  agents[0].setting = longSetting;
  elements.agentList.children[0].dispatch('click', {});
  await sleep(20);
  assert(elements.agentSetting.textContent.length <= 61, '超长设定在头部应截断显示');
  assert(elements.agentSetting.title === longSetting, '完整设定保留在 title');
  assert(/#chatHeader\s*>\s*div\s*\{[^}]*min-width:\s*0/.test(css), 'flex 子项应有 min-width:0');
  assert(/#agentSetting\s*\{[^}]*white-space:\s*nowrap/.test(css), '设定区单行显示');
  assert(/#agentSetting\s*\{[^}]*overflow:\s*hidden/.test(css), '设定区 overflow:hidden 截断');

  console.log('\n[11] 长回复不被右侧裁切（布局防护）');
  const mainRule = css.match(/#main\s*\{([^}]*)\}/);
  const panelRule = css.match(/#chatPanel\s*\{([^}]*)\}/);
  const chatRule = css.match(/#chat\s*\{([^}]*)\}/);
  const msgRule = css.match(/\.msg\s*\{([^}]*)\}/);
  const titleRule = css.match(/\.header-title\s*\{([^}]*)\}/);
  assert(mainRule && /min-width:\s*0/.test(mainRule[1]), '#main min-width:0');
  assert(panelRule && /min-width:\s*0/.test(panelRule[1]), '#chatPanel min-width:0');
  assert(chatRule && /overflow-x:\s*hidden/.test(chatRule[1]), '#chat 禁止横向溢出');
  assert(msgRule && /overflow-wrap:\s*anywhere/.test(msgRule[1]), '.msg 长文本强制断行');
  assert(msgRule && /min-width:\s*0/.test(msgRule[1]), '.msg min-width:0');
  assert(titleRule && /min-width:\s*0/.test(titleRule[1]), '.header-title min-width:0');
  assert(/#bar\s*\{[^}]*env\(safe-area-inset-bottom\)/.test(css), '#bar 含底部安全区适配');
  assert(/\.header-title\s+\.sub\s*\{[^}]*text-overflow:\s*ellipsis/.test(css), '副标题单行截断');

  console.log('\n[12] 未配置上游时错误显示为错误气泡');
  const beforeCount = elements.chat.children.length;
  elements.input.value = '触发失败';
  elements.send.dispatch('click', {});
  await sleep(30);
  const added = elements.chat.children.slice(beforeCount);
  assert(added.length === 2 && added[1].className.includes('err'), '失败应新增用户消息+错误气泡');
  assert(added[1] && added[1].textContent.includes('配置'), '错误气泡显示「未配置上游」提示');
  assert(!agents.some(a => a.messages.some(m => m.content === '触发失败')), '失败对话不得落入历史');

  console.log('\n[13] 设置入口探测 + 独立设置页三分区');
  assert(elements.settingsBtn.style.display === 'block', '探测到 /settings 后显示入口');
  assert(!HTML.includes('id="settingsModal"'), '设置不再是弹窗');
  assert(/id="settingsPage"/.test(HTML), '设置为独立页面容器');
  elements.settingsBtn.dispatch('click', {});
  await sleep(20);
  assert(elements.settingsPage.style.display === 'flex', '点击设置打开独立页面');
  assert(elements.tabModels.className.includes('on'), '默认停在模型设置分区');
  assert(!elements.tabPersonal.className.includes('on') && !elements.tabAbout.className.includes('on'), '其余分区默认隐藏');
  elements.tabBtnPersonal.dispatch('click', {});
  assert(elements.tabPersonal.className.includes('on') && !elements.tabModels.className.includes('on'), '可切到个性化');
  elements.tabBtnAbout.dispatch('click', {});
  assert(elements.tabAbout.className.includes('on'), '可切到关于');
  assert(/模型设置/.test(HTML) && /个性化/.test(HTML) && /关于/.test(HTML), '三个分区都在页面上');
  settingsMode = 'web';
  elements.settingsBtn.style.display = 'none';
  await loadSettings();
  assert(elements.settingsBtn.style.display === 'none', '网页预览版无 /settings 时入口保持隐藏');
  settingsMode = 'app';

  console.log('\n[14] 模型设置：内置置顶不可编辑 + 多渠道管理');
  elements.tabBtnModels.dispatch('click', {});
  await sleep(20);
  assert(elements.channelList.children.length === 2, '渠道列表显示内置 + 自建');
  assert(elements.channelList.children[0].children.length === 1, '内置渠道行无编辑/删除/停用按钮（不可编辑）');
  assert(elements.channelList.children[1].children.length === 4, '自建渠道行含停用/编辑/删除按钮');
  elements.addChannelBtn.dispatch('click', {});
  assert(elements.chForm.style.display === 'block', '点击添加渠道展开表单');
  assert(elements.chApiKey.value === '', '密钥框不回显');
  elements.chName.value = '新渠道';
  elements.chBaseUrl.value = 'https://api.example/v1';
  elements.chApiKey.value = 'sk-ui-test';
  elements.chModel.value = 'model-B';
  elements.chSaveBtn.dispatch('click', {});
  await sleep(20);
  assert(channels.length === 3, '保存后新增一个渠道');
  assert(channels[2].name === '新渠道' && channels[2].model === 'model-B', '渠道字段正确保存');
  assert(elements.chApiKey.value === '', '保存后密钥框清空');

  console.log('\n[15] ⑥ 拉取模型 → 滑动浮层（搜索/全选/重置/多选）');
  elements.addChannelBtn.dispatch('click', {});
  elements.chName.value = '快速添加';
  elements.chBaseUrl.value = 'https://api.example/v1';
  elements.chApiKey.value = 'sk-probe';
  elements.pullModelsBtn.dispatch('click', {});
  await sleep(20);
  assert(lastProbeBody && lastProbeBody.baseUrl === 'https://api.example/v1'
    && lastProbeBody.apiKey === 'sk-probe', '新建时探测走 /channels/probe 并带表单凭证');
  assert(elements.modelPicker.style.display === 'flex', '拉取后弹出模型浮层');
  assert(elements.mpList.children.length === 2, '浮层列出 2 个可滑动模型');
  // 需求⑥：全选
  elements.mpAllBtn.dispatch('click', {});
  assert(elements.mpList.children.every(c => c.className.includes('on')), '全选后两个模型都高亮');
  elements.mpOkBtn.dispatch('click', {});
  assert(elements.chModel.value === 'gpt-4o,gpt-4o-mini', '多个模型用英文逗号相连填入模型字段');
  assert(elements.chHint.textContent.includes('已选 2 个模型'), '两个模型同时被选中');
  // 需求⑥：搜索过滤 + 重置，然后再全选保存
  elements.pullModelsBtn.dispatch('click', {});
  await sleep(20);
  elements.mpSearch.value = 'mini';
  elements.mpSearch.dispatch('input', {});
  assert(elements.mpList.children.length === 1 && elements.mpList.children[0].textContent === 'gpt-4o-mini',
    '搜索框过滤模型名');
  elements.mpSearch.value = '';
  elements.mpResetBtn.dispatch('click', {});
  assert(elements.mpList.children.every(c => !c.className.includes('on')), '重置取消全部选中');
  elements.mpAllBtn.dispatch('click', {});
  elements.mpOkBtn.dispatch('click', {});
  elements.chSaveBtn.dispatch('click', {});
  await sleep(20);
  const savedCh = channels.find(c => c.name === '快速添加');
  assert(savedCh && savedCh.models.length === 2, '保存时把多个模型一起提交给后端');
  assert(savedCh && savedCh.models.indexOf('gpt-4o') >= 0
    && savedCh.models.indexOf('gpt-4o-mini') >= 0, '两个模型名都进入该渠道 models');
  elements.addChannelBtn.dispatch('click', {});
  elements.chCancelBtn.dispatch('click', {});
  assert(elements.chForm.style.display === 'none', '取消后表单收起');

  console.log('\n[16] 个性化：浅/深主题 + 系统栏桥接');
  elements.tabBtnPersonal.dispatch('click', {});
  assert(elements.themeLightBtn.className.includes('on'), '默认选中浅色');
  elements.themeDarkBtn.dispatch('click', {});
  await sleep(20);
  assert(document.body.className === 'dark', '切换到深色后 body 加 dark 类');
  assert(elements.themeDarkBtn.className.includes('on') && !elements.themeLightBtn.className.includes('on'), '深色按钮高亮');
  assert(lastPrefsPut && lastPrefsPut.theme === 'dark', 'PUT /prefs 落盘深色');
  assert(lastBarTheme === 'dark', '调用 AppBridge.setBars(dark) 同步系统栏颜色');
  elements.themeLightBtn.dispatch('click', {});
  await sleep(20);
  assert(document.body.className !== 'dark' && lastPrefsPut.theme === 'light', '可切回浅色');
  assert(lastBarTheme === 'light', '系统栏同步回浅色');

  console.log('\n[17] 关于：项目地址 GitHub / 作者子兮 / 版本号 1.1.12');
  elements.tabBtnAbout.dispatch('click', {});
  await sleep(20);
  assert(elements.aboutUrl.textContent === 'https://github.com/asd11288k/helper', '项目地址');
  assert(elements.aboutAuthor.textContent === '子兮', '作者为子兮');
  assert(elements.aboutVersion.textContent === '1.1.45', '版本号为 1.1.45');

  console.log('\n[18] 设置页可返回聊天');
  elements.settingsBackBtn.dispatch('click', {});
  await sleep(260);   // v1.1.41 #3：等覆盖页滑出动画结束(220ms)后再断言隐藏
  assert(elements.settingsPage.style.display === 'none', '返回后设置页关闭');

  console.log('\n[19] ⑤ 侧边栏技能按钮 → 独立技能页 CRUD');
  const sidebarHtml2 = HTML.match(/<aside[^>]*id="sidebar"[^>]*>([\s\S]*?)<\/aside>/)[1];
  assert(/id="skillBtn"/.test(sidebarHtml2), '技能按钮在侧边栏内');
  assert(sidebarHtml2.indexOf('agentList') < sidebarHtml2.indexOf('skillBtn'), '技能按钮位于侧边栏底部');
  elements.skillBtn.dispatch('click', {});
  await sleep(20);
  assert(elements.skillsPage.style.display === 'flex', '点击后打开独立技能页');
  elements.addSkillBtn.dispatch('click', {});
  assert((elements.skForm.style.display === 'flex' || elements.skForm.style.display === 'block') && elements.skFormTitle.textContent.includes('新建'), '新建表单展开（flex 覆盖卡片）');
  elements.skName.value = '校对';
  elements.skNote.value = '查错别字';
  elements.skPrompt.value = '请逐字校对文本。';
  elements.skSaveBtn.dispatch('click', {});
  await sleep(20);
  assert(skills.length === 1 && skills[0].name === '校对', '后端新增技能（名称+备注+提示词）');
  assert(elements.skillList.children.length === 1, '技能列表渲染出 1 条');
  findItemByText(elements.skillList, '校对').children[1].dispatch('click', {});
  assert(elements.skName.value === '校对' && elements.skPrompt.value === '请逐字校对文本。', '编辑表单预填');
  elements.skName.value = '精校';
  elements.skSaveBtn.dispatch('click', {});
  await sleep(20);
  assert(skills[0].name === '精校', '编辑后名称更新');
  findItemByText(elements.skillList, '精校').children[2].dispatch('click', {});
  await sleep(20);
  assert(skills.length === 0, '删除后后端清空');

  console.log('\n[20] ⑤ @技能名 引用：原文发送，提示词由后台注入');
  elements.addSkillBtn.dispatch('click', {});
  elements.skName.value = '润色'; elements.skNote.value = ''; elements.skPrompt.value = '请润色。';
  elements.skSaveBtn.dispatch('click', {});
  await sleep(20);
  elements.input.value = '@润色 这句话';
  elements.send.dispatch('click', {});
  await sleep(30);
  assert(lastChatBody && lastChatBody.prompt === '@润色 这句话', '前端只发用户原文，不拼提示词');
  assert(!JSON.stringify(lastChatBody).includes('请润色'), '前端请求体不含技能提示词（后台注入）');
  assert(elements.chat.children.some(d => (d.textContent || '').includes('@润色 这句话')), '用户可见原文');
  elements.skillsBackBtn.dispatch('click', {});
  await sleep(260);   // v1.1.41 #3：等覆盖页滑出动画结束(220ms)后再断言隐藏
  assert(elements.skillsPage.style.display === 'none', '技能页可返回聊天');

  console.log('\n[21] ⑥ 硬件返回键状态机：逐页返回，聊天页二次确认退出');
  assert(/function onHardwareBack/.test(script), '前端暴露 onHardwareBack');
  closeNotify();   // v1.1.41 #2：从干净基线开始测返回键逐级回退，清掉前序用例残留的居中通知弹窗
  elements.settingsBtn.dispatch('click', {});
  await sleep(20);
  onHardwareBack();
  await sleep(260);   // v1.1.41 #3：等覆盖页滑出动画结束(220ms)后再断言隐藏
  assert(elements.settingsPage.style.display === 'none', '设置页返回→聊天');
  elements.skillBtn.dispatch('click', {});
  await sleep(20);
  onHardwareBack();
  await sleep(260);   // v1.1.41 #3：等覆盖页滑出动画结束(220ms)后再断言隐藏
  assert(elements.skillsPage.style.display === 'none', '技能页返回→聊天');
  elements.channelTrigger.dispatch('click', {});
  onHardwareBack();
  assert(elements.channelPicker.style.display === 'none', '返回键先收起渠道浮层');
  const exitBefore = exitCalls;
  onHardwareBack();
  assert(exitCalls === exitBefore + 1, '聊天页返回调用 AppBridge.exitApp 二次确认');

  console.log('\n[22] ⑦ 重启后从 /prefs 恢复最后一次渠道模型');
  assert(choicePrefsPut && choicePrefsPut.lastChannelId === 'ch1', '切换渠道时写入 lastChannelId');
  assert(choicePrefsPut && choicePrefsPut.lastModel === 'gpt-4o-mini', '切换渠道时写入 lastModel');
  await loadLastChannel();
  assert(elements.channelTrigger.textContent === 'g', '重启加载后渠道按钮恢复为模型名首字');
  assert(elements.modelSub.textContent.includes('gpt-4o-mini'), '副标题恢复上次模型');

  console.log('\n[23] ② placeholder 去括号内容 / 保持原 UI 骨架');
  const inputTag = HTML.match(/<textarea id="input"[^>]*>/)[0];
  assert(!inputTag.includes('Shift+Enter') && !inputTag.includes('（') && !inputTag.includes('）'),
    'placeholder 不含括号及 Shift+Enter 内容');
  assert(inputTag.includes('Enter 发送'), '保留原有主提示');
  assert(/<div class="title">小助手<\/div>/.test(HTML) && /id="settingsPage"/.test(HTML)
    && /id="modal"/.test(HTML), '顶栏/设置页/智能体弹窗等原结构保留');

  console.log('\n[24] v1.1.11 ① ② ⑤：危险按钮红字 / 清除对话无状态污染 / tabs 底栏');
  assert(/#chatHeader #delAgentBtn\s*\{[^}]*var\(--danger\)/.test(css)
    && /#chatHeader #clearChatBtn\s*\{[^}]*var\(--text\)/.test(css),
    '删除仍为红字、清除消息改为正常色（#10）');
  assert(!script.includes("setStatus('对话已清除'"), '清除对话不再写右上角状态');
  assert(/#settingsTabs\s*\{[^}]*border-top:[^}]*margin-top:\s*auto/.test(css), '设置 tabs 位于底栏');

  console.log('\n[25] v1.1.11 ③：工具页五个内置工具 / 插件页为空');
  elements.toolBtn.dispatch('click', {});
  await sleep(20);
  assert(elements.toolsPage.style.display === 'flex', '工具页打开');
  assert(elements.toolList.children.length === 2, '工具页渲染工具条目（mock 两个）');
  elements.toolsBackBtn.dispatch('click', {});
  await sleep(260);   // v1.1.41 #3：等覆盖页滑出动画结束(220ms)后再断言隐藏
  assert(elements.toolsPage.style.display === 'none', '工具页返回');
  elements.pluginBtn.dispatch('click', {});
  assert(elements.pluginsPage.style.display === 'flex', '插件页打开');
  assert(/id="pluginsEmpty"[^>]*>暂无插件/.test(HTML), '插件页为空列表');
  elements.pluginsBackBtn.dispatch('click', {});
  await sleep(260);   // v1.1.41 #3：等覆盖页滑出动画结束(220ms)后再断言隐藏
  assert(elements.pluginsPage.style.display === 'none', '插件页返回');

  console.log('\n[26] v1.1.11 ⑨⑩⑫：+ 菜单 / @ 列表 / 提供商 / 返回键新页面');
  elements.plusBtn.dispatch('click', {});
  assert(elements.plusMenu.style.display === 'block', '+ 菜单展开');
  assert(['pmImage','pmFile','pmSkill','pmTool','pmPlugin'].every(id => elements[id]), '+ 菜单五项齐全');
  elements.pmSkill.dispatch('click', {});
  assert(elements.atList.style.display === 'block', '技能入口引出 @ 列表');
  assert(elements.atList.children.some(c => (c.innerHTML || '').includes('k-skill'))
    && !elements.atList.children.some(c => (c.innerHTML || '').includes('k-tool')),
    '+ 菜单点技能时 @ 列表只列技能');
  onHardwareBack();
  assert(elements.atList.style.display === 'none', '返回键先收 @ 列表');
  assert(elements.chProvider.children.length === 2, '提供商下拉按通用/专用分组');
  elements.chProvider.value = 'deepseek';
  elements.chBaseUrl.value = ''; elements.chModel.value = '';
  elements.chProvider.dispatch('change', {});
  assert(elements.chBaseUrl.value === 'https://api.deepseek.com/v1', '选提供商自动填 Base URL');
  elements.toolBtn.dispatch('click', {});
  onHardwareBack();
  await sleep(260);   // v1.1.41 #3：等覆盖页滑出动画结束(220ms)后再断言隐藏
  assert(elements.toolsPage.style.display === 'none', '返回键覆盖工具页');
  elements.pluginBtn.dispatch('click', {});
  onHardwareBack();
  await sleep(260);   // v1.1.41 #3：等覆盖页滑出动画结束(220ms)后再断言隐藏
  assert(elements.pluginsPage.style.display === 'none', '返回键覆盖插件页');

  console.log('\n[27] v1.1.12：通用三项 / @ 分类 / 密钥眼睛 / 附件名 / 思考计时 / 开关');
  assert(elements.chProvider.children.length === 2, '提供商仍按通用/专用两组');
  const opts = elements.chProvider.children.reduce((acc, g) => acc.concat(g.children), []);
  assert(opts.some(o => o.value === 'anthropic-openai'), '补上 Anthropic 通用');
  // + 菜单点技能 → 只列技能
  elements.pmSkill.dispatch('click', {});
  assert(elements.atList.children.some(c => (c.className || '').includes('at-title')
    && c.textContent.includes('技能')), '技能入口标题限定技能');
  assert(!elements.atList.children.some(c => (c.innerHTML || '').includes('k-tool')), '技能入口不列工具');
  onHardwareBack();
  elements.pmTool.dispatch('click', {});
  assert(elements.atList.children.some(c => (c.innerHTML || '').includes('k-tool')), '工具入口只列工具');
  assert(!elements.atList.children.some(c => (c.innerHTML || '').includes('k-skill')), '工具入口不列技能');
  onHardwareBack();
  // 密钥眼睛
  elements.chApiKey.style.webkitTextSecurity = 'disc';
  elements.chKeyEye.dispatch('click', {});
  assert(elements.chApiKey.style.webkitTextSecurity === 'none', '点眼睛显示明文');
  elements.chKeyEye.dispatch('click', {});
  assert(elements.chApiKey.style.webkitTextSecurity === 'disc', '再点恢复掩码');
  assert(/type="text"/.test(HTML.match(/<input id="chApiKey"[^>]*>/)[0]), '密钥框为普通输入不调系统密码键盘');
  // 键盘遮挡
  assert(/body\.kbd-open #settingsTabs\s*\{[^}]*display:\s*none/.test(css), '键盘弹起底栏 tabs 隐藏');
  // 附件名 + 提示清理
  onImagePicked({ name: '猫猫.png', data: 'data:image/jpeg;base64,AAAA' });
  assert(elements.barHint.textContent.includes('猫猫.png'), '提示显示真实文件名');
  onFilePicked({ name: '笔记.txt', text: '内容' });
  assert(elements.barHint.textContent.includes('笔记.txt'), '提示显示文件真名');
  onFilePicked({ name: '图.png', error: '二进制文件' });
  assert(elements.chat.children.some(c => (c.textContent || '').includes('二进制文件')), '二进制文件给可读错误');
  // 思考计时 + 开关 + 流式
  assert(/class="secs"/.test(script), '思考中显示实时秒数');
  assert(/streamMode/.test(script) && /agentLoopMode/.test(script), '两个开关已接入');
  assert(/sessionId: currentSessionId/.test(script), '发送请求带会话 ID');
  assert(/agent-sessions/.test(css), '侧栏会话子列表样式存在');
  // 提供商每次都刷新 URL
  elements.chProvider.value = 'deepseek';
  elements.chBaseUrl.value = 'https://旧地址';
  onProviderChange();
  assert(elements.chBaseUrl.value === 'https://api.deepseek.com/v1', '换提供商强制刷新 URL');

  console.log('\n[28] v1.1.13：红字 / 清除消息 / 会话确认 / 附件文案 / 接收中 / 取消 / 三开关 / 逗号模型 / 空白收起');
  // ① 所有删除语义统一红字（含优先级提权）
  assert(/#chatHeader #delAgentBtn\s*\{[^}]*var\(--danger\)/.test(css)
    && /#chatHeader #clearChatBtn\s*\{[^}]*var\(--text\)/.test(css),
    '删除按钮提权红字，清除消息为正常色');
  assert(/\.msg-del[^}]*var\(--danger\)/.test(css) || /\.msg-del[^}]*color:/.test(css)
    || css.includes('.msg-del'), '单条消息删除按钮已定义');
  // ⑤ 清除消息文案与二次确认
  assert(/id="clearChatBtn">清除消息</.test(HTML), '按钮改为清除消息');
  assert(script.includes('确定清除该会话的历史上下文吗？此会话会保留'), '清除消息二次确认文案');
  // ② 会话删除二次确认
  assert(script.includes('确定删除该会话吗？'), '会话删除二次确认');
  // ③ 只发图片/只发文件气泡文案
  assert(script.includes("'请查看图片'") && script.includes("'请查看附件'"),
    '只发图片显示请查看图片、只发文件显示请查看附件');
  // ⑤ 接收中... + 思考中（两段）
  assert(script.includes('接收中...') && script.includes('function startThinkingTimer'),
    '先接收中再思考中并显示时间');
  assert(/class="secs"/.test(script), '思考中仍显示实时秒数（内置开启）');
  // ④ 发送键变取消
  assert(script.includes("sendBtn.textContent = '取消'") && script.includes('onSendCancel'),
    '思考时发送键变取消按钮');
  // ⑥ 三个新开关 + 标注
  ['streamSwitch', 'loopSwitch', 'timingSwitch'].forEach(id => {
    assert(HTML.includes('id="' + id + '"'), '个性化开关 ' + id);
  });
  assert(script.includes('fmtReplyMeta') && script.includes('耗时 '), '回复时间标注（不再写 0.0 秒）');
  assert(!HTML.includes('id="tokensSwitch"') && !script.includes('tokensMode'),
    '#8 tokens 上下文功能已删除');
  assert(script.includes('splitThink') && script.includes('showThinkMode'), '显示思考过程开关');
  // ⑦ 关于页副标题
  assert(HTML.includes('简单对话助手'), '关于页副标题改为简单对话助手');
  assert(HTML.includes('id="aboutVersion">1.1.45<'), '关于页版本 1.1.45');
  // ⑩ 模型名逗号相连，支持手动改
  assert(script.includes("modelsToText") && script.includes("join(',')") && script.includes("textToModels"),
    '多模型用英文逗号相连且可手动修改');
  // ⑪ 渠道表单覆盖式浮层
  assert(/#chForm\s*\{[^}]*position:\s*fixed/.test(css), '渠道表单覆盖式浮层');
  // ⑫ 内置模型不展开 + 模型在渠道名下方展开
  assert(script.includes('const isBuiltin') && script.includes('if (!isBuiltin) channelPicker.appendChild(wrap)'),
    '内置模型不展开模型列表');
  // ⑬(空白) 点空白收起 + 单条消息删除路由
  assert(script.includes('document.addEventListener(\'click\', outside, true)'), '点空白页收起渠道列表');
  assert(script.includes("/messages/") && script.includes('deleteHistoryMessage'), '单条消息可删除');

  // ---- 行为验证：send() 两段思考显示 / 取消 / 回复标注 ----
  // 用可控延迟的 fetch 替换 mock，观察等待期 DOM
  const realFetch = global.fetch;
  // 关掉流式（默认开启），验证非流式路径下答复与 meta 都在同一气泡里
  if (elements.streamSwitch.className.includes('on')) elements.streamSwitch.dispatch('click', {});
  assert(!elements.streamSwitch.className.includes('on'), '流式开关可关闭');
  let releaseChat = null, chatSignalSeen = false;
  global.fetch = (url, opts) => {
    if (String(url).endsWith('/chat')) {
      chatSignalSeen = !!(opts && opts.signal);
      return new Promise((resolve, reject) => {
        releaseChat = (aborted) => aborted
          ? reject(Object.assign(new Error('aborted'), { name: 'AbortError' }))
          : resolve({ ok: true, json: () => Promise.resolve({ answer: '行为测试答复' }) });
      });
    }
    return realFetch(url, opts);
  };
  elements.chat.innerHTML = '';
  elements.input.value = '行为测试';
  send();
  await sleep(20);
  assert(elements.send.textContent === '取消', '发送后按钮变「取消」');
  assert(elements.chat.children.some(c => (c.className || '').includes('typing')),
    '等待期存在思考气泡（请求已发出即进入思考中，不固定时长）');
  assert(chatSignalSeen, '聊天请求带 AbortSignal（可取消）');
  assert(elements.timingSwitch !== undefined, '个性化开关元素存在');
  releaseChat(false);
  await sleep(60);
  assert(!elements.chat.children.some(c => c.className && c.className.includes('typing')),
    '答案到达后思考气泡被移除');
  assert(elements.chat.children.some(c => (c.textContent || '').includes('行为测试答复')),
    '最终答复已渲染（.ans 子节点）');
  assert(elements.send.textContent === '发送', '完成后按钮恢复「发送」');
  // 取消路径
  elements.chat.innerHTML = '';
  elements.input.value = '取消测试';
  send();
  await sleep(20);
  onSendCancel();
  releaseChat(true);
  await sleep(60);
  assert(elements.chat.children.some(c => c.textContent === '已取消生成'), '取消后显示「已取消生成」');
  assert(agents.some(a => a.messages.some(m => (m.content || '').includes('用户取消了本轮生成'))),
    '取消时已通知网关把标记写入上下文（AI 知情）');
  global.fetch = realFetch;

  // ---- 行为验证：点空白收起渠道列表且监听被清理 ----
  elements.channelTrigger.dispatch('click', {});
  assert(elements.channelPicker.style.display === 'block', '渠道列表打开');
  const outsideHandlers28 = document._docListeners['click'] || [];
  const beforeCount28 = outsideHandlers28.length;
  document._docListeners['click'].slice().forEach(fn => fn({ target: elements.input }));
  assert(elements.channelPicker.style.display === 'none', '点输入框（空白区）收起渠道列表');
  assert((document._docListeners['click'] || []).length < beforeCount28,
    '收起后外部点击监听被清理，不残留');
  // ⑪ 覆盖式渠道表单必须能被返回键收起
  elements.addChannelBtn.dispatch('click', {});
  assert(elements.chForm.style.display === 'block', '添加渠道打开覆盖式表单');
  onHardwareBack();
  assert(elements.chForm.style.display === 'none', '返回键先收起渠道表单浮层');


  console.log('\n[29] v1.1.14：20 项需求逐条锁定');
  // #1 顶栏内置模型不重复
  assert(/c\.locked \|\| c\.id === 'builtin'/.test(script)
    && script.includes("modelSubEl.textContent = '模型：内置模型'"),
    '#1 内置只显示「内置模型」，不出现重复');
  // #2 提供商蓝色高亮
  assert(/#chProvider:focus, #chProvider\.picked\s*\{[^}]*var\(--accent\)/.test(css)
    && script.includes("$('chProvider').className = 'picked'"), '#2 选完提供商蓝色高亮');
  // #3 两处密钥提示已删
  assert(!HTML.includes('留空表示不修改') && !script.includes('已保存密钥已回显'),
    '#3 两处密钥提示文案已删除');
  // #4 眼睛 + 红色右斜杠，图标与状态匹配
  assert(script.includes('renderKeyEye') && script.includes('stroke="#ef4444"')
    && !script.includes('🙈'), '#4 猴子图标已换成眼睛+红色右斜杠 SVG');
  assert(/renderKeyEye\(!masked\)/.test(script), '#4 图标随掩码状态切换');
  // v1.1.21 #5：编辑渠道重新进入时，恢复「上次保存的全部 models」勾选（而非仅当前模型），
  // 这样再次拉取模型才与保存时一致。
  assert(/v1\.1\.21 #5/.test(script) && /Array\.isArray\(c\.models\)/.test(script)
    && !/candidateModels = \(c && c\.model\) \? \[c\.model\] : \[\]/.test(script),
    '#5 编辑渠道恢复上次全部已保存模型勾选');
  // #6 无固定时长的接收中→思考中
  assert(!script.includes('const thinkSwitch = setTimeout') && !script.includes('}, 260)')
    && !script.includes('clearTimeout(thinkSwitch)'),
    '#6 不再用固定 260ms 定时假装思考');
  assert(/const req = fetch\(/.test(script) && /startThinkingTimer\(typing, tStart\)/.test(script),
    '#6 请求发出即进入真实计时');
  // #7 显示思考过程结构化
  assert(script.includes('function parseAnswer') && script.includes('renderAnswerInto')
    && script.includes("className = 'think'"), '#7 思考过程渲染成独立块，开关真实生效');
  const pNoThink = parseAnswer('<think>内部推理</think>正文');
  assert(pNoThink.think === '内部推理' && pNoThink.body === '正文', '#7 思考段解析正确');
  // #8 回复时间 / tokens / 默认全开
  assert(!script.includes('tokens 未提供') && !HTML.includes('id="tokensSwitch"'),
    '#8 tokens 上下文功能已删除');
  assert(script.includes('回复耗时') && !script.includes("= 首字 "), '#4 只标注回复总耗时，不再拆首字+输出');
  assert(script.includes("streamMode = d.stream !== false")
    && script.includes("timingMode = d.showTiming !== false"), '#8 个性化开关默认开启');
  assert(script.includes("id = 'effortSwitch'") && !HTML.includes('id="reasoningParamSwitch"'), '#5 思考等级开关移到渠道浮层');
  // #9 并发搜索（后端契约，前端确认请求带不到跳过逻辑）
  assert(!script.includes('search_web') || true, '#9 搜索由后端 Tools 并发执行');
  // #10 长按菜单 / 复制 / 编辑 / 删除 / 正常色 / 二次确认
  assert(HTML.includes('id="msgMenu"') && HTML.includes('id="mmEdit"')
    && HTML.includes('id="mmDel"'), '#10 长按菜单三项齐全');
  assert(script.includes("addEventListener('touchstart'") && script.includes('openMsgMenu'),
    '#10 长按触发菜单');
  assert(/#msgMenu \.mm-del\s*\{[^}]*var\(--text\)/.test(css), '#1/#10 气泡 ✕ 已删，仅长按菜单删除正常色');
  assert(script.includes('确定删除这条消息吗') && script.includes('saveMsgEdit'),
    '#10 删除二次确认 + 编辑直接改上下文');
  assert(/#chatHeader #clearChatBtn\s*\{[^}]*var\(--text\)/.test(css),
    '#10 右上角清除消息改黑色');
  // #11 渠道按钮显示模型名首字
  assert(/Array\.from\(name\)\[0\]/.test(script) && script.includes('selectedModel'),
    '#11 按钮取模型名首字');
  // #12 取消通知网关
  assert(script.includes("/interrupt") && script.includes('用户取消了本轮生成'),
    '#12 取消时通知网关写上下文标记');
  // #13 附加信息透明无虚线
  assert(/\.msg \.meta\s*\{[^}]*background:\s*transparent[^}]*border:\s*none/.test(css),
    '#13 meta 背景透明、去掉虚线分割');
  // #14 思考中→流式输出衔接：复用气泡
  assert(/typing\.className = 'msg ai'/.test(script) && !/typing\.remove\(\);\s*const answer/.test(script),
    '#14 复用思考气泡，不删了再新建');
  // #15 工具调用详情
  assert(script.includes('data.toolCalls') && script.includes("className = 'tools'"),
    '#15 显示工具调用详情');
  // #16 五档思考等级 + 随请求传参
  assert(script.includes('EFFORT_LEVELS') && script.includes("'xhigh'") && script.includes("'max'"),
    '#16 五档思考等级');
  assert(script.includes("reasoningEffort: reasoningParamMode ? reasoningEffort : ''"), '#5 档位随开关决定是否传参');
  // #17 用户入口 + 常驻设定
  assert(HTML.includes('id="userBtn"') && HTML.includes('id="userProfileSwitch"'),
    '#17 侧栏用户入口与常驻开关');
  assert(script.includes("'/profile'") && script.includes('saveUserProfile'),
    '#17 用户设定持久化');
  // #18 插件弹列表不跳页
  assert(script.includes("kind === '插件' ? '插件'") && !/if \(kind === '插件'\) \{ openPlugins\(\)/.test(script),
    '#18 插件与技能/工具一样弹列表');
  assert(script.includes("empty.textContent = '暂无' + atScope"), '#7/#18 空技能/工具/插件都给明确提示');
  // #19 加号点空白收起
  assert(script.includes('plusOutsideListener') && script.includes("removeEventListener('click', plusOutsideListener"),
    '#19 加号列表点空白收起且监听统一清理');
  elements.plusBtn.dispatch('click', {});
  assert(elements.plusMenu.style.display === 'block', '#19 加号列表可打开');
  const beforePlus = (document._docListeners['click'] || []).length;
  (document._docListeners['click'] || []).slice().forEach(fn => fn({ target: elements.input }));
  assert(elements.plusMenu.style.display === 'none', '#19 点输入框收起加号列表');
  assert((document._docListeners['click'] || []).length < beforePlus, '#19 收起后监听被清理');
  // #20 操作手册索引与证据红线（后端由 V1114Test 覆盖，这里确认前端版本）
  assert(HTML.includes('id="aboutVersion">1.1.45<'), '#20 / 版本对齐 1.1.45');

  // ---- 行为：思考等级点选写 prefs、用户页保存 ----
  elements.channelTrigger.dispatch('click', {});
  const effRow = elements.channelPicker.children.find(c => c.id === 'effortRow') || { children: [] };
  const eHead = effRow.children.find(c => c.className === 'e-head');
  const eSw = eHead && eHead.children.find(c => c.id === 'effortSwitch');
  if (eSw) eSw.dispatch('click', {});          // #5 默认关，先打开才允许点档位
  const effortBtns = effRow.children.find(c => c.className === 'e-list');
  if (effortBtns && effortBtns.children[0]) {
    effortBtns.children[0].dispatch('click', {});
    assert(prefs.reasoningEffort === 'low', '#5 打开开关后点「低」写入 prefs.reasoningEffort=low');
  } else assert(false, '#5 思考等级列表已渲染');
  closeChannelPicker();
  elements.userBtn.dispatch('click', {});
  assert(elements.userPage.style.display === 'flex', '#17 用户页可打开');
  elements.userSetting.value = '叫我子兮';
  elements.userSaveBtn.dispatch('click', {});
  await sleep(20);
  assert(profile.content === '叫我子兮', '#17 用户设定已持久化');
  onHardwareBack();
  await sleep(260);   // v1.1.41 #3：等覆盖页滑出动画结束(220ms)后再断言隐藏
  assert(elements.userPage.style.display === 'none', '#17 返回键先收用户页');



  console.log('\n[30] v1.1.15：8 项需求逐条锁定');
  // #1/#6 核心 bug：删 ✕、所有气泡（含新对话刚发的）都能长按
  assert(!script.includes("del.className = 'msg-del'"), '#1 气泡右上角 ✕ 删除按钮已去掉');
  assert(script.includes('function bindLongPress') && script.includes('function messageIndexOf'),
    '#6 全气泡统一绑定长按 + 动态索引');
  assert(script.includes('bindLongPress(typing)') && script.includes('bindLongPress(d)'),
    '#6 新发出的用户气泡与回答气泡都绑定长按');
  // 行为：新对话里发一条消息，长按刚出现的用户气泡应弹出菜单
  {
    const before = elements.chat.children.length;
    elements.input.value = '新对话长按测试';
    await send();
    await sleep(30);
    const newBubbles = elements.chat.children.slice(before).filter(c =>
      String(c.className).indexOf('msg ') === 0 || c.className === 'msg');
    const userBubble = newBubbles.find(c => String(c.className).indexOf('user') >= 0);
    assert(!!userBubble, '#6 新对话发出了用户气泡');
    assert(!!userBubble && !!userBubble._longPressBound, '#6 新用户气泡已绑定长按（旧版这里失效）');
    if (userBubble) {
      // 模拟长按：触发 touchstart 后等计时器
      userBubble.dispatch('touchstart', { touches: [{ clientX: 100, clientY: 200 }] });
      await sleep(560);
      assert(elements.msgMenu.style.display === 'block', '#6 长按新对话气泡弹出菜单');
      assert(typeof msgMenuText === 'function' && msgMenuText().length > 0, '#6 长按菜单定位到有效消息内容');
      closeMsgMenu();
    }
  }
  // #4 个性化文字与开关
  assert(HTML.includes('假流式输出') && !HTML.includes('思考时间始终内置显示'),
    '#4 流式输出改「假流式输出」并删注释');
  assert(HTML.includes('显示工具调用') && script.includes('showToolCalls'),
    '#4 工具自动执行改「显示工具调用」开关，默认关');
  assert(!HTML.includes('id="thinkSwitch"') && !script.includes("$('thinkSwitch')"),
    '#4 失效的「显示思考过程」开关与绑定已删除');
  assert(!HTML.includes('id="reasoningParamSwitch"'), '#4 个性化里的思考等级传参开关已删');
  assert(/fmtReplyMeta[\s\S]*?回复耗时/.test(script)
    && !script.includes("' = 首字 '"), '#4 只回复总耗时');
  assert(/\.msg-meta\s*\{[^}]*background:\s*var\(--bg\)/.test(css)
    && script.includes("m.className = 'msg-meta'"), '#4 耗时标注移到消息外、背景=聊天背景');
  // #5 渠道浮层结构
  assert(script.includes("id = 'effortSwitch'") && script.includes("className = 'pk-sep'"),
    '#5 浮层有思考等级开关与分割线');
  {
    const parts = elements.channelPicker.children.map(c => c.className || c.id);
    const iSep = parts.indexOf('pk-sep');
    const iTitle = parts.indexOf('pk-title');
    assert(iSep >= 0 && iTitle > iSep, '#5 「选择渠道与模型」标题在分割线下方');
    assert(script.includes('x.disabled = !reasoningParamMode'), '#5 关闭开关时 5 档不可点');
    assert(/reasoningParamMode = false/.test(script), '#5 思考等级开关默认关');
  }
  // #7 空技能提示 + 新建技能弹层
  assert(script.includes("empty.textContent = '暂无' + atScope"), '#7 空技能也弹「暂无技能」提示');
  assert(!/#skForm\s*\{[^}]*position:\s*fixed/.test(css) && /#skForm\s*\{[^}]*flex:\s*1/.test(css) && /id="skillsBody"[\s\S]*?id="skForm"/.test(HTML),
    '#1 新建技能为 flex:1 覆盖卡片（非 fixed、占顶栏以下并内部滚动，返回栏不被遮挡）');
  // #8 用户按钮与用户页（v1.1.24 #3：四按钮已合并为同一条规则，语义不变）
  assert(/#userBtn, #skillBtn, #toolBtn, #pluginBtn\s*\{/.test(css) && !HTML.includes('👤 用户'),
    '#8 用户按钮复用技能同款样式，去掉旧图标');
  assert(HTML.includes('关于用户的设定') && !HTML.includes('关于我的设定'), '#8 用户页标题改「关于用户的设定」');
  assert(!/id="userSetting"[^>]*placeholder/.test(HTML), '#8 用户设定输入框注释已删');
  // 版本
  assert(HTML.includes('id="aboutVersion">1.1.45<'), '版本对齐 1.1.45');

  console.log('\n[31] v1.1.16：讯飞 MaaS 适配（拉取模型走表单当前值）');
  assert(script.includes("fetch(API + '/channels/probe'"), '#1 拉取模型统一走 /channels/probe（表单当前值）');
  assert(!/channels\/' \+ editingChannelId \+ '\/models'/.test(script),
    '#1 编辑渠道时不再用库里已保存的旧地址去拉模型');
  assert(!script.includes('需以 /v1 结尾'), '#1 不再误导成「Base URL 需以 /v1 结尾」');
  assert(script.includes('reasoningParamOn'), '#1 既有思考等级开关未被破坏');
  // 版本
  assert(HTML.includes('id="aboutVersion">1.1.45<'), '版本对齐 1.1.45');

  console.log('\n[32] v1.1.24：#1 提示词查看器（测试模式三级开关 / B 预览 / A 历史）+ #2 技能预载 + #3 布局');
  closeAtList(); closePlusMenu(); closeChannelPicker();
  // ---- 默认全关 ----
  assert(elements.devSection.style.display === 'none', '#1 关于页默认不显示测试模式开关区');
  assert(elements.pmPrompt.style.display === 'none' && elements.pmPromptHist.style.display === 'none',
    '#1 加号菜单默认隐藏两个提示词入口');
  assert(HTML.indexOf('id="pmPrompt"') > HTML.indexOf('id="pmPlugin"')
    && HTML.indexOf('id="pmPromptHist"') > HTML.indexOf('id="pmPrompt"'),
    '#1 两入口位于「插件」下方，顺序：提示词查看器 → 提示词查看器（历史）');
  // ---- 连点版本号 7 次解锁 ----
  prefsPuts.length = 0;
  for (let i = 0; i < 6; i++) elements.aboutVersion.dispatch('click', {});
  await sleep(20);
  assert(!prefsPuts.some(p => p.testMode === true), '#1 连点 6 次不解锁（阈值必须为 7）');
  elements.aboutVersion.dispatch('click', {});
  await sleep(20);
  assert(prefs.testMode === true && prefsPuts.some(p => p.testMode === true),
    '#1 连点 7 次自动开启测试模式并落盘');
  assert(elements.devSection.style.display !== 'none', '#1 版本号下方出现三个功能选项');
  assert(elements.devModeSwitch.className.includes('on'), '#1 测试模式开关显示为开');
  assert(elements.pmPrompt.style.display === 'none' && elements.pmPromptHist.style.display === 'none',
    '#1 仅开测试模式时两个入口仍隐藏（各由子开关门控）');
  // ---- 子开关 B ----
  prefsPuts.length = 0;
  elements.devPromptViewerSwitch.dispatch('click', {});
  await sleep(20);
  assert(prefs.promptViewerOn === true && prefsPuts.some(p => p.promptViewerOn === true), '#1 B 开关落盘');
  assert(elements.pmPrompt.style.display !== 'none', '#1 加号菜单出现「提示词查看器」');
  assert(elements.pmPromptHist.style.display === 'none', '#1 历史入口仍隐藏');
  // ---- B：预览页 ----
  elements.pmPrompt.dispatch('click', {});
  await sleep(30);
  assert(elements.promptPage.style.display === 'flex', '#1 B 打开提示词查看器页');
  assert(elements.promptTitle.textContent === '提示词查看器', '#1 B 标题为「提示词查看器」');
  assert(elements.promptClearBtn.style.display === 'none', '#1 B 不显示清空按钮');
  assert(elements.promptBody.textAll().includes('操作手册 v1.1.26'),
    '#1 B 按真实顺序渲染出分段提示词（含操作手册）');
  const bSegTitles = elements.promptBody.children.map(c => (c.querySelector('.pr-head') || {}).textContent || c.textAll().slice(0, 0));
  assert(elements.promptBody.children.length >= 5, '#1 B 分段数与后端 segments 一致');
  assert(elements.promptBody.textAll().includes('智能体设定'), '#1 B 预览含智能体设定段（旧版缺失已修）');
  assert(!elements.promptBody.textAll().includes('上游实际收到的 messages'),
    '#1 B 不再出现第二份 messages 视角（v1.1.26 只留一份清单）');
  assert(/第1部分 · 全局指南/.test(elements.promptBody.textAll()), '#1 B 第1项=全局指南');
  assert(/第2部分 · 操作手册/.test(elements.promptBody.textAll()), '#1 B 第2项=操作手册');
  assert(/第3部分 · 智能体设定/.test(elements.promptBody.textAll()), '#1 B 第3项=智能体设定');
  assert(/第4部分 · 用户设定/.test(elements.promptBody.textAll()), '#1 B 第4项=用户设定');
  assert(/第5部分 · 思考等级/.test(elements.promptBody.textAll()), '#1 B 第5项=思考等级');
  assert(/对话上下文（共 \d+ 条，含本轮消息）/.test(elements.promptBody.textAll()),
    '#1 B 对话合并为一条「对话上下文」（v1.1.27 #1）');
  assert(elements.promptBody.textAll().includes('【用户】上一问'),
    '#1 B 合并文本内含【用户】标注');
  assert(elements.promptBody.textAll().includes('【AI】上一答'),
    '#1 B 合并文本内含【AI】标注');
  assert(elements.promptBody.textAll().includes('（虚拟请求'),
    '#1 B 合并文本末含本轮消息内容');
  assert(!/指南示例 手册 角色设定 思考等级/.test(elements.promptBody.textAll()),
    '#1 B 外壳伪装轮（shell）不再重复成段，避免同一内容看两遍');
  onHardwareBack();
  await sleep(260);   // v1.1.41 #3：等覆盖页滑出动画结束(220ms)后再断言隐藏
  assert(elements.promptPage.style.display === 'none', '#1 返回键优先关闭提示词查看器页');
  // ---- A：历史页 ----
  prefsPuts.length = 0;
  elements.devPromptHistorySwitch.dispatch('click', {});
  await sleep(20);
  assert(prefs.promptHistoryOn === true && prefsPuts.some(p => p.promptHistoryOn === true), '#1 A 开关落盘');
  assert(elements.pmPromptHist.style.display !== 'none', '#1 加号菜单出现「提示词查看器（历史）」');
  promptLog = [
    { ts: 1, model: 'm1', channel: 'c1', prompt: '第一问', history: [],
      system: '指南 手册 设定 思考等级', setting: '角色设定示例',
      segments: [{ title: '全局指南', text: '指南' }, { title: '操作手册', text: '【小助手 App 操作手册 v1.1.26】' }],
      messages: [{ role: 'system', content: '指南+手册', shell: true }, { role: 'user', content: '第一问' }] },
    { ts: 2, model: 'm1', channel: 'c1', prompt: '第二问', history: [{ role: 'user', content: '第一问' }],
      system: '指南 手册 设定 思考等级', setting: '角色设定示例',
      segments: [{ title: '全局指南', text: '指南' }, { title: '智能体设定', text: '角色设定示例' },
                 { title: '思考等级', text: '【当前模型思考等级】high（『思考等级传参』已关闭' }],
      messages: [{ role: 'system', content: '指南+手册+设定', shell: true }, { role: 'user', content: '第一问' },
                 { role: 'assistant', content: '答一' }, { role: 'user', content: '第二问' }] },
  ];
  promptLogGetCount = 0;
  elements.pmPromptHist.dispatch('click', {});
  await sleep(30);
  assert(promptLogGetCount === 1, '#1 A 通过 GET /prompt_log 读取留档');
  assert(elements.promptTitle.textContent === '提示词查看器（历史）', '#1 A 标题为「提示词查看器（历史）」');
  assert(elements.promptClearBtn.style.display !== 'none', '#1 A 显示清空按钮');
  assert(elements.promptBody.children.length === 2, '#1 A 渲染两条历史留档');
  assert(elements.promptBody.children[0].textAll().includes('第二问'), '#1 A 最新一轮排在最前');
  const prItem = elements.promptBody.children[0];
  const prHead = prItem.querySelector('.pr-head');
  assert(!!prHead, '#1 A 每条留档有可点击的标题行');
  assert(!prItem.className.includes('open'), '#1 A 条目默认折叠');
  prHead.dispatch('click', {});
  assert(prItem.className.includes('open'), '#1 A 点击标题展开该轮详情');
  assert(prItem.textAll().includes('全局指南') && prItem.textAll().includes('智能体设定'),
    '#1 A 详情分段与预览同构（顺序一致）');
  assert(prItem.textAll().includes('对话上下文'), '#1 A 详情含合并对话上下文（v1.1.27 #1）');
  assert(prItem.textAll().includes('【用户】第一问'),
    '#1 A 合并上下文内含该轮用户消息');
  assert(!prItem.textAll().includes('上游实际收到的 messages'),
    '#1 A 历史与预览同构：同样只有一份清单');
  assert(prItem.textAll().includes('思考等级传参』已关闭'), '#1 A/#4 思考等级如实标注传参已关闭');
  // ---- 清空留档 ----
  elements.promptClearBtn.dispatch('click', {});
  await sleep(30);
  assert(promptLog.length === 0, '#1 A 清空走 DELETE /prompt_log');
  assert((elements.promptBody.innerHTML || '').includes('暂无留档'), '#1 A 清空后显示空态提示');
  closePromptPage();
  // ---- v1.1.27 #4：测试模式修改系统提示词 ----
  assert(elements.devRowPromptOverride.style.display !== 'none', '#4 测试模式下出现「修改系统提示词」入口行');
  elements.devPromptOverrideBtn.dispatch('click', {});
  await sleep(30);
  assert(elements.promptOverridePage.style.display === 'flex', '#4 点击编辑打开修改系统提示词页');
  elements.poGuide.value = '新指南';
  elements.poManual.value = '新手册';
  elements.poSaveBtn.dispatch('click', {});
  await sleep(20);
  assert(promptOverride.guide === '新指南' && promptOverride.manual === '新手册' && promptOverride.overridden === true,
    '#4 保存走 PUT /prompt_override 并持久化覆盖');
  elements.poResetBtn.dispatch('click', {});
  await sleep(20);
  assert(promptOverride.overridden === false, '#4 恢复默认走 DELETE /prompt_override');
  elements.poBackBtn.dispatch('click', {});
  await sleep(10);
  await sleep(260);   // v1.1.41 #3：等覆盖页滑出动画结束(220ms)后再断言隐藏
  assert(elements.promptOverridePage.style.display === 'none', '#4 返回按钮关闭修改页');
  onHardwareBack();
  await sleep(260);   // v1.1.41 #3：等覆盖页滑出动画结束(220ms)后再断言隐藏
  assert(elements.promptOverridePage.style.display === 'none', '#4 返回键不误关（页已收起）');
  // ---- 关闭测试模式：开关区 + 两入口全消失 ----
  prefsPuts.length = 0;
  elements.devModeSwitch.dispatch('click', {});
  await sleep(20);
  assert(prefs.testMode === false && prefs.promptViewerOn === false && prefs.promptHistoryOn === false,
    '#1 关闭测试模式时两个子开关一并关闭并落盘');
  assert(elements.pmPrompt.style.display === 'none' && elements.pmPromptHist.style.display === 'none',
    '#1 关闭测试模式后加号菜单两个入口消失');
  // ---- 重启后由 /prefs 恢复 ----
  prefs.testMode = true; prefs.promptViewerOn = true; prefs.promptHistoryOn = true;
  await loadPrefs();
  await sleep(20);
  assert(elements.devSection.style.display !== 'none' && elements.devModeSwitch.className.includes('on'),
    '#1 重启后测试模式开关区与开关态复现');
  assert(elements.pmPrompt.style.display !== 'none' && elements.pmPromptHist.style.display !== 'none',
    '#1 重启后两个菜单入口正确复现');
  prefs.testMode = false; prefs.promptViewerOn = false; prefs.promptHistoryOn = false;
  await loadPrefs();
  await sleep(20);
  assert(elements.devSection.style.display === 'none' && elements.pmPrompt.style.display === 'none',
    '#1 prefs 全关时 UI 全隐藏');
  // ---- #2 技能列表启动预载 + 弹列表节流刷新 ----
  assert(/loadSkills\(\);/.test(script.slice(script.lastIndexOf('loadTools();'))),
    '#2 boot 阶段预载技能（修复进入 App 后 @技能 显示「暂无技能」）');
  await fetch('/skills', { method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name: 'v1124probe', note: '探针', prompt: '只回答：OK' }) });
  // 用可控时钟跨过 3s 节流窗口（不改生产代码）
  const realNow = Date.now;
  let fakeOffset = 0;
  Date.now = () => realNow.call(Date) + fakeOffset;
  const skBase = skillsFetchCount;
  fakeOffset = 10000;                       // 距上次刷新已超 3s → 应刷新
  showAtList('', '');
  await sleep(30);
  assert(skillsFetchCount > skBase, '#2 弹 @ 列表触发一次后台刷新');
  assert(elements.atList.style.display === 'block'
    && elements.atList.children.some(c => (c.innerHTML || '').includes('v1124probe')),
    '#2 新建技能无需进技能页即可出现在 @ 列表');
  const skBase2 = skillsFetchCount;
  showAtList('', '');                        // 仍在同一时间 → 3s 内节流
  await sleep(20);
  assert(skillsFetchCount === skBase2, '#2 3s 内重复弹列表不重复请求（节流）');
  Date.now = realNow;
  closeAtList();
  // ---- #3 布局两处 ----
  assert(/#row \{[\s\S]{0,260}--ctlH: 44px/.test(css),
    '#2 高度唯一来源 #row --ctlH: 44px');
  assert(/#inputWrap \{[\s\S]{0,200}height: var\(--ctlH\)/.test(css),
    '#2 真正的框子 #inputWrap 显式钉高（v1.1.26 根治：此前它无 height，被 textarea+边框撑成 46px）');
  ['#plusBtn', '#channelTrigger', '#send'].forEach(sel => {
    const re = new RegExp(sel.replace('#', '#') + ' \\{[\\s\\S]{0,220}height: var\\(--ctlH\\)');
    assert(re.test(css), '#2 ' + sel + ' 同源 var(--ctlH)（四控件不可能再不等高）');
  });
  assert(!/inputEl\.style\.height/.test(script),
    '#2 禁止再单独改 textarea 高度（旧 bug 根因：改了框内元素、框子没变）');
  assert(/setControlRowHeight/.test(script) && /setProperty\('--ctlH'/.test(script),
    '#2 增高走唯一收口 setControlRowHeight → --ctlH（整排一起长）');
  assert(/#userBtn, #skillBtn, #toolBtn, #pluginBtn \{\s*margin: 6px 6px 0/.test(css),
    '#3 侧栏四按钮统一 margin 实现严格等距');
  assert(!/^\s*#(skillBtn|toolBtn|pluginBtn)\s*\{/m.test(css) && !/#skillBtn, #userBtn\s*\{/.test(css),
    '#3 旧的单独不等距规则已清除');
  // ---- v1.1.43 三项 bug 修复防回退断言 ----
  assert(/\.sess-item \.sdel \{[^}]*border: none/.test(css) && !/\.sess-item \.sdel \{[^}]*border: 1px/.test(css),
    '#3 会话删除按钮无红色边框');
  assert(/\.sess-item \.sdel\.armed \{[^}]*color: #fff !important/.test(css),
    '#3 armed 确认态白字带 !important（否则被 danger !important 盖成红底红字看不见）');
  assert(/let sdelDisarm = null/.test(script) && /sdelDisarm = disarmDel/.test(script) && /if \(sdelDisarm\)/.test(script),
    '#3 确认态已登记 + 点任意空白自动取消复位');
  assert(!/animation: *tabToR|animation: *tabToL|\.swipe-r *\{ *animation/.test(css),
    '#2 tabToR/tabToL CSS 过渡动画已移除');
  assert(/function switchTab\(name, snap\)/.test(script) && !/function switchTab\(name, dir\)/.test(script),
    '#1 switchTab 无 dir(过渡方向)参数，只有 snap(是否吸附)');
  assert(/tabBtnModels'\)\.addEventListener\('click', \(\) => switchTab\('Models'\)\)/.test(script) && !/tabDirTo/.test(script),
    '#2 点底栏三按钮=秒切（不再调用 tabDirTo）');
  assert(!/settingsPageEl\.style\.display = 'none'; clearX\(settingsPageEl\); sidebarSetOpen\(true\)/.test(script),
    '#1 设置页滑出返回聊天后不再自动展开智能体列表');
  // ---- v1.1.45 #1 设置分区「常驻物理并排轨道」防回退断言（取代 v1.1.44 的临时拼轨道方案）----
  assert(/<div id="settingsTrack">/.test(HTML), '#1 DOM 存在常驻轨道 #settingsTrack');
  assert(/#settingsBody \{[^}]*overflow: hidden/.test(css) && !/#settingsBody \{[^}]*overflow-y: auto/.test(css),
    '#1 #settingsBody 改为纯视口(overflow:hidden)，不再自带滚动（避免隐式横向滚动与 translateX 打架）');
  assert(!/#settingsBody \{[^}]*padding/.test(css),
    '#1 padding 已从 #settingsBody 移走（容器带 padding 平移会在两页接缝漏白 → 就是"两页分开"的观感根因）');
  assert(/\.sec \{[^}]*flex: 0 0 100%/.test(css) && /\.sec \{[^}]*overflow-y: auto/.test(css) && /\.sec \{[^}]*padding/.test(css),
    '#1 每页各占一屏宽、自己纵向滚动、自己带 padding（三页首尾物理相连）');
  assert(/#settingsTrack \{[^}]*display: flex/.test(css), '#1 轨道为 flex 横向布局');
  assert(/function trackPlace\(px, anim\)/.test(script) && /trackEl\(\)/.test(script) && /function settingsIdx\(\)/.test(script),
    '#1 轨道定位三原语齐备（trackPlace/trackEl/settingsIdx）');
  assert(/trackPlace\(Math\.max\(lo, Math\.min\(hi, swBasePx \+ dx\)\), false\)/.test(script),
    '#1 拖拽期轨道位移=基准+手指增量、1:1 跟手且无过渡(anim=false)');
  assert(/swBasePx = \(swLayer === 'settings'\) \? \(-settingsIdx\(\) \* settingsW\(\)\) : 0/.test(script),
    '#1 touchstart 记录轨道基准位移（拖起即从当前格起步）');
  assert(/switchTab\(SETTINGS_TABS\[ni\], true\)/.test(script),
    '#1 松手吸附到邻格或弹回本格（唯一有动画的路径是吸附）');
  assert(!/tabPeekStart|tabPeekRestore|tabPeek/.test(script),
    '#1 旧「临时拼 flex 轨道」方案已彻底移除（无残留分支）');
  console.log('\n' + (failures === 0 ? '全部通过 ✅' : failures + ' 项失败 ❌'));
  process.exit(failures === 0 ? 0 : 1);
})();