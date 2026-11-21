"use strict";

const $ = (selector) => document.querySelector(selector);
const native = window.AndroidPet;
const pet = new URLSearchParams(location.search).get("pet") === "1";
const wallpaper = new URLSearchParams(location.search).get("wallpaper") === "1";
let wallpaperVisible = true;
let wallpaperFrameTimer;
const names = { action: "动作", expression: "表情", costume: "衣装", voice: "语音" };
const view = { session: null, costume: null, model: null, app: null, generation: 0, tab: "action" };
const caption = $("#caption");
let captionCardsEnabled = native.captionCardsEnabled();
let touchMenuEnabled = native.touchMenuEnabled();
let thirdPartyMenuEnabled = native.thirdPartyMenuEnabled();
const captionStage = $("#stage");
const petMenu = $("#pet-menu");
const petMenuButton = $("#pet-menu-button");
const settings = $("#settings");
const appPrompt = $("#app-prompt");
let promptId, promptCallback, noticeTimer;
let panelLoadSequence = 0;
let wallpaperPanelReloading = false;
let desktopCrop;
let desktopModelScale = 1, desktopRasterScale = 1;
let desktopRotation = 0;
let desktopMoving = false;
const captionDrag = enableCaptionDrag(caption, desktopControlArea, (rect) => {
  if (pet) reportPetBounds("caption", rect);
});
const loops = new Set();
const loopIntervals = { costume: 35000, voice: 15000, expression: 8000, action: 1600 };
const loopDeadlines = new Map();
const loopSource = Math.random().toString(36).slice(2);
let loopTimer, loopInFlight, costumeChange;
let loopSequence = 0, playbackGeneration = 0;
let panelPlaybackActive = false, wallpaperPlaybackActive = false;
let favoriteIds = new Set();
let showFavorites = false;
let overlayVisible = false;
let controlsCollapsed = localStorage.getItem("controlsCollapsed") === "true";
let controlsTransition;
let busy = false;
let wallpaperApplied = false;
let wallpaperChanging = false;
let audioContext, audioNode, analyser, samples;
let voiceEndsAt = 0;
let voiceGeneration = 0;
let mouth = 0;
let engines;
const performanceCursor = new Map();
const previewControlTimers = new Map();
const reportedControls = new Map();
let previewPress;
document.documentElement.classList.toggle("pet", pet);
document.documentElement.classList.toggle("app-panel", !pet && !wallpaper);
window.setPanelInsets = (...insets) => {
  for (const [index, edge] of ["top", "right", "bottom", "left"].entries()) {
    document.documentElement.style.setProperty("--panel-" + edge, insets[index] + "px");
  }
};
document.documentElement.classList.toggle("wallpaper", wallpaper);
if (pet) {
  setDesktopScale(native.desktopScale());
  desktopRotation = native.desktopRotation();
}

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

function loadEngine(legacy) {
  if (!engines || (legacy && !engines.legacy)) {
    const previous = engines?.ready;
    const engine = { legacy };
    engines = engine;
    engine.ready = (async () => {
      await previous;
      const core = [];
      if (!window.PIXI) core.push(script("/pixi.min.js"));
      if (!window.Live2DCubismCore) core.push(script("/runtime/live2dcubismcore.min.js"));
      if (legacy && !window.Live2D) core.push(script("/runtime/live2d.min.js"));
      await Promise.all(core);
      await script(legacy ? "/index.min.js" : "/cubism4.min.js");
      if (!window.PIXI?.live2d) throw new Error("Live2D 渲染器不可用");
    })().catch((error) => { if (engines === engine) engines = null; throw error; });
  }
  return engines.ready;
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
  updateWallpaperButton();
  $("#status").classList.remove("error");
  $("#status").textContent = on ? "正在导入模型…" : "";
  $("#status").hidden = !on;
}

function petStatus(showing, visible, homeOnly) {
  const button = $("#toggle");
  button.setAttribute("aria-pressed", String(showing));
  button.title = showing ? "退出桌宠" : "显示桌宠";
  button.disabled = busy || !view.model;
  updateWallpaperButton();
  $("#mode-top").checked = !homeOnly;
  $("#mode-home").checked = homeOnly;
  if (homeOnly) $("#usage-access").hidden = true;
  if (overlayVisible === visible) return;
  overlayVisible = visible;
  if (pet && !visible && !petMenu.hidden) showPetMenu(false);
  updateLoopOwner();
}

function updateWallpaperButton() {
  const button = $("#apply-wallpaper");
  button.disabled = busy || wallpaperChanging || (!view.model && !wallpaperApplied);
  button.setAttribute("aria-pressed", String(wallpaperApplied));
  button.setAttribute("aria-busy", String(wallpaperChanging));
  button.title = wallpaperChanging ? "正在设置壁纸" : wallpaperApplied ? "退出壁纸" : "显示壁纸";
  $("#welcome-wallpaper").disabled = wallpaperChanging;
  $("#scene-apply").disabled = busy || wallpaperChanging || !view.model;
  $("#scene-apply").setAttribute("aria-busy", String(wallpaperChanging));
}
window.setHomeWallpaperState = (applied, changing) => {
  wallpaperApplied = applied;
  wallpaperChanging = changing;
  updateWallpaperButton();
};

window.setPermissionStatus = (overlay, usage, wallpaperMode) => {
  for (const [id, granted] of [["overlay", overlay], ["usage", usage]]) {
    const status = $("#perm-" + id);
    status.dataset.status = granted ? "allowed" : "off";
    status.textContent = granted ? "已开启" : "未开启";
  }
  const wallpaperState = {
    enabled: "已开启", disabled: "未开启", system: "系统中查看", unsupported: "不支持",
  };
  $("#perm-wallpaper").textContent = wallpaperState[wallpaperMode] || wallpaperState.system;
  $("#perm-wallpaper").dataset.status = wallpaperMode === "enabled" ? "allowed" : "off";
};

function updateControlsVisibility() {
  const limited = view.session?.limited;
  $("#pure-preview-row").hidden = !view.session;
  $("#pure-preview-toggle").checked = controlsCollapsed;
  $("#favorite-interactions-row").hidden = limited;
  $("#open-performance").hidden = !view.session || limited;
  $("#controls").hidden = limited || controlsCollapsed;
  document.documentElement.classList.toggle("preview-only", !pet && (limited || controlsCollapsed));
  const immersive = !pet && !wallpaper && !!view.session
    && (controlsCollapsed || document.documentElement.classList.contains("scene-edit"));
  if (document.documentElement.classList.contains("immersive-preview") !== immersive) {
    document.documentElement.classList.toggle("immersive-preview", immersive);
    native.immersivePreview(immersive);
    window.sceneLayers?.render();
  }
  revealPreviewControls();
  if (!pet && !wallpaper) window.sceneLayers?.updateTiltPreview();
}

function setControlsCollapsed(collapsed, animate = false) {
  if (controlsCollapsed === collapsed || controlsTransition) return;
  const apply = () => {
    controlsCollapsed = collapsed;
    localStorage.setItem("controlsCollapsed", String(collapsed));
    updateControlsVisibility();
    view.app?.syncCanvas();
  };
  if (!animate || matchMedia("(prefers-reduced-motion: reduce)").matches) {
    apply();
    return;
  }
  if (!document.startViewTransition) {
    apply();
    $("#stage").animate([{opacity: .65}, {opacity: 1}], {duration: 160, easing: "ease-out"});
    return;
  }
  const root = document.documentElement;
  root.classList.add("preview-transition");
  controlsTransition = document.startViewTransition(apply);
  controlsTransition.ready.catch(() => {});
  controlsTransition.finished.catch(() => {}).finally(() => {
    controlsTransition = null;
    root.classList.remove("preview-transition");
  });
}

function revealPreviewControls(edge) {
  const root = document.documentElement;
  for (const side of edge ? [edge] : ["top", "bottom"]) {
    const hidden = "preview-" + side + "-hidden";
    clearTimeout(previewControlTimers.get(side));
    root.classList.remove(hidden);
    if (root.classList.contains("immersive-preview")) {
      previewControlTimers.set(side, setTimeout(() => root.classList.add(hidden), 2600));
    }
  }
}

function title(text) {
  $("#name").textContent = text;
  document.title = text + " · LovePet";
}

