"use strict";

(() => {
  if (pet) return;
  const key = "wallpaper-scene-v1";
  const materialPlacement = { scale: 1, x: 0, y: 0, rotation: 0 };
  const characterPlacement = { ...materialPlacement, scale: .82, y: -.04 };
  const starOpacity = .65;
  const base = () => ({ id: "background", kind: "background", name: "默认背景", color: "default", opacity: 1, visible: true, ...materialPlacement });
  const isBase = layer => layer.id === "background";
  const defaults = () => [
    base(),
    { id: "character", kind: "character", name: "Live2D 角色", opacity: 1, visible: true, ...characterPlacement },
    { id: "sparkles", kind: "effect", name: "星光", effect: "stars", opacity: starOpacity, visible: true, ...materialPlacement },
  ];
  function wrapRotation(value) {
    const angle = value % 360;
    return angle > 180 ? angle - 360 : angle < -180 ? angle + 360 : angle || 0;
  }
  const palettes = {
    default: ["原色", "var(--scene-background)"],
    dusk: ["暮色", "radial-gradient(ellipse at 16% 13%,#d3abce 0%,transparent 58%),linear-gradient(160deg,#4b456d,#916786 58%,#f1c5be)"],
    rose: ["樱色", "radial-gradient(ellipse at 70% 10%,#fff7e7,transparent 60%),linear-gradient(155deg,#f5d6e6,#f6e9f2 48%,#dcbbdb)"],
    night: ["夜空", "radial-gradient(ellipse at 85% 8%,#56517f,transparent 60%),linear-gradient(170deg,#171d3e,#3f355e 63%,#966683)"],
    morning: ["晨曦", "linear-gradient(155deg,#fdf1d7,#ffd2bb 55%,#e5adbc)"],
    peach: ["蜜桃", "radial-gradient(ellipse at 66% 13%,#ffeed5,transparent 65%),linear-gradient(145deg,#f4b4ae,#e99aaf)"],
    violet: ["藤紫", "linear-gradient(165deg,#f5eaff,#d5c4f0 52%,#a795d4)"],
    coast: ["海风", "linear-gradient(164deg,#ecf8ff,#a7d9ed 55%,#8baed8)"],
    mint: ["薄荷", "radial-gradient(ellipse at 80% 10%,#f6f9d8,transparent 70%),linear-gradient(150deg,#daf3df,#83c8bb)"],
    forest: ["青森", "linear-gradient(155deg,#152d29,#325a4a 60%,#86a37f)"],
    amber: ["琥珀", "radial-gradient(ellipse at 30% 15%,#fff0b8,transparent 65%),linear-gradient(160deg,#eab56d,#c27a5e)"],
    slate: ["月灰", "linear-gradient(150deg,#f3f1f3,#c6cad4 50%,#8792a5)"],
    stage: ["舞台", "radial-gradient(ellipse at 65% 18%,#bd54a9,transparent 48%),linear-gradient(160deg,#231e49,#583b81 63%,#384a82)"],
  };
  const effectNames = { stars: "星光", meteors: "流星", constellation: "星座", petals: "落樱", snow: "落雪", rain: "细雨", fireflies: "萤火", butterflies: "蝴蝶", bubbles: "泡泡", confetti: "彩纸", bokeh: "柔光", notes: "音符" };
  // Always restore the original, opaque bottom layer, including older saved scenes.
  function normalize(saved) {
    if (!Array.isArray(saved) || saved.length > 12 || saved.filter(layer => layer?.kind === "character").length !== 1) return defaults();
    const ids = new Set(["background"]);
    const content = saved.filter(layer => {
      if (!layer || ids.has(layer.id) || !["character", "background", "image", "video", "effect"].includes(layer.kind)) return false;
      if (layer.kind === "effect" && !effectNames[layer.effect]) return false;
      ids.add(layer.id); return true;
    });
    // Reserve room for the character if a scene from an older build omitted the base.
    if (content.length > 11) content.splice(content.findIndex(layer => layer.kind !== "character"), 1);
    return [base(), ...content.map(layer => ({
      ...layer, rotation: Number.isFinite(layer.rotation) ? wrapRotation(layer.rotation) : 0,
    }))];
  }
  let layers, draftSettings = null, editorDraft, savedInEditor;
  let returningFromPreset = false;
  try {
    const saved = JSON.parse(wallpaper ? native.desktopSetting(key) : localStorage.getItem(key));
    layers = normalize(Array.isArray(saved) ? saved : saved?.scene);
  } catch { layers = defaults(); }
  let selected = "character", visible = true, engine;
  const nodes = new Map(), urls = new Map();
  const effects = new Map();
  const identifier = () => Date.now().toString(36) + "-" + Math.random().toString(36).slice(2);
  const host = $("#scene-layers");
  const tiltToggle = $("#scene-tilt");
  const gazeToggle = $("#scene-gaze");
  function updateTiltPreview() {
    if (wallpaper) return;
    const classes = document.documentElement.classList;
    native.setWallpaperTiltPreview(!document.hidden && !!view.model &&
      (classes.contains("scene-preview") || (classes.contains("immersive-preview") && !classes.contains("scene-edit"))), tiltToggle.checked);
  }
  function shiftBackground(x, y) {
    host.style.setProperty("--wallpaper-shift-x", (x * 2).toFixed(3) + "%");
    host.style.setProperty("--wallpaper-shift-y", (y * 2).toFixed(3) + "%");
  }
  function setTiltActive(active) {
    host.classList.toggle("tilt-background", active);
    if (!active) shiftBackground(0, 0);
  }
  const database = !wallpaper && new Promise((resolve, reject) => {
    const request = indexedDB.open("wallpaper-media", 1);
    request.onupgradeneeded = () => request.result.createObjectStore("files");
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
  function media(id, value) {
    if (wallpaper) return fetch("/wallpaper-media/" + encodeURIComponent(id)).then(res => res.ok ? res.blob() : null);
    return database.then(db => new Promise((resolve, reject) => {
      const tx = db.transaction("files", value ? "readwrite" : "readonly");
      const store = tx.objectStore("files");
      const request = value ? store.put(value, id) : store.get(id);
      tx.oncomplete = () => resolve(request.result);
      tx.onabort = tx.onerror = () => reject(tx.error || Error("素材保存失败"));
    }));
  }
  function removeMedia(id) {
    native.removeWallpaperMedia(id);
    return database.then(db => new Promise((resolve, reject) => {
      const tx = db.transaction("files", "readwrite");
      tx.objectStore("files").delete(id);
      tx.oncomplete = resolve;
      tx.onabort = tx.onerror = () => reject(tx.error);
    }));
  }
  function releaseUnusedMedia(ids) {
    for (const id of ids) {
      if (!id || layers.some(layer => layer.media === id) || editorDraft?.layers.some(layer => layer.media === id)
          || window.savedWallpaperUsesMedia?.(id)) continue;
      removeMedia(id).catch(console.error);
    }
  }
  async function missingMedia(scene) {
    const files = await Promise.all(scene.filter(layer => layer.media).map(async layer =>
      await media(layer.media) instanceof Blob ? null : layer.name));
    return files.filter(Boolean);
  }
  function openSavedConfiguration(scene, settings, preset) {
    const previous = layers;
    const initial = settings ? null : captureConfiguration();
    const options = settings || initial.settings;
    editorDraft ??= {layers, selected, returnToEditor: !$("#scene-editor").hidden};
    editorDraft.preset = preset ? {id: preset.id, snapshot: preset.snapshot || {...initial, scene: normalize(scene)}} : null;
    savedInEditor = undefined;
    layers = normalize(scene);
    draftSettings = {...options};
    syncConfiguration();
    selected = "character";
    releaseUnusedMedia(previous.map(layer => layer.media));
    document.documentElement.classList.add("scene-saved-config");
    stage.inert = true;
    cancelPreviewTouch();
    openEditor();
    setScenePreview(true);
  }
  function message(text = "") {
    $("#scene-message").textContent = text;
    $("#scene-message").hidden = !text;
  }
  function sceneFailure(text) {
    if ($("#scene-message").textContent !== text) native.reportRuntimeFailure?.("scene");
    message(text);
  }
  function save() {
    if (editorDraft) return true;
    try { localStorage.setItem(key, JSON.stringify(layers)); }
    catch { sceneFailure("场景保存失败，请检查可用存储空间。"); return false; }
    window.dispatchEvent(new Event("wallpaper-scene-saved"));
    return true;
  }

  async function copyMedia(id) {
    if (native.hasWallpaperMedia(id)) return;
    const blob = await media(id);
    if (!blob || !native.beginWallpaperMedia(id, blob.type, blob.size)) throw new Error("无法准备壁纸素材，请检查文件和可用空间。");
    const chunkSize = 192 * 1024;
    try {
      for (let offset = 0; offset < blob.size; offset += chunkSize) {
        const data = new Uint8Array(await blob.slice(offset, offset + chunkSize).arrayBuffer());
        let text = "";
        for (let index = 0; index < data.length; index += 4096) text += String.fromCharCode(...data.subarray(index, index + 4096));
        if (!native.appendWallpaperMedia(btoa(text))) throw new Error("无法写入壁纸素材，请检查可用空间。");
      }
      if (!native.finishWallpaperMedia()) throw new Error("无法保存壁纸素材。");
    } catch (error) {
      native.finishWallpaperMedia();
      throw error;
    }
  }

  function captureConfiguration() {
    if (!view.model || !view.costume || !$("#loading").hidden) throw new Error("请等待模型加载完成。");
    const config = native.captureWallpaperConfig(view.session.version, view.costume.id, view.viewerOutfit || "", JSON.stringify(layers),
      draftSettings ? JSON.stringify(draftSettings) : "");
    if (!config) throw new Error("无法保存壁纸配置，请检查模型和可用空间。");
    return JSON.parse(config);
  }

  async function cacheConfigurationMedia(config) {
    for (const layer of config.scene) if (layer.media) await copyMedia(layer.media);
  }

  async function prepareWallpaper(config) {
    try {
      const saved = savedInEditor && JSON.stringify(savedInEditor.snapshot) === JSON.stringify(config)
        ? savedInEditor : editorDraft?.preset;
      await cacheConfigurationMedia(config);
      if (saved) await cacheConfigurationMedia(saved.snapshot);
      const preset = saved ? JSON.stringify({...saved.snapshot, presetId: saved.id}) : "";
      if (!native.prepareWallpaper(JSON.stringify(config), preset)) throw new Error("无法准备壁纸配置，请重试。");
    } catch (error) {
      native.reportRuntimeFailure?.("scene-publish");
      throw error;
    }
  }
  function applyNode(layer, node, index, panelPreview = false) {
    node.style.zIndex = index + 1;
    node.style.opacity = panelPreview && layer.kind === "character" ? 1 : layer.visible ? layer.opacity : 0;
    if (layer.kind === "character") {
      node.style.pointerEvents = panelPreview || (layer.visible && layer.opacity > 0) ? "auto" : "none";
      setSceneCharacterTransform(layer);
      return;
    }
    node.classList.toggle("scene-tilt", layer.kind !== "effect");
    node.style.transform = "translate(" + layer.x * 100 + "%," + layer.y * 100 + "%) rotate(" + layer.rotation + "deg) scale(" + layer.scale + ")";
  }
  function particleOptions(type) {
    const particles = {
      number: { value: 28, density: { enable: false } }, color: { value: "#d38bb0" },
      shape: { type: "circle" }, opacity: { value: { min: .25, max: .85 }, animation: { enable: true, speed: .5, sync: false } },
      size: { value: { min: 1, max: 3 } }, move: { enable: true, speed: .5, direction: "none", outModes: { default: "out" } },
      links: { enable: false }, collisions: { enable: false },
    };
    if (type === "stars") {
      particles.shape = { type: "star", options: { star: { sides: 4 } } };
      particles.color.value = ["#ce82a7", "#bd9a55", "#ffffff"];
      particles.size.value = { min: 2, max: 5 };
      particles.move.speed = .18;
    } else if (type === "petals") {
      particles.shape = { type: "image", options: { image: { src: "/petal.svg", width: 20, height: 14 } } };
      particles.size.value = { min: 4, max: 8 };
      particles.move = { enable: true, speed: .8, direction: "bottom-left", outModes: { default: "out" } };
      particles.rotate = { value: { min: 0, max: 360 }, animation: { enable: true, speed: 3, sync: false } };
    } else if (type === "snow") {
      particles.number.value = 42; particles.color.value = ["#ffffff", "#d2dbe9"];
      particles.move = { enable: true, speed: .6, direction: "bottom", outModes: { default: "out" } };
    } else if (type === "rain") {
      particles.number.value = 48; particles.color.value = "#8faac7";
      particles.shape.type = "line"; particles.size.value = { min: 6, max: 12 };
      particles.stroke = { width: 1, color: { value: "#8faac7" } };
      particles.rotate = { value: 105 };
      particles.opacity = { value: .5 };
      particles.move = { enable: true, speed: 7, direction: "bottom-left", straight: true, outModes: { default: "out" } };
    } else if (type === "bubbles") {
      particles.number.value = 18; particles.color.value = ["#b9a3d6", "#96bdce", "#dc9bb9"];
      particles.stroke = { width: 1, color: { value: ["#b9a3d6", "#96bdce", "#dc9bb9"] } };
      particles.size.value = { min: 7, max: 19 };
      particles.move = { enable: true, speed: .5, direction: "top", outModes: { default: "out" } };
    } else if (type === "fireflies") {
      particles.number.value = 22; particles.color.value = ["#b4cf77", "#e0c05c"];
      particles.move.speed = .35;
    } else if (type === "confetti") {
      particles.number.value = 34; particles.shape.type = "square";
      particles.color.value = ["#e28aaf", "#a38bc5", "#86bfc7", "#e7c566"];
      particles.size.value = { min: 2, max: 5 };
      particles.rotate = { value: { min: 0, max: 360 }, animation: { enable: true, speed: 5, sync: false } };
      particles.move = { enable: true, speed: 1, direction: "bottom", outModes: { default: "out" } };
    } else if (type === "meteors") {
      particles.number.value = 9;
      particles.shape.type = "line"; particles.size.value = { min: 12, max: 25 };
      particles.stroke = { width: 1.5, color: { value: ["#bda7df", "#d797b8", "#ffffff"] } };
      particles.rotate = { value: 135 };
      particles.move = { enable: true, speed: 5, direction: "bottom-left", straight: true, outModes: { default: "out" } };
    } else if (type === "constellation") {
      particles.number.value = 30;
      particles.color.value = ["#b294c9", "#d19cb5"];
      particles.size.value = { min: 1, max: 2 };
      particles.move.speed = .2;
      particles.links = { enable: true, distance: 110, color: "#b29bc5", opacity: .35, width: .7 };
    } else if (type === "bokeh") {
      particles.number.value = 13;
      particles.shape = { type: "image", options: { image: { src: "/bokeh.svg", width: 64, height: 64 } } };
      particles.size.value = { min: 14, max: 35 };
      particles.move.speed = .2;
    } else if (type === "butterflies" || type === "notes") {
      particles.number.value = type === "butterflies" ? 12 : 18;
      particles.shape = { type: "image", options: { image: { src: type === "butterflies" ? "/butterfly.svg" : "/note.svg", width: 32, height: 32 } } };
      particles.size.value = { min: 5, max: type === "butterflies" ? 11 : 9 };
      particles.rotate = { value: { min: -25, max: 25 }, animation: { enable: true, speed: .8, sync: false } };
      particles.move = { enable: true, speed: .45, direction: "top", outModes: { default: "out" } };
    }
    particles.shape.fill = !["bubbles", "rain", "meteors"].includes(type);
    return { autoPlay: false, fullScreen: { enable: false }, detectRetina: false, fpsLimit: 30,
      pauseOnBlur: false, pauseOnOutsideViewport: false,
      interactivity: { events: { onClick: { enable: false }, onHover: { enable: false }, resize: { enable: true } } }, particles };
  }
  function syncEffect(layer) {
    const effect = effects.get(layer.id);
    if (!effect) return;
    if (visible && !document.hidden && layer.visible && layer.opacity > 0) effect.play();
    else effect.pause();
  }
  async function makeParticles(node, layer) {
    try {
      // Lazy-load once; no CDN or network access is needed on the phone.
      engine ||= (async () => {
        for (const src of ["/tsparticles.engine.min.js", "/tsparticles.slim.bundle.min.js"]) {
          await new Promise((resolve, reject) => {
            const script = document.createElement("script"); script.src = src;
            script.onload = resolve; script.onerror = reject; document.head.append(script);
          });
        }
        await loadSlim(tsParticles);
        return tsParticles;
      })();
      const particles = await engine;
      if (nodes.get(layer.id) !== node) return;
      const effect = await particles.load({ id: node.id, element: node, options: particleOptions(layer.effect) });
      if (nodes.get(layer.id) !== node) { effect?.destroy(); return; }
      effects.set(layer.id, effect);
      const current = layers.find(item => item.id === layer.id);
      if (current) syncEffect(current);
    } catch (error) {
      if (nodes.get(layer.id) !== node) return;
      console.error("Particle layer failed", error);
      sceneFailure("特效加载失败，请重新打开应用。");
    }
  }
  function render() {
    const alive = new Set(layers.map(layer => layer.id));
    for (const [id, node] of nodes) {
      if (alive.has(id)) continue;
      if (node instanceof HTMLVideoElement) { node.pause(); node.removeAttribute("src"); node.load(); }
      effects.get(id)?.destroy(); effects.delete(id);
      node.remove(); nodes.delete(id);
      if (urls.has(id)) { URL.revokeObjectURL(urls.get(id)); urls.delete(id); }
    }
    const panelPreview = !wallpaper && !document.documentElement.classList.contains("immersive-preview");
    // Keep scene materials behind the character on the model panel.
    const drawOrder = panelPreview ? [
      ...layers.filter(layer => layer.kind !== "character" && layer.kind !== "effect"),
      ...layers.filter(layer => layer.kind === "character" || layer.kind === "effect"),
    ] : layers;
    drawOrder.forEach((layer, index) => {
      if (layer.kind === "character") { applyNode(layer, $("#canvas"), index, panelPreview); return; }
      let node = nodes.get(layer.id);
      if (!node) {
        node = document.createElement(layer.kind === "video" ? "video" : layer.kind === "image" ? "img" : "div");
        node.className = "scene-layer";
        nodes.set(layer.id, node);
        host.append(node);
        if (layer.kind === "effect") {
          node.id = "particles-" + layer.id;
          makeParticles(node, layer);
        }
        if (layer.kind === "video") {
          node.muted = true; node.loop = true; node.playsInline = true;
        }
        if (layer.media) {
          const unavailable = () => {
            if (nodes.get(layer.id) === node) sceneFailure("图片或视频读取失败，请重新添加。");
          };
          node.onerror = unavailable;
          media(layer.media).then(blob => {
            if (!blob) return unavailable();
            if (nodes.get(layer.id) !== node) return;
            const url = URL.createObjectURL(blob);
            urls.set(layer.id, url); node.src = url;
            if (layer.kind === "video" && visible && layer.visible) node.play().catch(() => {
              if (nodes.get(layer.id) === node && node.error) sceneFailure("此视频格式暂时无法播放。");
            });
          }).catch(unavailable);
        }
      }
      if (layer.kind === "background") node.style.background = palettes[layer.color]?.[1] || palettes.default[1];
      applyNode(layer, node, index);
      if (layer.kind === "effect") syncEffect(layer);
      if (layer.kind === "video") {
        if (visible && layer.visible) node.play().catch(() => {});
        else node.pause();
      }
    });
  }
  function setVisible(show) {
    if (visible === show) return;
    visible = show;
    render();
  }
  function current() { return layers.find(layer => layer.id === selected) || layers[0]; }
  function editSelected() {
    const layer = current();
    $("#scene-selected").textContent = layer.name;
    for (const [name, value] of [["opacity", layer.opacity * 100], ["scale", layer.scale * 100], ["x", layer.x * 100], ["y", layer.y * 100]]) {
      $("#scene-" + name).value = Math.round(value);
    }
    $("#scene-opacity-value").textContent = Math.round(layer.opacity * 100) + "%";
    $("#scene-scale-value").textContent = Math.round(layer.scale * 100) + "%";
    $("#scene-rotation").value = Math.round(layer.rotation);
    $("#scene-rotation-value").textContent = Math.round(layer.rotation) + "°";
    $("#scene-rotation-row").hidden = $("#scene-scale-row").hidden = $(".scene-position").hidden = layer.kind === "background";
    $("#scene-opacity-row").hidden = isBase(layer);
    $("#scene-base-note").hidden = !isBase(layer);
  }
  function drawStack() {
    const fragment = document.createDocumentFragment();
    [...layers].reverse().forEach(layer => {
      const row = document.createElement("li");
      row.className = layer.id === selected ? "selected" : "";
      const visibility = button(layer.visible ? "●" : "○", "layer-eye", () => {
        layer.visible = !layer.visible; update();
      });
      visibility.setAttribute("aria-label", (layer.visible ? "隐藏" : "显示") + layer.name);
      visibility.disabled = isBase(layer);
      if (isBase(layer)) visibility.setAttribute("aria-label", "默认背景固定保留");
      const choose = button(layer.name, "layer-select", () => { selected = layer.id; $("#scene-adjust-section").open = true; drawStack(); });
      choose.setAttribute("aria-pressed", String(layer.id === selected));
      row.append(visibility, choose);
      for (const [label, direction] of [["↑", 1], ["↓", -1]]) {
        const index = layers.indexOf(layer);
        const move = button(label, "layer-order", () => {
          [layers[index], layers[index + direction]] = [layers[index + direction], layers[index]];
          update();
        });
        move.disabled = isBase(layer) || index + direction < 1 || index + direction >= layers.length;
        move.setAttribute("aria-label", (direction === 1 ? "上移" : "下移") + layer.name);
        row.append(move);
      }
      if (layer.kind !== "character" && !isBase(layer)) {
        const remove = button("×", "layer-remove", () => {
          layers = layers.filter(item => item.id !== layer.id);
          if (selected === layer.id) selected = "character";
          update();
          releaseUnusedMedia([layer.media]);
        });
        remove.setAttribute("aria-label", "删除" + layer.name); row.append(remove);
      }
      fragment.append(row);
    });
    $("#scene-stack").textContent = "";
    $("#scene-stack").append(fragment);
    editSelected();
  }
  function update() { save(); render(); drawStack(); }
  function syncConfiguration() {
    tiltToggle.disabled = !native.hasWallpaperTilt();
    tiltToggle.checked = !tiltToggle.disabled && (draftSettings ? draftSettings["wallpaper-tilt"] === "true" : native.wallpaperTiltEnabled());
    gazeToggle.checked = draftSettings ? draftSettings["wallpaper-gaze"] !== "false" : native.wallpaperGazeEnabled();
  }
  function changeConfiguration(toggle, saveDefault, failure) {
    if (editorDraft) {
      syncConfiguration();
      return;
    }
    if (!saveDefault(toggle.checked)) {
      syncConfiguration();
      sceneFailure(failure);
    }
    updateTiltPreview();
  }
  function openEditor() {
    if (!view.model) return;
    settings.close();
    document.documentElement.classList.add("scene-edit");
    $("#scene-editor").hidden = false;
    window.refreshAppTheme?.();
    window.dispatchEvent(new Event("scene-editor-change"));
    render();
    drawStack();
  }
  $("#edit-scene").onclick = openEditor;
  if (!wallpaper) {
    const opacity = $("#scene-panel-opacity");
    const opacityValue = $("#scene-panel-opacity-value");
    const editor = $("#scene-editor");
    const transparencyKey = "wallpaper-editor-transparency";
    try {
      const savedTransparency = Number(localStorage.getItem(transparencyKey));
      if (Number.isFinite(savedTransparency)) opacity.value = 100 - savedTransparency;
    } catch { /* The editor also works without saved preferences. */ }
    function updatePanelOpacity() {
      editor.style.setProperty("--scene-panel-fill", opacity.valueAsNumber / 100);
      opacityValue.value = opacity.value + "%";
    }
    opacity.oninput = updatePanelOpacity;
    opacity.onchange = () => {
      try { localStorage.setItem(transparencyKey, 100 - opacity.valueAsNumber); }
      catch { /* Keep the current value for this session. */ }
    };
    updatePanelOpacity();
    syncConfiguration();
    tiltToggle.onchange = () => changeConfiguration(tiltToggle, enabled => native.setWallpaperTiltEnabled(enabled), "无法保存背景微动设置。");
    gazeToggle.onchange = () => changeConfiguration(gazeToggle, enabled => native.setWallpaperGazeEnabled(enabled), "无法保存视线跟随设置。");
  }
  async function closeEditor(keepEditor = false, animate = false) {
    if (returningFromPreset) return;
    const root = document.documentElement;
    const draft = editorDraft;
    const present = (update) => animate ? transitionPanel(update, waitForSceneFrame) : update();
    const finish = () => {
      closeEditorView(root, keepEditor);
      if (animate && draft && $("#scene-editor").hidden) $("#scene-open-presets").click();
    };
    if (wallpaperEditorPreset) {
      returningFromPreset = true;
      root.classList.add("scene-returning");
      showPetMenu(false);
      wallpaperEditorPreset = undefined;
      let shown = false;
      const swap = (showModel) => present(() => {
        if (showModel() === false) return;
        finish();
        shown = true;
      });
      try {
        if (!await refreshModel(swap) && !shown) await swap(clearPlayer);
      } finally {
        returningFromPreset = false;
        root.classList.remove("scene-returning");
      }
    } else {
      await present(finish);
    }
  }
  function closeEditorView(root, keepEditor) {
    const showEditor = keepEditor && Boolean(view.model);
    $("#scene-choices").hidden = true;
    savedInEditor = undefined;
    root.classList.remove("scene-preview", "scene-saved-config", "scene-returning");
    root.classList.toggle("scene-edit", showEditor);
    $("#scene-editor").hidden = !showEditor;
    stage.inert = false;
    $("#scene-preview").setAttribute("aria-pressed", "false");
    $("#scene-preview").textContent = "预览";
    if (editorDraft) {
      const previous = layers;
      ({layers, selected} = editorDraft);
      editorDraft = undefined;
      draftSettings = null;
      releaseUnusedMedia(previous.map(layer => layer.media));
      syncConfiguration();
    }
    window.refreshAppTheme?.();
    window.dispatchEvent(new Event("scene-editor-change"));
    render();
    if (showEditor) drawStack();
    view.app?.syncCanvas();
  }
  $("#scene-back").onclick = async () => {
    if (returningFromPreset || wallpaperChanging) return;
    await closeEditor(editorDraft?.returnToEditor, true);
  };
  function setScenePreview(on) {
    document.documentElement.classList.toggle("scene-preview", on);
    $("#scene-preview").textContent = on ? "编辑" : "预览";
    $("#scene-preview").setAttribute("aria-pressed", String(on));
    if (!on) showPetMenu(false);
    updateTiltPreview();
  }
  $("#scene-preview").onclick = () => {
    if (!editorDraft) setScenePreview(!document.documentElement.classList.contains("scene-preview"));
  };
  for (const name of ["opacity", "scale", "x", "y"]) {
    $("#scene-" + name).oninput = (event) => {
      if (editorDraft || isBase(current())) return;
      current()[name] = event.target.valueAsNumber / 100;
      render(); editSelected();
    };
    $("#scene-" + name).onchange = save;
  }

  function sliderRotation(angle, drag, now) {
    const alignment = Math.floor(angle / 90 + .5) * 90;
    const distance = Math.abs(angle - alignment);
    const crossed = (drag.previous < alignment && angle > alignment) || (drag.previous > alignment && angle < alignment);
    drag.previous = angle;
    if (drag.alignment !== undefined && Math.abs(angle - drag.alignment) > 12) drag.alignment = undefined;
    if (drag.alignment === undefined && (distance <= 4 || (crossed && distance <= 12))) {
      drag.alignment = alignment;
      drag.holdUntil = now + 300;
    }
    return drag.alignment !== undefined && now < drag.holdUntil ? drag.alignment : angle;
  }

  const rotationSlider = $("#scene-rotation");
  let rotationDrag;
  rotationSlider.addEventListener("pointerdown", event => {
    if (event.isPrimary && event.button === 0) rotationDrag = {id: event.pointerId, previous: rotationSlider.valueAsNumber};
  });
  function endRotationDrag(event) {
    if (rotationDrag?.id === event.pointerId) rotationDrag = undefined;
  }
  window.addEventListener("pointerup", endRotationDrag);
  window.addEventListener("pointercancel", endRotationDrag);
  rotationSlider.addEventListener("lostpointercapture", endRotationDrag);
  rotationSlider.addEventListener("keydown", () => { rotationDrag = undefined; });
  rotationSlider.oninput = () => {
    const layer = current();
    if (editorDraft || isBase(layer)) return;
    const angle = rotationSlider.valueAsNumber;
    const value = rotationDrag ? sliderRotation(angle, rotationDrag, performance.now()) : angle;
    if (layer.rotation === value) {
      rotationSlider.value = value;
      return;
    }
    layer.rotation = value;
    render(); editSelected();
  };
  rotationSlider.onchange = save;

  function addEffect(effect, name) {
    if (layers.length >= 12) return message("最多可放置 12 个图层。");
    if (layers.filter(layer => layer.kind === "effect").length >= 3) return message("最多同时使用 3 层特效，减少发热与耗电。");
    const layer = { id: identifier(), kind: "effect", effect, name, opacity: effect === "stars" ? starOpacity : .8,
      visible: true, ...materialPlacement };
    layers.push(layer);
    selected = layer.id; $("#scene-choices").hidden = true; update(); message();
  }
  $("#scene-add-effect").onclick = () => {
    const choices = $("#scene-choices");
    choices.hidden = !choices.hidden;
    if (choices.hidden) return;
    $("#scene-choices-title").textContent = "选择特效";
    const grid = $("#scene-choice-grid");
    const content = document.createElement("div");
    content.className = "scene-choice-grid";
    for (const [effect, name] of Object.entries(effectNames)) {
      const choice = button(name, "scene-choice", () => addEffect(effect, name));
      choice.dataset.type = effect;
      content.append(choice);
    }
    grid.replaceChildren(content);
    choices.scrollIntoView({ block: "nearest", behavior: matchMedia("(prefers-reduced-motion: reduce)").matches ? "auto" : "smooth" });
  };
  // Keep the framebuffer size steady during gestures.
  const stage = $("#stage"), fingers = new Map();
  let gesture, threeFinger, previousThreeTap, firstPressedAt;
  let moving = false, changed = false, tapMoved = false, suppressClick = false;
  function centerOf(points) {
    const center = { x: 0, y: 0 };
    for (const point of points) {
      center.x += point.x / points.length;
      center.y += point.y / points.length;
    }
    return center;
  }
  function beginGesture() {
    const points = [...fingers.values()];
    if (!points.length) { gesture = null; return; }
    const inEditor = document.documentElement.classList.contains("scene-edit");
    const layer = gesture?.layer || (inEditor && !isBase(current()) ? current() : layers.find(item => item.kind === "character"));
    gesture = { layer, x: layer.x, y: layer.y, scale: layer.scale, bounds: stage.getBoundingClientRect(),
      center: centerOf(points),
      distance: points.length === 2 ? Math.hypot(points[0].x - points[1].x, points[0].y - points[1].y) : 0 };
  }
  function paintGestureLayer(layer) {
    changed = true;
    const node = layer.kind === "character" ? $("#canvas") : nodes.get(layer.id);
    if (node) applyNode(layer, node, layers.indexOf(layer));
    if (layer === current() && !$("#scene-editor").hidden) editSelected();
  }
  function captureGesture() {
    moving = suppressClick = true;
    cancelPreviewTouch();
    for (const id of fingers.keys()) stage.setPointerCapture(id);
  }
  function rotateGesture() {
    const chord = threeFinger;
    if (!chord.frame) return;
    cancelAnimationFrame(chord.frame);
    chord.frame = 0;
    if (chord.released || chord.canceled || !layers.includes(chord.layer)) return;
    const points = [...fingers.values()];
    const center = centerOf(points);
    let cross = 0, dot = 0;
    for (const [index, point] of points.entries()) {
      const x = chord.points[index].x - chord.center.x, y = chord.points[index].y - chord.center.y;
      const nextX = point.x - center.x, nextY = point.y - center.y;
      cross += x * nextY - y * nextX;
      dot += x * nextX + y * nextY;
    }
    if (Math.hypot(dot, cross) < 16) return;
    const delta = Math.atan2(cross, dot) * 180 / Math.PI;
    if (!chord.rotating && Math.abs(delta) < 4) return;
    chord.rotating = true;
    chord.layer.rotation = wrapRotation(chord.rotation + delta);
    paintGestureLayer(chord.layer);
  }
  stage.addEventListener("pointerdown", event => {
    const root = document.documentElement;
    const editing = root.classList.contains("scene-edit") && !root.classList.contains("scene-preview");
    if (wallpaper || editorDraft || !root.classList.contains("immersive-preview") || !view.model || !$("#loading").hidden
        || (!fingers.size && ((editing && isBase(current())) || event.target.closest("#caption, #pet-menu, button")))) return;
    if (event.pointerType === "mouse" && event.button !== 0) return;
    if (!fingers.size) {
      changed = tapMoved = suppressClick = false;
      firstPressedAt = event.timeStamp;
    }
    fingers.set(event.pointerId, { x: event.clientX, y: event.clientY, downX: event.clientX, downY: event.clientY });
    if (threeFinger) {
      threeFinger.canceled = true;
    } else if (fingers.size === 3) {
      const points = [...fingers.values()];
      threeFinger = { layer: gesture.layer, rotation: gesture.layer.rotation, points, center: centerOf(points),
        joinedInTime: event.timeStamp - firstPressedAt <= 200, frame: 0 };
    } else beginGesture();
    if (fingers.size >= 2) {
      if (gesture.layer.kind === "character") view.app?.setScenePinching(true);
      captureGesture();
      event.stopPropagation();
    }
  }, { capture: true });
  stage.addEventListener("pointermove", event => {
    const finger = fingers.get(event.pointerId);
    if (!finger || !gesture) return;
    const point = { ...finger, x: event.clientX, y: event.clientY };
    fingers.set(event.pointerId, point);
    if (Math.hypot(point.x - point.downX, point.y - point.downY) > 12) tapMoved = true;
    if (threeFinger) {
      event.preventDefault();
      event.stopPropagation();
      if (!threeFinger.frame && !threeFinger.released && !threeFinger.canceled) {
        threeFinger.frame = requestAnimationFrame(rotateGesture);
      }
      return;
    }
    const { layer, bounds } = gesture;
    if (!layers.includes(layer)) return;
    const points = [...fingers.values()];
    const center = centerOf(points);
    if (!moving) {
      if (Math.hypot(center.x - gesture.center.x, center.y - gesture.center.y) < 8) return;
      captureGesture();
    }
    event.preventDefault();
    event.stopPropagation();
    const distance = points.length === 2 ? Math.hypot(points[0].x - points[1].x, points[0].y - points[1].y) : 0;
    const clamp = (value, min, max) => Math.max(min, Math.min(max, value));
    layer.scale = clamp(gesture.scale * (gesture.distance > 0 ? distance / gesture.distance : 1), .3, 3);
    const ratio = layer.scale / gesture.scale;
    layer.x = clamp((center.x - bounds.left - bounds.width / 2 - (gesture.center.x - bounds.left - bounds.width / 2 - gesture.x * bounds.width) * ratio) / bounds.width, -.8, .8);
    layer.y = clamp((center.y - bounds.top - bounds.height / 2 - (gesture.center.y - bounds.top - bounds.height / 2 - gesture.y * bounds.height) * ratio) / bounds.height, -.8, .8);
    paintGestureLayer(layer);
  }, { capture: true });
  function endGesture(event) {
    if (event.type === "lostpointercapture" && event.target !== stage) return;
    const finger = fingers.get(event.pointerId);
    if (!finger) return;
    if (threeFinger) {
      if (event.type !== "pointerup") threeFinger.canceled = true;
      if (Math.hypot(event.clientX - finger.downX, event.clientY - finger.downY) > 12) tapMoved = true;
      rotateGesture();
      threeFinger.released = true;
    }
    fingers.delete(event.pointerId);
    if (fingers.size) {
      if (fingers.size === 2 && threeFinger?.rotating && !threeFinger.canceled) {
        // Keep the current rotation and rebase the pinch to the two remaining fingers.
        threeFinger = previousThreeTap = null;
      }
      if (!threeFinger) beginGesture();
      return;
    }
    const chord = threeFinger;
    if (chord && chord.joinedInTime && !chord.canceled && !chord.rotating && !tapMoved &&
        event.timeStamp - firstPressedAt <= 290 && layers.includes(chord.layer)) {
      const { layer, center } = chord;
      const last = previousThreeTap;
      if (last?.layer === layer && firstPressedAt - last.time <= 360 &&
          Math.hypot(center.x - last.center.x, center.y - last.center.y) <= 44) {
        Object.assign(layer, layer.kind === "character" ? characterPlacement : materialPlacement);
        paintGestureLayer(layer);
        previousThreeTap = null;
      } else previousThreeTap = { layer, center, time: event.timeStamp };
    } else previousThreeTap = null;
    gesture = threeFinger = null;
    if (moving) view.app?.setScenePinching(false);
    moving = false;
    if (changed) save();
  }
  window.addEventListener("pointerup", endGesture, { capture: true });
  window.addEventListener("pointercancel", endGesture, { capture: true });
  stage.addEventListener("lostpointercapture", endGesture, { capture: true });
  for (const type of ["image", "video"]) {
    const picker = $("#scene-" + type + "-file");
    $("#scene-add-" + type).onclick = () => picker.click();
    picker.onchange = (event) => importMedia(event, type);
  }
  async function importMedia(event, type) {
    const file = event.target.files[0];
    event.target.value = "";
    if (!file) return;
    if (layers.length >= 12) return message("最多可放置 12 个图层。");
    const supported = type === "video" ? file.type === "video/mp4" : ["image/png", "image/jpeg", "image/webp"].includes(file.type);
    if (!supported) return message(type === "video" ? "请选择 MP4 视频。" : "请选择 PNG、JPG 或 WebP 图片。");
    if (file.size > 64 * 1024 * 1024) return message("单个素材请控制在 64 MB 以内。");
    const id = identifier();
    try {
      const contents = new Blob([await file.arrayBuffer()], {type: file.type});
      await media(id, contents);
      const layer = { id, media: id, kind: file.type.startsWith("video/") ? "video" : "image", name: file.name, opacity: 1, visible: true, ...materialPlacement };
      layers.splice(Math.max(1, layers.findIndex(item => item.kind === "character")), 0, layer);
      selected = id; update(); message();
    } catch (error) {
      console.error("Scene media import failed", error);
      sceneFailure("素材读取或保存失败，请重新选择文件。");
    }
  }
  async function changeWallpaper(exit, fromEditor = false) {
    if (wallpaper || wallpaperChanging || busy || returningFromPreset || (!exit && !view.model)) return;
    if (!exit && !save()) return showAppNotice("壁纸场景保存失败，请检查可用存储空间。");
    wallpaperChanging = true;
    updateWallpaperButton();
    try {
      if (!exit) await prepareWallpaper(editorDraft?.preset?.snapshot ?? captureConfiguration());
      if (fromEditor) await closeEditor();
      native.changeWallpaper(exit);
    } catch (error) {
      wallpaperChanging = false;
      updateWallpaperButton();
      showAppNotice(error.message);
    }
  }
  $("#apply-wallpaper").onclick = () => changeWallpaper(wallpaperApplied);
  $("#welcome-wallpaper").onclick = () => {
    if (wallpaperApplied) changeWallpaper(true);
  };
  $("#scene-apply").onclick = () => changeWallpaper(false, true);
  window.runWallpaperTileAction = exit => changeWallpaper(exit, !$("#scene-editor").hidden);
  window.addEventListener("storage", event => {
    if (wallpaper || editorDraft || event.key !== key || !event.newValue) return;
    try {
      const draft = JSON.parse(event.newValue);
      layers = normalize(Array.isArray(draft) ? draft : draft?.scene);
      render();
      if (!$("#scene-editor").hidden) drawStack();
      scheduleWallpaperFrame();
    } catch {}
  });
  document.addEventListener("visibilitychange", () => {
    setVisible(!document.hidden && (wallpaper ? wallpaperVisible : panelPlaybackActive));
    updateTiltPreview();
  });
  function mediaReady() {
    return layers.every(layer => {
      if (!layer.visible || layer.opacity <= 0 || !layer.media) return true;
      const node = nodes.get(layer.id);
      return layer.kind === "video" ? node?.readyState >= 2 : node?.complete && node.naturalWidth > 0;
    });
  }
  function waitForSceneFrame() {
    view.app?.syncCanvas();
    view.app?.renderer.render(view.app.stage);
    if (mediaReady() || !view.model) return;
    return new Promise(resolve => {
      const deadline = performance.now() + 650;
      const check = () => {
        if (mediaReady() || document.hidden || performance.now() >= deadline) resolve();
        else setTimeout(check, 24);
      };
      check();
    });
  }
  window.sceneLayers = {
    render, setVisible, get layers() { return JSON.parse(JSON.stringify(layers)); }, open: () => transitionPanel(openEditor, waitForSceneFrame),
    get savedPreviewOpen() { return Boolean(editorDraft); },
    get wallpaperTheme() { return draftSettings && document.documentElement.classList.contains("scene-edit") ? draftSettings.theme ?? null : undefined; },
    ignoreGestureClick: (event) => suppressClick && event.detail > 0,
    setTiltActive, shiftBackground, updateTiltPreview,
    mediaReady, waitForSceneFrame,
    media, removeMedia, releaseUnusedMedia, missingMedia, openSavedConfiguration, captureConfiguration, cacheConfigurationMedia,
    noteSavedConfiguration(item) {
      if (document.documentElement.classList.contains("scene-edit")) savedInEditor = item;
    },
    reloadDesktopScene() {
      if (!wallpaper) return;
      layers = normalize(JSON.parse(native.desktopSetting(key)));
      render();
      scheduleWallpaperFrame();
    },
  };
  render();
})();
