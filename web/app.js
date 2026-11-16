"use strict";

const $ = (selector) => document.querySelector(selector);
const native = window.AndroidPet;
const pet = new URLSearchParams(location.search).get("pet") === "1";
const names = { action: "动作", expression: "表情", costume: "衣装", voice: "语音" };
const view = { session: null, costume: null, model: null, app: null, generation: 0, tab: "action" };
const loops = new Set();
const timers = new Map();
let favoriteIds = new Set();
let showFavorites = false;
let linked = false;
let busy = false;
let audioContext, audioNode, analyser, samples;
let voiceGeneration = 0;
let mouth = 0;
let engines;
document.documentElement.classList.toggle("pet", pet);

const asset = (path, base = "/media/" + view.session.version + "/") =>
  new URL(path.split("/").map(encodeURIComponent).join("/"), location.origin + base).href;

async function data(url) {
  const response = await fetch(url);
  if (!response.ok) throw new Error("模型资源读取失败");
  return response.json();
}

function script(url) {
  return new Promise((resolve, reject) => {
    const tag = document.createElement("script");
    tag.src = url;
    tag.onload = resolve;
    tag.onerror = () => { tag.remove(); reject(new Error("Live2D 运行库加载失败")); };
    document.head.append(tag);
  });
}

async function loadEngine() {
  if (!engines) {
    engines = (async () => {
      await Promise.all([
        script("/runtime/live2d.min.js"), script("/runtime/live2dcubismcore.min.js"),
        script("/pixi.min.js"),
      ]);
      await script("/index.min.js");
      if (!window.PIXI?.live2d) throw new Error("Live2D 渲染器不可用");
    })().catch((error) => { engines = null; throw error; });
  }
  return engines;
}

function reportError(text) {
  $("#status").textContent = text;
  $("#status").classList.add("error");
  $("#status").hidden = false;
}

function importProgress(on) {
  busy = on;
  $("#replace").disabled = $("#choose").disabled = on;
  $("#toggle").disabled = on || !view.model;
  $("#status").classList.remove("error");
  $("#status").textContent = on ? "正在导入模型…" : "";
  $("#status").hidden = !on;
}

function petStatus(showing) {
  linked = showing;
  $("#toggle").textContent = showing ? "退出桌宠" : "显示桌宠";
  $("#toggle").disabled = busy || !view.model;
  if (pet) return;
  if (showing) {
    stopVoice();
    for (const timer of timers.values()) clearTimeout(timer);
  } else {
    for (const kind of loops) schedule(kind, 400);
  }
}

function title(text) {
  $("#name").textContent = text;
  document.title = text + " · BandoriLovePet";
}

async function refreshModel() {
  const session = await data("/session");
  if (!session) {
    $("#welcome").hidden = false;
    return;
  }
  try {
    cancelPlayback();
    await loadEngine();
    view.session = session;
    showFavorites = false;
    const catalog = session.catalog;
    favoriteIds = new Set(JSON.parse(localStorage.getItem("favorites:" + catalog.id) || "[]"));
    $("#welcome").hidden = true;
    $("#player").hidden = false;
    $("#replace").hidden = false;
    $("#controls").hidden = session.limited;
    $("#source").textContent = [catalog.game, catalog.group].filter(Boolean).join(" · ");
    $("#pet-menu-button").hidden = !pet;
    document.querySelectorAll("#pet-menu [data-loop], #pet-reset").forEach((button) => {
      button.hidden = session.limited;
    });
    title(catalog.name);
    const selected = catalog.costumes.find((item) => item.id === native.costume(catalog.id || ""));
    setTab("action");
    await selectCostume(selected || catalog.costumes[0]);
    importProgress(false);
    petStatus(native.petShowing());
  } catch (error) {
    importProgress(false);
    reportError(error.message);
    console.error(error);
  }
}