function beginPanelLoad() {
  const ticket = ++panelLoadSequence;
  if (!pet && !wallpaper) {
    native.panelLoading(ticket);
  }
  return ticket;
}

function finishPanelLoad(ticket, ready) {
  if (pet || wallpaper) return;
  requestAnimationFrame(() => {
    if (ticket !== panelLoadSequence) return;
    native.panelFrameReady(ticket, ready);
  });
}

async function refreshModel() {
  const ticket = beginPanelLoad();
  let ready = false;
  try {
    const session = await data("/session");
    if (!session) {
      cancelPlayback();
      ++view.generation;
      for (const model of view.app?.stage.removeChildren() || []) model.destroy({ children: true });
      view.session = view.model = view.costume = null;
      native.reportModelRendering?.("empty");
      $("#player").hidden = $("#replace").hidden = true;
      $("#welcome").hidden = false;
      updateControlsVisibility();
      importProgress(false);
      ready = true;
      return true;
    }
    cancelPlayback();
    view.session = session;
    view.viewer = session.viewerPackage ? await window.viewerInteraction.load(asset("plain/viewer.json")) : null;
    view.viewerOutfit = view.viewer?.initial;
    view.viewerCostume = null;
    showFavorites = false;
    const catalog = session.catalog;
    favoriteIds = readFavoriteIds();
    $("#welcome").hidden = true;
    $("#player").hidden = false;
    $("#replace").hidden = false;
    updateControlsVisibility();
    $("#source").textContent = [catalog.game, catalog.group].filter(Boolean).join(" · ");
    petMenuButton.hidden = petMenu.hidden = true;
    if (pet) {
      reportPetBounds("menu", null);
      reportPetBounds("menuButton", null);
    }
    document.querySelectorAll("#pet-menu [data-loop], #pet-reset").forEach((button) => {
      button.hidden = session.limited;
    });
    title(catalog.name);
    const savedCostume = native.costume(catalog.id || "");
    const selected = catalog.costumes.find((item) => item.id === savedCostume || item.aliasIds?.includes(savedCostume));
    setTab("action");
    ready = await selectCostume(selected || catalog.costumes[0]);
    window.modelPreviewChanged?.();
    updateThirdPartyMenuButton();
    updateTouchMenuSetting();
    importProgress(false);
    petStatus(native.petShowing(), native.petVisible(), native.homeOnly());
  } catch (error) {
    importProgress(false);
    native.reportModelRendering?.("error");
    reportError(error.message);
    console.error(error);
  } finally {
    finishPanelLoad(ticket, ready);
  }
  return ready;
}

window.reloadWallpaperPanel = async (request) => {
  wallpaperPanelReloading = true;
  armLoops();
  let ready = false;
  try {
    await costumeChange?.catch(() => {});
    ready = view.costume ? await selectCostume(view.costume) : await refreshModel();
  } catch (error) {
    reportError(error.message);
    console.error(error);
  }
  requestAnimationFrame(() => requestAnimationFrame(() => native.wallpaperPanelReady(request, ready)));
};

window.finishWallpaperPanelReload = () => {
  wallpaperPanelReloading = false;
  armLoops();
};

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
  const context = canvas.getContext("webgl2", attributes)
    || canvas.getContext("webgl", attributes)
    || canvas.getContext("webgl", { ...attributes, antialias: false });
  if (!context) throw new Error("此设备的 Android WebView 无法创建 WebGL 画布");
  const maxSide = Math.min(context.getParameter(context.MAX_RENDERBUFFER_SIZE), context.getParameter(context.MAX_TEXTURE_SIZE));
  const [maxWidth, maxHeight] = Array.from(context.getParameter(context.MAX_VIEWPORT_DIMS), value => Math.min(value, maxSide));
  const anisotropy = context.getExtension("EXT_texture_filter_anisotropic");
  const anisotropyLevel = anisotropy ? Math.min(4, context.getParameter(anisotropy.MAX_TEXTURE_MAX_ANISOTROPY_EXT)) : 0;
  const isEnlarged = (width) => pet ? width * (devicePixelRatio || 1) * desktopRasterScale >= 640
    : (view.sceneCharacterTransform?.scale ?? 1) >= 1.25;
  function resolutionFor(width, height, enlarged) {
    const nativeResolution = (devicePixelRatio || 1) * desktopRasterScale;
    // Bound the extra framebuffer area on high-resolution screens.
    const target = enlarged ? Math.min(nativeResolution * 1.25,
      Math.sqrt(nativeResolution ** 2 + 1_200_000 / (width * height))) : nativeResolution;
    return Math.min(target, maxWidth / width, maxHeight / height);
  }
  // Some Android GPU configurations support WebGL 2 while blocking WebGL 1.
  const renderer = new PIXI.Renderer({
    view: canvas, context,
    width: stage.clientWidth, height: stage.clientHeight,
    backgroundAlpha: 0, resolution: resolutionFor(stage.clientWidth, stage.clientHeight, isEnlarged(stage.clientWidth)),
    autoDensity: !pet, antialias: context.getContextAttributes().antialias, preserveDrawingBuffer: true,
  });
  const contents = new PIXI.Container();
  stage.append(canvas);
  let pinching = false;
  let resizeTimer;
  let qualityTimer;
  let enlarged = isEnlarged(stage.clientWidth);
  const readAlpha = makeAlphaSampler(canvas);
  const sampleTouchMask = pet ? makeTouchSampler(readAlpha) : null;
  let refreshTouchMask = false;
  let desktopRotationPending = false;
  const ticker = PIXI.Ticker.shared;
  ticker.add(() => {
    if ((wallpaper && !wallpaperVisible) || (!wallpaper && document.hidden)) return;
    // Dragging and pinching composite the previous frame; a rotation redraws only when its angle changes.
    if (desktopRotationPending) {
      desktopRotationPending = false;
      placeModel();
    } else if (pet && desktopMoving) return;
    renderer.render(contents);
    if (sampleTouchMask && overlayVisible && view.model && !desktopMoving) {
      sampleTouchMask(view.model, refreshTouchMask);
      refreshTouchMask = false;
    }
  }, undefined, PIXI.UPDATE_PRIORITY.LOW);
  function syncMask(model) {
    if (!model?.internalModel.coreModel.isUsingMasking?.()) return false;
    const mask = model.internalModel.renderer;
    // The default 256-pixel mask leaks lip textures at high zoom.
    const size = Math.min(enlarged && maxSide >= 4096 ? 1536 : 1024, maxSide);
    if (mask.getClippingMaskBufferSize() === size) return false;
    const initialized = Boolean(mask.gl);
    mask.setClippingMaskBufferSize(size);
    if (initialized) mask.startUp(context);
    return true;
  }
  function prepareModel(model) {
    const mipmap = renderer.context.webGLVersion > 1 ? PIXI.MIPMAP_MODES.ON : PIXI.MIPMAP_MODES.POW2;
    for (const { baseTexture } of model.textures) {
      baseTexture.setStyle(PIXI.SCALE_MODES.LINEAR, mipmap);
      if (baseTexture.anisotropicLevel !== anisotropyLevel) {
        baseTexture.anisotropicLevel = anisotropyLevel;
        baseTexture.dirtyStyleId++;
      }
    }
    syncMask(model);
  }
  function syncCanvas() {
    const { clientWidth: width, clientHeight: height } = stage;
    if (!width || !height) return;
    if (!pinching) enlarged = isEnlarged(width);
    const resolution = resolutionFor(width, height, enlarged);
    const layoutChanged = Math.abs(renderer.screen.width - width) >= 1 ||
        Math.abs(renderer.screen.height - height) >= 1;
    const resized = renderer.resolution !== resolution || layoutChanged;
    const maskChanged = syncMask(view.model);
    if (resized) {
      renderer.resolution = resolution;
      renderer.resize(width, height);
      if (layoutChanged) placeModel();
    }
    // Present a complete frame in the same turn as the drawing buffer resize.
    if (resized || maskChanged) renderer.render(contents);
  }
  function scheduleQualitySync() {
    clearTimeout(qualityTimer);
    if (!pinching && isEnlarged(stage.clientWidth) !== enlarged) qualityTimer = setTimeout(syncCanvas, 140);
  }
  function setScenePinching(active) {
    pinching = active;
    scheduleQualitySync();
  }
  function scheduleCanvasSync() {
    if (pet) {
      clearTimeout(resizeTimer);
      // CSS scales the existing transparent frame while Android tracks two fingers.
      if (!desktopMoving) resizeTimer = setTimeout(syncCanvas, 80);
    } else syncCanvas();
  }
  if (pet) window.setDesktopGesture = (moving, zooming) => {
    desktopMoving = moving;
    pinching = zooming;
    if (!moving) {
      refreshTouchMask = true;
      desktopRasterScale = desktopModelScale;
      captionDrag.layout();
      updateThirdPartyMenuButton();
    }
    scheduleCanvasSync();
  };
  const resize = new ResizeObserver(() => {
    scheduleCanvasSync();
    positionPetMenu();
    if (pet) {
      if (!petMenuButton.hidden) reportPetBounds("menuButton", petMenuButton.getBoundingClientRect());
    }
  });
  resize.observe(stage);
  const pause = () => { ticker.autoStart = false; ticker.stop(); };
  if (wallpaper ? !wallpaperVisible : !pet && !panelPlaybackActive) pause();
  return { renderer, stage: contents, screen: renderer.screen, readAlpha, syncCanvas,
    prepareModel, scheduleQualitySync, setScenePinching, pause, resume: () => ticker.start(),
    requestDesktopRotation: () => { desktopRotationPending = true; } };
}

const touchColumns = 40, touchRows = 58;
function makeAlphaSampler(canvas) {
  const sample = document.createElement("canvas");
  sample.width = touchColumns;
  sample.height = touchRows;
  const pixels = sample.getContext("2d", { willReadFrequently: true });
  return (point) => {
    if (point) {
      const x = Math.floor(point.x * canvas.width);
      const y = Math.floor(point.y * canvas.height);
      pixels.clearRect(0, 0, 1, 1);
      pixels.drawImage(canvas, x, y, 1, 1, 0, 0, 1, 1);
      return pixels.getImageData(0, 0, 1, 1).data[3];
    }
    pixels.clearRect(0, 0, touchColumns, touchRows);
    pixels.drawImage(canvas, 0, 0, touchColumns, touchRows);
    return pixels.getImageData(0, 0, touchColumns, touchRows).data;
  };
}

function hasVisibleModelPixels() {
  return view.app.readAlpha().some((alpha, index) => index % 4 === 3 && alpha >= 28);
}

function makeTouchSampler(readAlpha) {
  let lastSample = 0, lastMask = "", lastModel;
  return (model, refresh) => {
    const now = performance.now();
    if (!refresh && now - lastSample < 145) return;
    lastSample = now;
    const data = readAlpha();
    let mask = "";
    for (let i = 3; i < data.length; i += 4) mask += data[i] >= 28 ? "1" : "0";
    // A blank frame during a surface update should not erase the last usable touch outline.
    if (!mask.includes("1")) return;
    const changedModel = model !== lastModel;
    if (refresh || mask !== lastMask || changedModel) {
      native.modelTouchMask(mask, changedModel, desktopRotation);
      lastMask = mask;
      lastModel = model;
    }
  };
}

function fitVisibleModel(width, height) {
  const { model, app } = view;
  const previous = view.visibleFit;
  if (previous?.model === model && Math.abs(previous.aspect - width / height) < .006) {
    model.scale.set(previous.scale * width);
    model.position.set(previous.x * width, previous.y * height);
    return { top: previous.top * height, height: previous.height * height };
  }
  app.renderer.render(app.stage);
  const data = app.readAlpha();
  let left = touchColumns, right = 0, top = touchRows, bottom = 0;
  for (let row = 0; row < touchRows; row++) {
    for (let col = 0; col < touchColumns; col++) {
      if (data[(row * touchColumns + col) * 4 + 3] < 28) continue;
      left = Math.min(left, col);
      right = Math.max(right, col + 1);
      top = Math.min(top, row);
      bottom = Math.max(bottom, row + 1);
    }
  }
  if (right - left < 2 || bottom - top < 2) return;
  const centerX = (left + right) * width / (2 * touchColumns);
  const centerY = (top + bottom) * height / (2 * touchRows);
  const zoom = Math.min(.86 * touchColumns / (right - left), .86 * touchRows / (bottom - top), 2.4);
  model.scale.set(model.scale.x * zoom);
  model.position.set(width / 2 + (model.position.x - centerX) * zoom,
    height / 2 + (model.position.y - centerY) * zoom);
  const fittedHeight = (bottom - top) * height * zoom / touchRows;
  const fittedTop = (height - fittedHeight) / 2;
  view.visibleFit = {
    model, aspect: width / height, scale: model.scale.x / width,
    x: model.position.x / width, y: model.position.y / height, top: fittedTop / height, height: fittedHeight / height,
    width: (right - left) / touchColumns * zoom,
  };
  return { top: fittedTop, height: fittedHeight };
}

function placeModel() {
  if (!view.model) return;
  const { width, height } = view.app.screen;
  const bounds = view.bounds;
  if (!bounds?.width || !bounds.height) return;
  const fitVisible = pet || wallpaper || document.documentElement.classList.contains("immersive-preview");
  const scale = Math.min(width * (fitVisible ? .72 : .91) / bounds.width,
    height * (fitVisible ? .66 : .96) / bounds.height);
  const x = width * .5;
  const y = height * (fitVisible ? .5 : .985);
  view.model.scale.set(scale);
  view.model.angle = 0;
  view.model.anchor.set(.5, fitVisible ? .5 : 1);
  view.model.position.set(x, y);
  if (fitVisible) fitVisibleModel(width, height);
  if (view.sceneCharacterTransform || desktopRotation) {
    const { scale: zoom = 1, x: offsetX = 0, y: offsetY = 0, rotation = desktopRotation } = view.sceneCharacterTransform || {};
    const radians = rotation * Math.PI / 180;
    const cos = Math.cos(radians), sin = Math.sin(radians);
    let adjustedZoom = zoom;
    const fit = view.visibleFit;
    if (pet && rotation && fit?.model === view.model) {
      // Keep the rotated character inside the transparent pet viewport.
      const fitWidth = fit.width * width, fitHeight = fit.height * height;
      const rotatedWidth = Math.abs(cos) * fitWidth + Math.abs(sin) * fitHeight;
      const rotatedHeight = Math.abs(sin) * fitWidth + Math.abs(cos) * fitHeight;
      adjustedZoom = Math.min(zoom, width * .96 / rotatedWidth, height * .96 / rotatedHeight);
    }
    const centerX = width / 2, centerY = height / 2;
    const dx = view.model.position.x - centerX, dy = view.model.position.y - centerY;
    view.model.scale.set(view.model.scale.x * adjustedZoom);
    view.model.angle = rotation;
    view.model.position.set(centerX + width * offsetX + (dx * cos - dy * sin) * adjustedZoom,
      centerY + height * offsetY + (dx * sin + dy * cos) * adjustedZoom);
    if (!fitVisible) {
      view.app.renderer.render(view.app.stage);
      // A saved wallpaper pose can move the entire character outside the panel.
      if (!hasVisibleModelPixels()) {
        view.model.scale.set(scale);
        view.model.angle = 0;
        view.model.position.set(x, y);
      }
    }
  }
  if (view.session.limited && !desktopMoving) updateThirdPartyMenuButton();
}

function setSceneCharacterTransform({ scale, x, y, rotation = 0 }) {
  const previous = view.sceneCharacterTransform;
  if (previous?.scale === scale && previous.x === x && previous.y === y && previous.rotation === rotation) return;
  view.sceneCharacterTransform = { scale, x, y, rotation };
  placeModel();
  if (previous?.scale !== scale) view.app?.scheduleQualitySync();
}

window.setDesktopRotation = (rotation) => {
  if (desktopRotation === rotation) return;
  desktopRotation = rotation;
  view.app?.requestDesktopRotation();
};

function motionFades(model, metadata) {
  const manager = model.internalModel.motionManager;
  const sourceFades = view.session.catalog.motionFadePolicy === "source";
  const modern = Boolean(metadata.FileReferences);
  const motions = modern ? metadata.FileReferences.Motions : metadata.motions;
  manager.on("motionLoaded", (group, index, motion) => {
    const definition = motions?.[group]?.[index];
    // Cubism 3 uses seconds and Cubism 2 uses milliseconds in both the settings and the setter.
    if (modern) {
      motion.setFadeInTime?.(sourceFades ? (definition?.FadeInTime ?? 1) : 0);
      motion.setFadeOutTime?.(sourceFades ? (definition?.FadeOutTime ?? 1) : 0);
    } else {
      motion.setFadeIn?.(sourceFades ? (definition?.fade_in ?? 1000) : 0);
      motion.setFadeOut?.(sourceFades ? (definition?.fade_out ?? 1000) : 0);
    }
  });
}