function preview(raw, costume, file) {
  if (view.session.limited) {
    const refs = raw.FileReferences || raw;
    const prefix = file.substring(0, file.lastIndexOf("/") + 1);
    const path = (key) => { if (refs[key]) refs[key] = new URL(refs[key].split("/").map(encodeURIComponent).join("/"), prefix).href; };
    for (const key of ["Moc", "model", "Physics", "physics", "Pose", "pose"]) path(key);
    const textureKey = raw.FileReferences ? "Textures" : "textures";
    refs[textureKey] = refs[textureKey].map((item) => new URL(item.split("/").map(encodeURIComponent).join("/"), prefix).href);
    return raw;
  }
  const modern = Boolean(raw.FileReferences);
  const refs = raw.FileReferences || raw;
  const key = (upper, lower) => modern ? upper : lower;
  const groups = refs[key("Motions", "motions")] || {};
  const startup = groups[key("Start", "start")]?.[0];
  const textureKey = key("Textures", "textures");
  const mocKey = key("Moc", "model");
  const fileKey = key("File", "file");
  const textures = refs[textureKey].slice();
  for (const [, index, replacement] of (startup?.[key("PostCommand", "post_command")] || "")
    .matchAll(/replace_tex (\d+) ([^;\s]+)/g)) {
    if (textures[index] !== undefined) textures[index] = replacement;
  }
  const motions = {};
  const sourceFades = view.session.catalog.motionFadePolicy === "source";
  const fade = (item) => sourceFades ? {
    [key("FadeInTime", "fade_in")]: item.fadeIn / (modern ? 1000 : 1),
    [key("FadeOutTime", "fade_out")]: item.fadeOut / (modern ? 1000 : 1),
  } : {};
  for (const item of costume.actions || []) {
    motions["page_" + item.id] = [{ [fileKey]: asset(item.file), ...fade(item) }];
  }
  const idle = key("Idle", "idle");
  if (groups[idle]?.[0]?.[fileKey]) motions[idle] = [{ [fileKey]: asset(groups[idle][0][fileKey]) }];
  const expressions = (costume.expressions || []).filter((item) => item.file).map((item) =>
    ({ [key("Name", "name")]: String(item.id), [fileKey]: asset(item.file) }));
  const normalized = {
    [mocKey]: asset(refs[mocKey]),
    [textureKey]: textures.map((path) => asset(path)),
    [key("Motions", "motions")]: motions,
    [key("Expressions", "expressions")]: expressions,
  };
  for (const field of [key("Physics", "physics"), key("Pose", "pose")]) {
    if (refs[field]) normalized[field] = asset(refs[field]);
  }
  return modern ? { Version: 3, FileReferences: normalized } : { version: "Live2DViewerEX", ...normalized };
}

function makeRenderer() {
  const stage = $("#canvas");
  const canvas = document.createElement("canvas");
  const attributes = { alpha: true, stencil: true, antialias: true, preserveDrawingBuffer: true };
  const context = canvas.getContext("webgl2", attributes) || canvas.getContext("webgl", attributes);
  if (!context) throw new Error("此设备的 Android WebView 无法创建 WebGL 画布");
  // Some Android GPU configurations support WebGL 2 while blocking WebGL 1.
  const renderer = new PIXI.Renderer({
    view: canvas, context,
    width: stage.clientWidth, height: stage.clientHeight,
    backgroundAlpha: 0, resolution: Math.min(devicePixelRatio, 1.5),
    autoDensity: true, antialias: true, preserveDrawingBuffer: true,
  });
  const contents = new PIXI.Container();
  PIXI.Ticker.shared.add(() => renderer.render(contents), undefined, PIXI.UPDATE_PRIORITY.LOW);
  stage.append(canvas);
  const resize = new ResizeObserver(() => {
    if (!stage.clientWidth || !stage.clientHeight) return;
    renderer.resize(stage.clientWidth, stage.clientHeight);
    placeModel();
  });
  resize.observe(stage);
  return { renderer, stage: contents, screen: renderer.screen };
}

function placeModel() {
  if (!view.model) return;
  const { width, height } = view.app.screen;
  const bounds = view.bounds;
  if (!bounds?.width || !bounds.height) return;
  view.model.scale.set(Math.min(width * .91 / bounds.width, height * .96 / bounds.height));
  view.model.anchor.set(.5, 1);
  view.model.position.set(width * .5, height * .985);
}

function motionFades(model, metadata) {
  const manager = model.internalModel.motionManager;
  const sourceFades = view.session.catalog.motionFadePolicy === "source";
  manager.on("motionLoaded", (group, index, motion) => {
    const definition = (metadata.FileReferences?.Motions || metadata.motions || {})[group]?.[index];
    const fadeIn = definition?.FadeInTime ?? definition?.fade_in;
    const fadeOut = definition?.FadeOutTime ?? definition?.fade_out;
    const multiplier = metadata.FileReferences ? 1 : 1000;
    motion.setFadeInTime?.(sourceFades ? (fadeIn ?? 1) : 0);
    motion.setFadeOutTime?.(sourceFades ? (fadeOut ?? 1) : 0);
    motion.setFadeIn?.(sourceFades ? (fadeIn ?? 1) * multiplier : 0);
    motion.setFadeOut?.(sourceFades ? (fadeOut ?? 1) * multiplier : 0);
  });
}

async function selectCostume(costume) {
  const generation = ++view.generation;
  $("#loading").hidden = false;
  try {
    const file = asset(costume.file);
    const raw = await data(file);
    const metadata = preview(raw, costume, file);
    metadata.url = file;
    if (!view.app) view.app = makeRenderer();
    const next = await PIXI.live2d.Live2DModel.from(metadata, { autoInteract: false, motionPreload: "NONE" });
    if (generation !== view.generation) {
      next.destroy({ children: true });
      return;
    }
    const outgoing = view.app.stage.removeChildren();
    view.model = next;
    view.costume = costume;
    view.bounds = next.getLocalBounds();
    const controllers = raw[raw.FileReferences ? "Controllers" : "controllers"];
    const sync = controllers?.[raw.FileReferences ? "LipSync" : "lip_sync"];
    const targets = (sync?.Items || sync?.items || []).map((item) => item.Id || item.id);
    next.internalModel.on("beforeModelUpdate", () => moveMouth(next, targets));
    motionFades(next, metadata);
    view.app.stage.addChild(next);
    placeModel();
    view.app.renderer.render(view.app.stage);
    for (const model of outgoing) model.destroy({ children: true });
    renderItems();
  } finally {
    if (generation === view.generation) $("#loading").hidden = true;
  }
}

function items(kind) {
  const catalog = view.session?.catalog;
  if (!catalog) return [];
  if (kind === "costume") return catalog.costumes;
  if (kind === "voice") return catalog.voices || [];
  if (kind === "expression") return view.costume?.expressions || [];
  return view.costume?.actions || [];
}

function button(text, className, click) {
  const node = document.createElement("button");
  node.className = className;
  node.textContent = text;
  node.addEventListener("click", click);
  return node;
}

function renderItems() {
  if (!view.session || view.session.limited || pet) return;
  const query = $("#filter").value.trim().toLocaleLowerCase();
  const entries = items(view.tab).filter((item) =>
    (!showFavorites || favoriteIds.has(item.flag)) &&
    (item.name || "").toLocaleLowerCase().includes(query));
  const list = document.createDocumentFragment();
  for (const item of entries) {
    const row = document.createElement("li");
    const play = button(item.name, "item", () => send(view.tab, item.id));
    if (view.tab === "costume") play.setAttribute("aria-pressed", String(view.costume?.id === item.id));
    row.append(play);
    if (item.flag) {
      const saved = favoriteIds.has(item.flag);
      const heart = button(saved ? "♥" : "♡", "heart", () => {
        if (saved) favoriteIds.delete(item.flag);
        else favoriteIds.add(item.flag);
        localStorage.setItem("favorites:" + view.session.catalog.id, JSON.stringify([...favoriteIds]));
        renderItems();
      });
      heart.setAttribute("aria-label", saved ? "取消收藏" : "收藏");
      row.append(heart);
    }
    list.append(row);
  }
  const container = $("#items");
  container.textContent = "";
  container.append(list);
  $("#empty").hidden = Boolean(entries.length);
  updateLoops();
}

function setTab(tab) {
  view.tab = tab;
  $("#filter").value = "";
  $("#filter").placeholder = "搜索" + names[tab];
  $("#loop").disabled = tab === "expression";
  document.querySelectorAll("[data-tab]").forEach((node) =>
    node.setAttribute("aria-pressed", String(node.dataset.tab === tab)));
  $("#favorites").setAttribute("aria-pressed", String(showFavorites));
  $("#favorites").textContent = showFavorites ? "♥" : "♡";
  renderItems();
}

function updateLoops() {
  $("#loop").setAttribute("aria-pressed", String(loops.has(view.tab)));
  document.querySelectorAll("[data-loop]").forEach((node) =>
    node.setAttribute("aria-pressed", String(loops.has(node.dataset.loop))));
}

function send(type, id = 0, kind = "") {
  if (!view.session || view.session.limited) return;
  native.command(JSON.stringify({ type, id, kind, role: view.session.catalog.id }));
}

function ownsPlayback() { return pet || !linked; }

function schedule(kind, ms) {
  clearTimeout(timers.get(kind));
  if (ownsPlayback() && loops.has(kind)) timers.set(kind, setTimeout(() => playRandom(kind), ms));
}

function playRandom(kind, filter = () => true) {
  const list = items(kind).filter(filter);
  if (!list.length) return;
  const choices = kind === "costume" && list.length > 1 ? list.filter((item) => item.id !== view.costume?.id) : list;
  send(kind, choices[Math.floor(Math.random() * choices.length)].id);
}

function stopVoice() {
  ++voiceGeneration;
  if (audioNode) {
    try { audioNode.stop(); } catch {}
    audioNode = null;
  }
  $("#caption").hidden = true;
  mouth = 0;
}