async function selectCostume(costume) {
  const ticket = beginPanelLoad();
  let ready = false;
  const generation = ++view.generation;
  $("#loading").hidden = false;
  try {
    const packed = await view.viewer?.prepare(view.viewerOutfit);
    await loadEngine(!(packed?.modern ?? costume.cubism3));
    const file = asset(costume.file);
    const raw = packed?.metadata || await data(file);
    const metadata = packed?.metadata || preview(raw, costume, file);
    if (!packed) metadata.url = file;
    if (!view.app) view.app = makeRenderer();
    const next = await PIXI.live2d.Live2DModel.from(metadata, { autoInteract: false, motionPreload: "NONE" });
    if (generation !== view.generation) {
      next.destroy({ children: true });
      return false;
    }
    view.app.prepareModel(next);
    const outgoing = view.app.stage.removeChildren();
    view.model = next;
    view.viewerCostume = packed;
    view.visibleFit = null;
    view.costume = costume;
    view.bounds = next.getLocalBounds();
    const controllers = raw[raw.FileReferences ? "Controllers" : "controllers"];
    const sync = controllers?.[raw.FileReferences ? "LipSync" : "lip_sync"];
    const targets = metadata.pageMouthIds || (sync?.Items || sync?.items || []).map((item) => item.Id || item.id);
    next.internalModel.on("beforeModelUpdate", () => moveMouth(next, targets));
    if (!packed) motionFades(next, metadata);
    view.app.stage.addChild(next);
    placeModel();
    view.app.renderer.render(view.app.stage);
    for (const model of outgoing) model.destroy({ children: true });
    renderItems();
    updateWallpaperButton();
    scheduleWallpaperFrame();
    ready = true;
    return true;
  } finally {
    if (generation === view.generation) {
      $("#loading").hidden = true;
      native.reportModelRendering?.(ready ? "ready" : "error");
      finishPanelLoad(ticket, ready);
    }
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
    (!showFavorites || isFavorite(item, favoriteIds)) &&
    (item.name || "").toLocaleLowerCase().includes(query));
  const list = document.createDocumentFragment();
  for (const item of entries) {
    const row = document.createElement("li");
    const play = button(item.name, "item", () => send(view.tab, item.id));
    if (view.tab === "costume") play.setAttribute("aria-pressed", String(view.costume?.id === item.id));
    row.append(play);
    if (item.flag) {
      const saved = isFavorite(item, favoriteIds);
      const heart = button(saved ? "♥" : "♡", "heart", () => {
        if (saved) {
          favoriteIds.delete(item.flag);
          for (const alias of item.aliasFlags || []) favoriteIds.delete(alias);
        } else favoriteIds.add(item.flag);
        writeDesktopSetting("favorites:" + view.session.catalog.id, [...favoriteIds]);
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
  document.querySelectorAll("[data-tab]").forEach((node) =>
    node.setAttribute("aria-pressed", String(node.dataset.tab === tab)));
  $("#favorites").setAttribute("aria-pressed", String(showFavorites));
  $("#favorites").textContent = showFavorites ? "♥" : "♡";
  renderItems();
}

function readFavoriteIds() {
  return new Set(JSON.parse(readDesktopSetting("favorites:" + view.session.catalog.id) || "[]"));
}

function isFavorite(item, favorites) {
  return favorites.has(item.flag) || item.aliasFlags?.some(flag => favorites.has(flag));
}

function readDesktopSetting(key) {
  const shared = native.desktopSetting(key);
  if (shared !== null) return shared;
  const legacy = localStorage.getItem(key);
  if (legacy !== null && !pet && !wallpaper && key !== "wallpaper-scene-v1") native.setDesktopSetting(key, legacy);
  return legacy;
}

function writeDesktopSetting(key, data) {
  const value = JSON.stringify(data);
  if (!native.setDesktopSetting(key, value)) throw new Error("无法保存桌面设置，请检查可用存储空间。");
}

function updateLoops() {
  $("#loop").setAttribute("aria-pressed", String(loops.has(view.tab)));
  document.querySelectorAll("[data-loop]").forEach((node) =>
    node.setAttribute("aria-pressed", String(loops.has(node.dataset.loop))));
}

function send(type, id = 0, kind = "", loop) {
  if (!view.session || view.session.limited) return;
  const outfit = type === "action" || type === "expression" ? view.costume?.id : undefined;
  native.command(JSON.stringify({ type, id, kind, role: view.session.catalog.id, outfit, loop }));
}

function ownsPlayback() {
  if (pet) return overlayVisible;
  if (wallpaper) return wallpaperPlaybackActive && !overlayVisible;
  return panelPlaybackActive && !wallpaperPanelReloading && !document.hidden && !overlayVisible;
}

function updateLoopOwner() {
  if (!ownsPlayback()) stopVoice();
  armLoops();
}

window.setPlaybackActive = (active) => {
  if (wallpaper) wallpaperPlaybackActive = active;
  else {
    panelPlaybackActive = active;
    if (!pet) {
      if (active) view.app?.resume();
      else view.app?.pause();
      window.sceneLayers?.setVisible(active && !document.hidden);
    }
  }
  updateLoopOwner();
};
if (!pet && !wallpaper) document.addEventListener("visibilitychange", updateLoopOwner);

function armLoops() {
  clearTimeout(loopTimer);
  if (!ownsPlayback() || loopInFlight || costumeChange || !view.model || !$("#loading").hidden || !loopDeadlines.size) return;
  loopTimer = setTimeout(runDueLoop, Math.max(0, Math.min(...loopDeadlines.values()) - performance.now()));
}

function scheduleLoop(kind, delay = loopIntervals[kind]) {
  if (!loops.has(kind)) return;
  loopDeadlines.set(kind, performance.now() + delay);
  armLoops();
}

function sendLoopStep(track, type, id, kind = "") {
  loopDeadlines.delete(track);
  const token = loopInFlight = loopSource + ":" + (++loopSequence);
  send(type, id, kind, { track, token });
}

function runDueLoop() {
  if (!ownsPlayback() || loopInFlight || costumeChange || !view.costume || !$("#loading").hidden) return;
  const now = performance.now();
  // Read all due deadlines together so a wardrobe change wins over coincident tracks.
  const kind = Object.keys(loopIntervals).find((kind) => loopDeadlines.get(kind) <= now + 40);
  if (!kind) return armLoops();
  const catalog = view.session.catalog;
  const costume = view.costume;
  if (kind === "action") {
    const data = readPerformances(catalog.id);
    if (data.enabled) {
      const next = nextPerformance(data);
      if (!next) return scheduleLoop(kind, loopIntervals.expression);
      if (next.group.tracks.voice && voiceEndsAt > now) return scheduleLoop(kind, voiceEndsAt - now + 200);
      performanceCursor.set(next.cursor, next.group.id);
      sendLoopStep(kind, "performance", costume.id, next.group.id);
      return;
    }
  }
  if (kind === "voice" && voiceEndsAt > now) return scheduleLoop(kind, voiceEndsAt - now + 200);
  const choices = kind === "costume" ? catalog.costumes.filter((item) => item.id !== costume.id)
    : performanceChoices(catalog, costume)[kind];
  const item = choices[Math.floor(Math.random() * choices.length)];
  if (item) sendLoopStep(kind, kind, item.id);
  else scheduleLoop(kind);
}

function playRandom(kind, filter = () => true) {
  const list = items(kind).filter(filter);
  if (!list.length) return false;
  const choices = kind === "costume" && list.length > 1 ? list.filter((item) => item.id !== view.costume?.id) : list;
  send(kind, choices[Math.floor(Math.random() * choices.length)].id);
  return true;
}

function playInteraction(kind, filter = () => true) {
  const favorites = native.interactionFavoritesOnly() ? readFavoriteIds() : null;
  const played = playRandom(kind, (item) => filter(item) && (!favorites || (
    isFavorite(item, favorites) && (kind !== "costume" || item.id !== view.costume?.id)
  )));
  if (!played && favorites) {
    const detail = kind === "costume" ? "请先收藏另一套衣装" : `这个部位暂无已收藏的${names[kind]}`;
    window.showAppNotice(`已开启「互动收藏」，${detail}`);
  }
}

function enableCaptionDrag(card, bounds, report = () => {}) {
  let position;
  let drag;
  const clamp = (point, size, extent) => {
    const inset = Math.min(size, extent) / 2;
    return Math.max(inset, Math.min(extent - inset, point));
  };

  function layout() {
    if (!card.hidden && (position || pet)) {
      const area = bounds();
      if (area.width && area.height) {
        card.style.maxWidth = area.width * .94 + "px";
        const x = clamp((position?.x ?? .5) * area.width, card.offsetWidth, area.width);
        const y = clamp((position?.y ?? .94) * area.height, card.offsetHeight, area.height);
        card.style.left = (area.x + x) + "px";
        card.style.top = (area.y + y) + "px";
        card.style.bottom = "auto";
        card.style.transform = "translate(-50%, -50%)";
      }
    }
    report(card.hidden ? null : card.getBoundingClientRect());
  }

  card.addEventListener("pointerdown", (event) => {
    if (!event.isPrimary || event.button !== 0 || drag) return;
    const rect = card.getBoundingClientRect();
    drag = { id: event.pointerId, x: event.clientX - rect.left - rect.width / 2,
      y: event.clientY - rect.top - rect.height / 2 };
    card.setPointerCapture(event.pointerId);
    event.preventDefault();
  });
  card.addEventListener("pointermove", (event) => {
    if (drag?.id !== event.pointerId || card.hidden) return;
    const parent = card.offsetParent.getBoundingClientRect();
    const area = bounds();
    if (!area.width || !area.height) return;
    const x = event.clientX - parent.left - card.offsetParent.clientLeft - drag.x - area.x;
    const y = event.clientY - parent.top - card.offsetParent.clientTop - drag.y - area.y;
    position = { x: clamp(x, card.offsetWidth, area.width) / area.width,
      y: clamp(y, card.offsetHeight, area.height) / area.height };
    layout();
  });
  for (const type of ["pointerup", "pointercancel", "lostpointercapture"]) {
    card.addEventListener(type, (event) => {
      if (drag?.id === event.pointerId) drag = null;
    });
  }
  new ResizeObserver(layout).observe(card.parentElement);
  return { layout, get dragging() { return Boolean(drag) && !card.hidden; } };
}

function stopVoice() {
  ++voiceGeneration;
  voiceEndsAt = 0;
  if (audioNode) {
    try { audioNode.stop(); } catch {}
    audioNode = null;
  }
  caption.hidden = true;
  captionDrag.layout();
  mouth = 0;
}

window.setCaptionCardsEnabled = (enabled) => {
  captionCardsEnabled = enabled;
  $("#caption-cards").checked = enabled;
  caption.hidden = !enabled || !audioNode || !caption.textContent;
  captionDrag.layout();
};

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
  const url = item.url ?? asset(item.file);
  await audioContext.resume();
  const response = await fetch(url);
  if (!response.ok) throw new Error("语音资源读取失败");
  const buffer = await audioContext.decodeAudioData(await response.arrayBuffer());
  if (generation !== voiceGeneration) return;
  const node = audioContext.createBufferSource();
  audioNode = node;
  node.buffer = buffer;
  node.connect(analyser);
  caption.textContent = item.caption || item.name;
  window.setCaptionCardsEnabled(native.captionCardsEnabled());
  node.onended = () => {
    if (audioNode !== node) return;
    stopVoice();
  };
  node.start();
  const duration = buffer.duration * 1000;
  voiceEndsAt = performance.now() + duration;
  return duration;
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

function resetModelExpression() {
  const manager = view.model?.internalModel.motionManager.expressionManager;
  if (!manager) return;
  manager.reserveExpressionIndex = -1;
  manager.currentExpression = manager.defaultExpression;
  manager.resetExpression();
}

function resetActivePlayback() {
  stopVoice();
  const manager = view.model?.internalModel.motionManager;
  manager?.stopAllMotions();
  resetModelExpression();
}

function cancelPlayback() {
  ++playbackGeneration;
  clearTimeout(loopTimer);
  loopInFlight = null;
  loopDeadlines.clear();
  loops.clear();
  resetActivePlayback();
  updateLoops();
}

function readPerformances(role) {
  try {
    const data = JSON.parse(readDesktopSetting("performances:" + role));
    if (data && typeof data.enabled === "boolean" && data.groups && typeof data.groups === "object") {
      mergeOutfitPerformances(data.groups, view.session?.catalog.costumes || []);
      return data;
    }
  } catch {}
  return { enabled: false, groups: {} };
}

function writePerformances(role, update) {
  const data = readPerformances(role);
  update(data);
  writeDesktopSetting("performances:" + role, data);
}

function setPerformanceEnabled(role, enabled) {
  writePerformances(role, (data) => { data.enabled = enabled; });
}

function nextPerformance(data) {
  const { costume } = view;
  if (!costume) return;
  const groups = outfitPerformances(data.groups[costume.flag]);
  if (!groups.length) return;
  const cursor = view.session.catalog.id + ":" + costume.flag;
  const last = performanceCursor.get(cursor) || data.last?.[costume.flag];
  return { cursor, group: groups[(groups.findIndex((group) => group.id === last) + 1) % groups.length] };
}

function sendPerformance(data) {
  const next = nextPerformance(data);
  if (!next) return;
  performanceCursor.set(next.cursor, next.group.id);
  send("performance", view.costume.id, next.group.id);
}

function actionLoopDelay(item) {
  return Math.max(600, (item.duration || 1) * 1000 + 600);
}

async function playPerformance(outfit, presetId, sound, automatic) {
  const { costume, model, session } = view;
  if (session.limited || costume?.id !== outfit || !model || !$("#loading").hidden) return;
  const { enabled, groups } = readPerformances(session.catalog.id);
  const group = outfitPerformances(groups[costume.flag]).find((item) => item.id === presetId);
  if (!enabled || !group) return;
  const choices = performanceChoices(session.catalog, costume);
  const tracks = {};
  for (const kind of performanceTracks) {
    if (!group.tracks[kind]) continue;
    const item = choices[kind].find((entry) => matchesItemFlag(entry, group.tracks[kind]));
    if (!item) return;
    tracks[kind] = item;
  }
  if (!Object.keys(tracks).length) return;
  if (!automatic) resetActivePlayback();
  if (tracks.expression && !tracks.expression.file) resetModelExpression();
  const [, , voiceDuration] = await Promise.all([
    tracks.action && model.motion("page_" + tracks.action.id, 0, 3),
    tracks.expression?.file && model.expression(String(tracks.expression.id)),
    tracks.voice && sound && playVoice(tracks.voice),
  ]);
  return Math.max(tracks.action ? actionLoopDelay(tracks.action) : 0,
    voiceDuration ? voiceDuration + 600 : 0,
    tracks.action || tracks.voice ? loopIntervals.action : loopIntervals.expression);
}

async function changePlaybackCostume(item, session) {
  const previous = costumeChange || Promise.resolve();
  const change = previous.catch(() => {}).then(async () => {
    if (view.session !== session || view.costume?.id === item.id) return;
    stopVoice();
    await selectCostume(item);
  });
  costumeChange = change;
  try { await change; }
  finally { if (costumeChange === change) costumeChange = null; }
}

async function receiveCommand(command, sound) {
  if (!view.session || view.session.catalog.id !== command.role) return;
  const session = view.session;
  const generation = playbackGeneration;
  const { type, id, kind, outfit, loop } = command;
  let delay;
  const ownStep = loop?.token === loopInFlight && Boolean(loop);
  try {
    if (loop?.token?.startsWith(loopSource + ":") && (!ownStep || !loops.has(loop.track))) return;
    if (type === "reset") return cancelPlayback();
    if (type === "loop" && Object.hasOwn(loopIntervals, kind)) {
      if (id) { loops.add(kind); scheduleLoop(kind, 250); }
      else { loops.delete(kind); loopDeadlines.delete(kind); }
      return updateLoops();
    }
    if (type === "costume") {
      const item = items(type).find((entry) => entry.id === id);
      if (!item) return;
      delay = loopIntervals.costume;
      await changePlaybackCostume(item, session);
      return;
    }
    while (costumeChange) await costumeChange.catch(() => {});
    if (view.session !== session || generation !== playbackGeneration || !view.costume ||
        (outfit !== undefined && outfit !== view.costume.id)) return;
    if (type === "performance") {
      const interval = await playPerformance(id, kind, sound, Boolean(loop));
      if (loop) delay = interval;
      return;
    }
    const item = (loop && type === "voice" ? performanceChoices(session.catalog, view.costume).voice
      : items(type)).find((entry) => entry.id === id);
    if (!item) return;
    if (type === "action") {
      delay = actionLoopDelay(item);
      await view.model?.motion("page_" + id, 0, 3);
    } else if (type === "expression") {
      delay = loopIntervals.expression;
      if (item.file) await view.model?.expression(String(id));
      else resetModelExpression();
    } else if (type === "voice") {
      delay = loopIntervals.voice;
      if (sound) delay += (await playVoice(item)) ?? 0;
    }
  } catch (error) {
    native.reportRuntimeFailure?.(type);
    reportError(error.message);
    console.error(error);
  } finally {
    if (ownStep && loopInFlight === loop.token) loopInFlight = null;
    if (generation === playbackGeneration && view.session === session && (loop || delay !== undefined)) {
      scheduleLoop(loop?.track ?? type, delay);
    }
    armLoops();
  }
}

function reportPetBounds(name, rect) {
  const bounds = rect && innerWidth && innerHeight
    ? [rect.left / innerWidth, rect.top / innerHeight, rect.right / innerWidth, rect.bottom / innerHeight]
    : [0, 0, 0, 0];
  const key = bounds.join(",");
  if (reportedControls.get(name) === key) return;
  reportedControls.set(name, key);
  native.controlBounds(name, ...bounds);
}

function desktopControlArea() {
  const width = pet ? $("#canvas").clientWidth * desktopModelScale : captionStage.clientWidth;
  const height = pet ? $("#canvas").clientHeight * desktopModelScale : captionStage.clientHeight;
  if (!pet || !desktopCrop) return { x: 0, y: 0, width, height };
  const [left, top, right, bottom] = desktopCrop;
  return { x: left * width, y: top * height, width: (right - left) * width, height: (bottom - top) * height };
}

function setDesktopScale(scale) {
  desktopModelScale = scale;
  $("#canvas").style.transform = `scale(${desktopModelScale})`;
  if (!desktopMoving) {
    desktopRasterScale = desktopModelScale;
    captionDrag.layout();
    updateThirdPartyMenuButton();
  }
}
window.setDesktopScale = setDesktopScale;

window.setDesktopCrop = (...bounds) => {
  desktopCrop = bounds;
  captionDrag.layout();
  updateThirdPartyMenuButton();
};

function positionPetMenu() {
  if (petMenu.hidden) return;
  const area = pet || wallpaper ? desktopControlArea() : { x: 0, y: 0, width: innerWidth, height: innerHeight };
  petMenu.style.maxWidth = Math.max(0, area.width - 8) + "px";
  petMenu.style.maxHeight = Math.max(0, area.height - 8) + "px";
  const right = area.x + area.width - petMenu.offsetWidth - 4;
  const freeHeight = area.height - petMenu.offsetHeight;
  const top = area.y + (wallpaper ? freeHeight / 2 : freeHeight - 4);
  petMenu.style.top = Math.max(area.y + 4, top) + "px";
  petMenu.style.left = Math.max(area.x + 4, right) + "px";
  if (pet) reportPetBounds("menu", petMenu.getBoundingClientRect());
}

function showPetMenu(show = petMenu.hidden) {
  petMenu.hidden = !show;
  if (show) {
    petMenuButton.hidden = true;
    if (pet) reportPetBounds("menuButton", null);
    petMenu.scrollTop = 0;
    positionPetMenu();
  } else {
    if (pet) reportPetBounds("menu", null);
    updateThirdPartyMenuButton();
    scheduleWallpaperFrame();
  }
}

function updateThirdPartyMenuButton() {
  const visible = (pet || wallpaper) && view.model && view.session?.limited && thirdPartyMenuEnabled && petMenu.hidden;
  petMenuButton.hidden = !visible;
  if (visible) {
    const area = desktopControlArea();
    petMenuButton.style.left = Math.max(area.x + 18, area.x + area.width - 21) + "px";
    petMenuButton.style.top = Math.max(area.y + 18, area.y + area.height - 21) + "px";
  }
  if (pet) reportPetBounds("menuButton", visible ? petMenuButton.getBoundingClientRect() : null);
  positionPetMenu();
}

function avatarCoordinates(event) {
  if (!view.model || !$("#loading").hidden) return;
  const rect = $("#canvas").getBoundingClientRect();
  const x = (event.clientX - rect.left) / rect.width;
  const y = (event.clientY - rect.top) / rect.height;
  if (x >= 0 && x < 1 && y >= 0 && y < 1) return { x, y };
}

function avatarOpaque({ x, y }) {
  const pixel = (Math.floor(y * touchRows) * touchColumns + Math.floor(x * touchColumns)) * 4;
  return view.app.readAlpha()[pixel + 3] >= 28;
}

function avatarRegion(event) {
  const coordinates = avatarCoordinates(event);
  if (!coordinates) return;
  const x = coordinates.x * view.app.screen.width;
  const y = coordinates.y * view.app.screen.height;
  if (view.viewerCostume) return view.viewerCostume.hit(view.model, x, y);
  const point = view.model.toModelPosition(new PIXI.Point(x, y));
  const internal = view.model.internalModel;
  if (view.costume.cubism3) {
    point.x = (point.x - internal.originalWidth / 2) / internal.pixelsPerUnit;
    point.y = (point.y - internal.originalHeight / 2) / internal.pixelsPerUnit;
  }
  const regions = view.costume.hitRegions || {};
  const contains = (box) => box && point.x >= box[0] && point.x <= box[2] && point.y >= box[1] && point.y <= box[3];
  const region = ["menu", "expression", "voice", "left", "right", "body", "wardrobe"].find((name) => contains(regions[name]));
  if (region || !view.costume.cubism3) return region;

  // Instruments and wide costumes can extend past the authored rectangles.
  // Extend those touch bands over the visible model while leaving the background alone.
  const band = ["menu", "left", "right"].find((name) => {
    const box = regions[name];
    if (!box || point.y < box[1] || point.y > box[3]) return false;
    return name === "menu" || (name === "left" ? point.x > box[2] : point.x < box[0]);
  });
  if (band && avatarOpaque(coordinates)) return band;
}

async function playViewerAction(action) {
  if (action.outfit) {
    stopVoice();
    view.viewerOutfit = action.outfit;
    await selectCostume(view.costume);
    return;
  }
  if (action.sound) await playVoice({ url: action.sound, caption: action.text, name: action.text });
  if (action.expression) await view.model.expression(action.expression);
  if (action.motion) await view.model.motion(action.motion, 0, 3);
}

function avatarClick(event) {
  if (!view.model || !$("#loading").hidden) return;
  const region = avatarRegion(event);
  if (view.session.limited) {
    const coordinates = avatarCoordinates(event);
    const action = region || (coordinates && view.viewerCostume?.hasTap && avatarOpaque(coordinates))
      ? view.viewerCostume?.choose(region) : null;
    if (action) playViewerAction(action).catch(error => {
      native.reportRuntimeFailure?.("viewer-action");
      window.showAppNotice(error.message);
    });
    return;
  }
  if (region === "menu") {
    if (touchMenuEnabled) showPetMenu();
  }
  else if (region === "wardrobe") playInteraction("costume");
  else if (region === "voice" || region === "expression") playInteraction(region);
  else if (region === "body") {
    const performance = readPerformances(view.session.catalog.id);
    if (performance.enabled) sendPerformance(performance);
    else playInteraction("action", (item) => actionMatchesRegion(item, "body"));
  }
  else if (region) playInteraction("action", (item) => actionMatchesRegion(item, region));
}

function actionMatchesRegion(item, region) {
  return item.categories ? item.categories.includes(region) : item.category === region;
}

$("#choose").onclick = () => native.pickModel();
$("#replace").onclick = () => window.openModelLibrary?.();
for (const selector of ["#toggle", "#exit-pet"]) $(selector).onclick = () => native.togglePet();
for (const selector of ["#reset", "#pet-reset"]) $(selector).onclick = () => send("reset");
$("#open-panel").onclick = () => { showPetMenu(false); native.openPanel(); };
$("#close-pet-menu").onclick = () => showPetMenu(false);
petMenuButton.onclick = () => showPetMenu();
$("#placement").onclick = () => {
  const homeOnly = native.homeOnly();
  $("#mode-top").checked = !homeOnly;
  $("#mode-home").checked = homeOnly;
  $("#favorite-interactions").checked = native.interactionFavoritesOnly();
  $("#caption-cards").checked = captionCardsEnabled;
  updateTouchMenuSetting();
  $("#usage-access").hidden = !homeOnly || native.canUseHomeMode();
  if (!settings.open) {
    native.settingsVisible(true);
    settings.showModal();
  }
};
$("#settings-close").onclick = () => settings.close();
const openPerformance = createPerformanceEditor({
  read: () => view.session && !view.session.limited && {
    catalog: view.session.catalog,
    costume: view.costume,
    ...readPerformances(view.session.catalog.id),
  },
  setEnabled: setPerformanceEnabled,
  save: (role, outfit, preset, group) => writePerformances(role, ({ groups }) =>
    updateOutfitPerformances(groups, outfit, preset, group)),
  onVisibility: syncAppModalVisibility,
});
$("#open-performance").onclick = () => {
  settings.close();
  openPerformance();
};
const permissions = $("#permission-config");
$("#open-permissions").onclick = () => {
  if (!permissions.open) permissions.showModal();
  syncAppModalVisibility();
  native.refreshPermissions();
};
$("#close-permissions").onclick = () => permissions.close();
permissions.addEventListener("close", syncAppModalVisibility);
permissions.querySelectorAll("[data-permission]").forEach(control => {
  control.onclick = () => native.openPermission(control.dataset.permission);
});
const diagnostics = $("#runtime-diagnostics");
$("#open-diagnostics").onclick = () => {
  const report = diagnostics.querySelector(".diagnostics-report");
  const sections = native.runtimeDiagnostics().split("\n\n");
  const playback = ["当前面板与播放",
    "模型  " + (!$("#loading").hidden ? "加载中" : view.model ? "已显示" : "未显示"),
    "当前视图  " + (document.documentElement.classList.contains("immersive-preview") ? "纯享" : "控制面板"),
    "字幕  " + (captionCardsEnabled ? "已开启" : "未开启"),
    "触控菜单  " + (touchMenuEnabled ? "已开启" : "未开启")];
  if (view.session && !view.session.limited) {
    const { enabled, groups } = readPerformances(view.session.catalog.id);
    playback.push("可用内容  " + Object.keys(names).map(kind => names[kind] + " " + items(kind).length).join(" · "),
      "循环  " + ([...loops].map(kind => names[kind]).join("、") || "未开启"),
      `演出模式  ${enabled ? "已开启" : "未开启"} · ${Object.values(groups).flatMap(outfitPerformances).length} 个演出组`);
  } else if (view.session) {
    playback.push("模型模式  " + (view.viewer ? "第三方互动模型" : "基础预览"));
  }
  const scene = window.sceneLayers;
  const layers = scene?.layers || [];
  const materials = ["当前素材预览", "图层  " + layers.length,
    "素材  " + layers.filter(layer => layer.media).length + " · 特效  " + layers.filter(layer => layer.kind === "effect").length,
    "可见素材  " + (scene?.mediaReady() ? "就绪" : "未就绪"),
    "默认背景配色  " + $("#theme-current").textContent,
    "背景微动  " + (!native.hasWallpaperTilt() ? "设备不支持" : native.wallpaperTiltEnabled() ? "已开启" : "未开启")];
  sections.splice(-1, 0, playback.join("\n"), materials.join("\n"));
  report.replaceChildren(...sections.map(content => {
    const firstLine = content.indexOf("\n");
    const section = document.createElement("section");
    const title = document.createElement("h3");
    title.textContent = content.slice(0, firstLine);
    const body = document.createElement("p");
    body.textContent = content.slice(firstLine + 1);
    section.append(title, body);
    return section;
  }));
  diagnostics.showModal();
  report.scrollTop = 0;
  syncAppModalVisibility();
};
diagnostics.addEventListener("close", syncAppModalVisibility);
settings.addEventListener("close", () => {
  syncAppModalVisibility();
  revealPreviewControls();
});
function syncAppModalVisibility() {
  native.settingsVisible(!!document.querySelector("dialog[open]"));
}
window.showAppPrompt = (id, title, message, action, callback, alternative) => {
  promptId = id;
  promptCallback = callback;
  appPrompt.returnValue = "";
  $("#app-prompt-title").textContent = title;
  $("#app-prompt-message").textContent = message;
  $("#app-prompt-cancel").hidden = !action;
  const alternate = $("#app-prompt-alternate");
  alternate.hidden = !alternative;
  alternate.textContent = alternative || "";
  $("#app-prompt-confirm").textContent = action || "知道了";
  if (!appPrompt.open) appPrompt.showModal();
  syncAppModalVisibility();
};
appPrompt.addEventListener("close", () => {
  syncAppModalVisibility();
  const confirmed = appPrompt.returnValue === "confirm";
  if (promptCallback) promptCallback(confirmed);
  else if (Number.isInteger(promptId)) native.answerPrompt(promptId, appPrompt.returnValue);
  revealPreviewControls();
});
window.showAppNotice = (message) => {
  const notice = $("#app-notice");
  if (pet) {
    const canvas = $("#canvas");
    notice.style.left = canvas.clientWidth * desktopModelScale / 2 + "px";
    notice.style.top = canvas.clientHeight * desktopModelScale - 70 + "px";
    notice.style.maxWidth = Math.max(0, canvas.clientWidth * desktopModelScale - 28) + "px";
  }
  notice.textContent = message;
  notice.hidden = false;
  clearTimeout(noticeTimer);
  noticeTimer = setTimeout(() => { notice.hidden = true; }, 3200);
};
settings.addEventListener("click", (event) => {
  if (event.target !== settings) return;
  const rect = settings.getBoundingClientRect();
  if (event.clientX < rect.left || event.clientX > rect.right || event.clientY < rect.top || event.clientY > rect.bottom) {
    settings.close();
  }
});
$("#mode-top").onchange = () => {
  $("#usage-access").hidden = true;
  native.setHomeOnly(false);
};
$("#mode-home").onchange = () => {
  if (native.canUseHomeMode()) native.setHomeOnly(true);
  else {
    $("#mode-top").checked = true;
    $("#usage-access").hidden = false;
  }
};
$("#grant-usage").onclick = () => native.setHomeOnly(true);
$("#pure-preview-toggle").onchange = (event) => {
  setControlsCollapsed(event.target.checked);
  settings.close();
};
$("#favorite-interactions").onchange = (event) => native.setInteractionFavoritesOnly(event.target.checked);
$("#caption-cards").onchange = (event) => {
  const enabled = event.target.checked;
  window.setCaptionCardsEnabled(enabled);
  native.setCaptionCardsEnabled(enabled);
};
const avatarCanvas = $("#canvas");
function endPreviewPress() {
  clearTimeout(previewPress?.timer);
  previewPress = undefined;
}
function openPreviewMenu(press) {
  if (press.opened || !touchMenuEnabled || view.session?.limited) return;
  const coordinates = avatarCoordinates({ clientX: press.x, clientY: press.y });
  if (coordinates && avatarOpaque(coordinates)) {
    press.opened = true;
    showPetMenu(true);
  }
}
avatarCanvas.addEventListener("pointerdown", (event) => {
  endPreviewPress();
  const root = document.documentElement;
  if (event.pointerType !== "touch" || !event.isPrimary || !root.classList.contains("immersive-preview") ||
      (root.classList.contains("scene-edit") && !root.classList.contains("scene-preview"))) return;
  const press = { id: event.pointerId, x: event.clientX, y: event.clientY, started: event.timeStamp, opened: false };
  // Android can suppress click after a long touch, so open the menu while it is held.
  press.timer = setTimeout(() => openPreviewMenu(press), 450);
  previewPress = press;
});
avatarCanvas.addEventListener("pointermove", (event) => {
  if (previewPress && event.pointerId === previewPress.id &&
      Math.hypot(event.clientX - previewPress.x, event.clientY - previewPress.y) > 12) endPreviewPress();
});
avatarCanvas.addEventListener("pointerup", (event) => {
  const press = previewPress;
  if (!press || event.pointerId !== press.id) return;
  clearTimeout(press.timer);
  // A slow render can defer the timer until Android has delivered pointerup.
  if (event.timeStamp - press.started >= 450) openPreviewMenu(press);
});
avatarCanvas.addEventListener("pointercancel", endPreviewPress);
avatarCanvas.addEventListener("contextmenu", (event) => { if (previewPress) event.preventDefault(); });
avatarCanvas.onclick = (event) => {
  const opened = previewPress?.opened;
  endPreviewPress();
  if (opened) return;
  if (document.documentElement.classList.contains("scene-edit")) {
    if (document.documentElement.classList.contains("scene-preview")) avatarClick(event);
    return;
  }
  avatarClick(event);
};
const previewStage = $("#stage");
const backgroundTapInterval = 450;
const backgroundTapSlop = 16;
let backgroundPress, backgroundTap, backgroundClick, previewEdgeTimer;
function cancelPreviewTouch() {
  endPreviewPress();
  backgroundPress = backgroundTap = null;
  clearTimeout(previewEdgeTimer);
}
function canTapPreviewBackground(event) {
  return view.session && view.model && $("#loading").hidden && previewStage.contains(event.target) &&
    !document.documentElement.classList.contains("scene-edit") && !event.target.closest("#caption, #pet-menu, button");
}
function hiddenPreviewEdge(clientY) {
  const root = document.documentElement;
  if (!root.classList.contains("immersive-preview") || root.classList.contains("scene-edit")) return;
  const { top, bottom } = previewStage.getBoundingClientRect();
  const edge = clientY < top + 64 ? "top" : clientY > bottom - 64 ? "bottom" : null;
  if (edge && root.classList.contains("preview-" + edge + "-hidden")) return edge;
}
if (!pet && !wallpaper) {
  // Android WebView can drop compatibility clicks. Count completed pointer taps instead.
  document.addEventListener("pointerdown", (event) => {
    backgroundClick = false;
    if (!event.isPrimary || event.button !== 0 || !canTapPreviewBackground(event)) {
      cancelPreviewTouch();
      return;
    }
    clearTimeout(previewEdgeTimer);
    backgroundPress = { id: event.pointerId, x: event.clientX, y: event.clientY, time: event.timeStamp };
  }, true);
  document.addEventListener("pointermove", (event) => {
    if (backgroundPress?.id === event.pointerId &&
        Math.hypot(event.clientX - backgroundPress.x, event.clientY - backgroundPress.y) > backgroundTapSlop) {
      cancelPreviewTouch();
    }
  }, true);
  document.addEventListener("pointercancel", (event) => {
    if (backgroundPress?.id === event.pointerId) cancelPreviewTouch();
  }, true);
  document.addEventListener("pointerup", (event) => {
    const press = backgroundPress;
    if (press?.id !== event.pointerId) return;
    backgroundPress = null;
    if (event.timeStamp - press.time > 280 ||
        Math.hypot(event.clientX - press.x, event.clientY - press.y) > backgroundTapSlop || !canTapPreviewBackground(event)) {
      cancelPreviewTouch();
      return;
    }
    const coordinates = event.target.closest("#canvas") && avatarCoordinates(event);
    if (coordinates && view.app.readAlpha(coordinates) >= 28) {
      backgroundTap = null;
      clearTimeout(previewEdgeTimer);
      return;
    }
    backgroundClick = true;
    const last = backgroundTap;
    if (last?.session === view.session && last.collapsed === controlsCollapsed &&
        press.time - last.time <= backgroundTapInterval && Math.hypot(press.x - last.x, press.y - last.y) <= 42) {
      backgroundTap = null;
      setControlsCollapsed(!controlsCollapsed, true);
      return;
    }
    backgroundTap = { x: press.x, y: press.y, time: event.timeStamp, session: view.session, collapsed: controlsCollapsed };
    const edge = hiddenPreviewEdge(event.clientY);
    if (edge) {
      // Keep the edge clear for the second tap before showing the toolbar.
      previewEdgeTimer = setTimeout(() => revealPreviewControls(edge), backgroundTapInterval);
    }
  }, true);
}
previewStage.addEventListener("click", (event) => {
  if ((backgroundClick && event.detail > 0) || window.sceneLayers?.ignoreGestureClick(event)) {
    backgroundClick = false;
    event.preventDefault();
    event.stopImmediatePropagation();
    return;
  }
  const edge = hiddenPreviewEdge(event.clientY);
  if (!edge) return;
  if (event.target.closest("#canvas") && avatarRegion(event)) return;
  revealPreviewControls(edge);
  event.stopPropagation();
}, { capture: true });
$("#toolbar").addEventListener("pointerdown", () => revealPreviewControls("top"));
$(".title-row").addEventListener("pointerdown", () => revealPreviewControls("bottom"));
$("#filter").oninput = renderItems;
$("#favorites").onclick = () => { showFavorites = !showFavorites; setTab(view.tab); };
$("#loop").onclick = () => send("loop", Number(!loops.has(view.tab)), view.tab);
document.querySelectorAll("[data-tab]").forEach((node) => { node.onclick = () => setTab(node.dataset.tab); });
document.querySelectorAll("[data-loop]").forEach((node) => {
  node.onclick = () => send("loop", Number(!loops.has(node.dataset.loop)), node.dataset.loop);
});

refreshModel().catch((error) => reportError(error.message));

function scheduleWallpaperFrame() {
  if (!wallpaper) return;
  clearTimeout(wallpaperFrameTimer);
  if (!wallpaperVisible || !view.model) return;
  const deadline = performance.now() + 1800;
  const waitForMedia = () => {
    if (window.sceneLayers?.mediaReady() === false && performance.now() < deadline) {
      wallpaperFrameTimer = setTimeout(waitForMedia, 80);
      return;
    }
    native.wallpaperFrameReady();
  };
  wallpaperFrameTimer = setTimeout(waitForMedia, 0);
}

function updateTouchMenuSetting() {
  const thirdParty = Boolean(view.session?.limited);
  $("#touch-menu").checked = thirdParty ? thirdPartyMenuEnabled : touchMenuEnabled;
}

window.setTouchMenuEnabled = (enabled, thirdParty = false) => {
  if (thirdParty) thirdPartyMenuEnabled = enabled;
  else touchMenuEnabled = enabled;
  updateTouchMenuSetting();
  if (!enabled && thirdParty === Boolean(view.session?.limited) && !petMenu.hidden) showPetMenu(false);
  updateThirdPartyMenuButton();
};
$("#touch-menu").onchange = (event) => {
  const enabled = event.target.checked;
  const thirdParty = Boolean(view.session?.limited);
  window.setTouchMenuEnabled(enabled, thirdParty);
  native.setTouchMenuEnabled(enabled, thirdParty);
};

window.setWallpaperVisible = (visible) => {
  wallpaperVisible = visible;
  if (!visible) stopVoice();
  if (visible) view.app?.resume();
  else view.app?.pause();
  window.sceneLayers?.setVisible(visible);
  scheduleWallpaperFrame();
};
window.wallpaperTap = (x, y) => {
  const clientX = x * innerWidth, clientY = y * innerHeight;
  const target = document.elementFromPoint(clientX, clientY);
  if (target?.closest("#pet-menu, #pet-menu-button")) target.closest("button")?.click();
  else if (target?.closest("#canvas")) avatarClick({clientX, clientY});
};
window.wallpaperGaze = (x, y) => {
  const model = view.model;
  const internal = model?.internalModel;
  if (!internal) return;
  if (!Number.isFinite(x) || !Number.isFinite(y)) return internal.focusController.focus(0, 0);
  const rect = $("#canvas").getBoundingClientRect();
  if (!rect.width || !rect.height) return;
  const point = model.toModelPosition(new PIXI.Point(
    (x * innerWidth - rect.left) * view.app.screen.width / rect.width,
    (y * innerHeight - rect.top) * view.app.screen.height / rect.height));
  internal.focusController.focus(point.x * 2 / internal.originalWidth - 1,
    1 - point.y * 2 / internal.originalHeight);
};
window.addEventListener("scene-editor-change", () => {
  showPetMenu(false);
  updateControlsVisibility();
});