async function playVoice(item) {
  stopVoice();
  const generation = voiceGeneration;
  if (!audioContext) audioContext = new AudioContext();
  if (!analyser) {
    analyser = audioContext.createAnalyser();
    analyser.fftSize = 256;
    samples = new Uint8Array(analyser.fftSize);
    analyser.connect(audioContext.destination);
  }
  const url = asset(item.file);
  await audioContext.resume();
  const response = await fetch(url);
  if (!response.ok) throw new Error("语音资源读取失败");
  const buffer = await audioContext.decodeAudioData(await response.arrayBuffer());
  if (generation !== voiceGeneration) return;
  const node = audioContext.createBufferSource();
  audioNode = node;
  node.buffer = buffer;
  node.connect(analyser);
  $("#caption").textContent = item.caption || item.name;
  $("#caption").hidden = false;
  node.onended = () => {
    if (audioNode !== node) return;
    stopVoice();
    schedule("voice", 1300);
  };
  node.start();
}

function moveMouth(model, ids) {
  if (!audioNode || !ids.length) return;
  analyser.getByteTimeDomainData(samples);
  const rms = Math.sqrt(samples.reduce((sum, value) => sum + ((value - 128) / 128) ** 2, 0) / samples.length);
  mouth += (Math.min(1, rms * 4.2) - mouth) * .44;
  const core = model.internalModel.coreModel;
  for (const id of ids) {
    if (core.setParameterValueById) core.setParameterValueById(id, mouth);
    else core.setParamFloat?.(id, mouth);
  }
}

function cancelPlayback() {
  for (const timer of timers.values()) clearTimeout(timer);
  timers.clear();
  loops.clear();
  stopVoice();
  const manager = view.model?.internalModel.motionManager;
  manager?.stopAllMotions();
  manager?.expressionManager?.resetExpression();
  updateLoops();
}

async function receiveCommand(command, sound) {
  if (!view.session || view.session.catalog.id !== command.role) return;
  try {
    const { type, id, kind } = command;
    if (type === "reset") return cancelPlayback();
    if (type === "loop" && ["action", "voice", "costume"].includes(kind)) {
      if (id) { loops.add(kind); schedule(kind, 0); }
      else { loops.delete(kind); clearTimeout(timers.get(kind)); }
      return updateLoops();
    }
    const item = items(type).find((entry) => entry.id === id);
    if (!item) return;
    if (type === "costume") {
      if (view.costume?.id !== id) await selectCostume(item);
      schedule(type, 3800);
    } else if (type === "action") {
      await view.model?.motion("page_" + id, 0, 3);
      schedule(type, Math.max(600, (item.duration || 1) * 1000 + 600));
    } else if (type === "expression") {
      if (item.file) await view.model?.expression(String(id));
      else view.model?.internalModel.motionManager.expressionManager?.resetExpression();
    } else if (type === "voice" && sound) {
      await playVoice(item);
    }
  } catch (error) {
    reportError(error.message);
    console.error(error);
  }
}

function showPetMenu(show = $("#pet-menu").hidden) {
  if (pet) $("#pet-menu").hidden = !show;
}

function avatarClick(event) {
  if (!view.model || view.session.limited || !$("#loading").hidden) return;
  const rect = $("#canvas").getBoundingClientRect();
  const point = view.model.toModelPosition(new PIXI.Point(event.clientX - rect.left, event.clientY - rect.top));
  const internal = view.model.internalModel;
  if (view.costume.cubism3) {
    point.x = (point.x - internal.originalWidth / 2) / internal.pixelsPerUnit;
    point.y = (point.y - internal.originalHeight / 2) / internal.pixelsPerUnit;
  }
  const regions = view.costume.hitRegions || {};
  const contains = (box) => box && point.x >= box[0] && point.x <= box[2] && point.y >= box[1] && point.y <= box[3];
  const region = ["menu", "expression", "voice", "left", "right", "body", "wardrobe"].find((name) => contains(regions[name]));
  if (region === "menu") showPetMenu(true);
  else if (region === "wardrobe") playRandom("costume");
  else if (region === "voice" || region === "expression") playRandom(region);
  else if (region) playRandom("action", (item) => item.category === region);
}

for (const selector of ["#choose", "#replace"]) $(selector).onclick = () => native.pickModel();
for (const selector of ["#toggle", "#exit-pet"]) $(selector).onclick = () => native.togglePet();
for (const selector of ["#reset", "#pet-reset"]) $(selector).onclick = () => send("reset");
$("#open-panel").onclick = () => { showPetMenu(false); native.openPanel(); };
$("#pet-menu-button").onclick = () => showPetMenu();
$("#canvas").onclick = avatarClick;
$("#filter").oninput = renderItems;
$("#favorites").onclick = () => { showFavorites = !showFavorites; setTab(view.tab); };
$("#loop").onclick = () => send("loop", Number(!loops.has(view.tab)), view.tab);
document.querySelectorAll("[data-tab]").forEach((node) => { node.onclick = () => setTab(node.dataset.tab); });
document.querySelectorAll("[data-loop]").forEach((node) => {
  node.onclick = () => send("loop", Number(!loops.has(node.dataset.loop)), node.dataset.loop);
});

refreshModel().catch((error) => reportError(error.message));
