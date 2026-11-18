"use strict";

(() => {
  if (pet) return;
  const key = "wallpaper-scene-v1";
  const base = () => ({ id: "background", kind: "background", name: "默认背景", color: "default", opacity: 1, visible: true, scale: 1, x: 0, y: 0 });
  const isBase = layer => layer.id === "background";
  const defaults = () => [
    base(),
    { id: "character", kind: "character", name: "Live2D 角色", opacity: 1, visible: true, scale: .82, x: 0, y: -.04 },
    { id: "sparkles", kind: "effect", name: "星光", effect: "stars", opacity: .65, visible: true, scale: 1, x: 0, y: 0 },
  ];
  const palettes = {
    default: ["原色", "radial-gradient(ellipse at 50% 82%,#eee0eb,#fbf5f9 75%)"],
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
    return [base(), ...content];
  }
  let layers;
  try {
    layers = normalize(JSON.parse(localStorage.getItem(key)));
  } catch { layers = defaults(); }
  let selected = "character", visible = true, engine;
  const nodes = new Map(), urls = new Map();
  const effects = new Map();
  const identifier = () => Date.now().toString(36) + "-" + Math.random().toString(36).slice(2);
  const host = $("#scene-layers");
  const database = new Promise((resolve, reject) => {
    const request = indexedDB.open("wallpaper-media", 1);
    request.onupgradeneeded = () => request.result.createObjectStore("files");
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
  function media(id, value) {
    return database.then(db => new Promise((resolve, reject) => {
      const tx = db.transaction("files", value ? "readwrite" : "readonly");
      const store = tx.objectStore("files");
      const request = value ? store.put(value, id) : store.get(id);
      tx.oncomplete = () => resolve(request.result);
      tx.onabort = tx.onerror = () => reject(tx.error || Error("素材保存失败"));
    }));
  }
  function removeMedia(id) {
    return database.then(db => new Promise((resolve, reject) => {
      const tx = db.transaction("files", "readwrite");
      tx.objectStore("files").delete(id);
      tx.oncomplete = resolve;
      tx.onabort = tx.onerror = () => reject(tx.error);
    }));
  }
  function releaseUnusedMedia(ids) {
    for (const id of ids) {
      if (!id || layers.some(layer => layer.media === id) || window.savedWallpaperUsesMedia?.(id)) continue;
      removeMedia(id).catch(console.error);
    }
  }
  async function missingMedia(scene) {
    const files = await Promise.all(scene.filter(layer => layer.media).map(async layer =>
      await media(layer.media) instanceof Blob ? null : layer.name));
    return files.filter(Boolean);
  }
  function restore(scene) {
    const previous = layers;
    layers = normalize(scene);
    if (!save()) { layers = previous; throw new Error("无法保存当前壁纸配置。"); }
    selected = "character";
    releaseUnusedMedia(previous.map(layer => layer.media));
    render();
    drawStack();
  }
  function message(text = "") {
    $("#scene-message").textContent = text;
    $("#scene-message").hidden = !text;
  }
  function save() {
    try { localStorage.setItem(key, JSON.stringify(layers)); }
    catch { message("场景保存失败，请检查可用存储空间。"); return false; }
    window.dispatchEvent(new Event("wallpaper-scene-saved"));
    return true;
  }
  function applyNode(layer, node, index) {
    node.style.zIndex = index + 1;
    node.style.opacity = layer.visible ? layer.opacity : 0;
    node.style.transform = "translate(" + layer.x * 100 + "%," + layer.y * 100 + "%) scale(" + layer.scale + ")";
    if (layer.kind === "character") node.style.pointerEvents = layer.visible && layer.opacity > 0 ? "auto" : "none";
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
      syncEffect(layers.find(item => item.id === layer.id));
    } catch (error) { console.error("Particle layer failed", error); message("特效加载失败，请重新打开应用。"); }
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
    layers.forEach((layer, index) => {
      if (layer.kind === "character") { applyNode(layer, $("#canvas"), index); return; }
      let node = nodes.get(layer.id);
      if (!node) {
        node = document.createElement(layer.kind === "video" ? "video" : layer.kind === "image" ? "img" : "div");
        node.className = "scene-layer";
        nodes.set(layer.id, node);
        host.append(node);
        if (layer.kind === "effect") { node.id = "particles-" + layer.id; makeParticles(node, layer); }
        if (layer.kind === "video") {
          node.muted = true; node.loop = true; node.playsInline = true;
        }
        if (layer.media) media(layer.media).then(blob => {
          if (!blob || nodes.get(layer.id) !== node) return;
          const url = URL.createObjectURL(blob);
          urls.set(layer.id, url); node.src = url;
          if (layer.kind === "video" && visible && layer.visible) node.play().catch(() => message("此视频格式暂时无法播放。"));
        }).catch(() => message("图片或视频读取失败，请重新添加。"));
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
    visible = show;
    render();
  }
  function current() { return layers.find(layer => layer.id === selected) || layers[0]; }
  function editSelected() {
    const layer = current();
    $("#scene-selected").textContent = layer.name;
    $("#scene-selection-hint").textContent = layer.name;
    for (const [name, value] of [["opacity", layer.opacity * 100], ["scale", layer.scale * 100], ["x", layer.x * 100], ["y", layer.y * 100]]) {
      $("#scene-" + name).value = Math.round(value);
    }
    $("#scene-opacity-value").textContent = Math.round(layer.opacity * 100) + "%";
    $("#scene-scale-value").textContent = Math.round(layer.scale * 100) + "%";
    $("#scene-scale-row").hidden = $(".scene-position").hidden = layer.kind === "background";
    $("#scene-opacity-row").hidden = isBase(layer);
    $("#scene-base-note").hidden = !isBase(layer);
    $("#scene-palette").hidden = layer.kind !== "background" || isBase(layer);
    for (const button of $("#scene-palette").children) button.setAttribute("aria-pressed", String(button.dataset.color === layer.color));
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
  function openEditor() {
    if (!view.model) return;
    settings.close();
    document.documentElement.classList.add("scene-edit");
    $("#scene-editor").hidden = false;
    window.dispatchEvent(new Event("scene-editor-change"));
    drawStack();
  }
  $("#edit-scene").onclick = openEditor;
  $("#scene-back").onclick = () => {
    $("#scene-choices").hidden = true;
    document.documentElement.classList.remove("scene-edit", "scene-preview");
    $("#scene-editor").hidden = true;
    $("#scene-preview").setAttribute("aria-pressed", "false");
    $("#scene-preview").textContent = "预览";
    window.dispatchEvent(new Event("scene-editor-change"));
  };
  $("#scene-preview").onclick = () => {
    const on = document.documentElement.classList.toggle("scene-preview");
    $("#scene-preview").textContent = on ? "编辑" : "预览";
    $("#scene-preview").setAttribute("aria-pressed", String(on));
  };
  for (const [color, [name, background]] of Object.entries(palettes)) {
    if (color === "default") continue;
    const choice = button(name, "scene-swatch", () => { current().color = color; current().name = name + "背景"; update(); });
    choice.style.setProperty("--swatch", background);
    choice.dataset.color = color;
    $("#scene-palette").append(choice);
  }
  for (const name of ["opacity", "scale", "x", "y"]) {
    $("#scene-" + name).oninput = (event) => {
      if (isBase(current())) return;
      current()[name] = Number(event.target.value) / 100;
      render(); editSelected();
    };
    $("#scene-" + name).onchange = save;
  }
  function addLayer(kind, properties) {
    if (layers.length >= 12) return message("最多可放置 12 个图层。");
    if (kind === "effect" && layers.filter(layer => layer.kind === "effect").length >= 3) return message("最多同时使用 3 层特效，减少发热与耗电。");
    const layer = { id: identifier(), kind, opacity: kind === "effect" ? .8 : 1, visible: true, scale: 1, x: 0, y: 0, ...properties };
    if (kind === "background") layers.splice(Math.max(1, layers.findIndex(item => item.kind === "character")), 0, layer);
    else layers.push(layer);
    selected = layer.id; $("#scene-choices").hidden = true; update(); message();
  }
  function chooseLayer(kind) {
    const choices = $("#scene-choices");
    if (!choices.hidden && choices.dataset.kind === kind) { choices.hidden = true; return; }
    choices.dataset.kind = kind;
    $("#scene-choices-title").textContent = kind === "effect" ? "选择特效" : "选择背景配色";
    const grid = $("#scene-choice-grid"); grid.textContent = "";
    function appendChoice(target, type, name, background) {
      const choice = button(name, "scene-choice", () => addLayer(kind,
        kind === "effect" ? { effect: type, name } : { color: type, name: name + "背景" }));
      choice.dataset.type = type;
      if (background) {
        choice.classList.add("scene-swatch");
        choice.style.setProperty("--swatch", background);
      }
      target.append(choice);
    }
    if (kind === "effect") {
      const content = document.createElement("div"); content.className = "scene-choice-grid";
      for (const [type, name] of Object.entries(effectNames)) appendChoice(content, type, name);
      grid.append(content);
    } else {
      const content = document.createElement("div"); content.className = "scene-choice-grid";
      for (const [color, [name, background]] of Object.entries(palettes)) if (color !== "default") appendChoice(content, color, name, background);
      grid.append(content);
    }
    choices.hidden = false;
    choices.scrollIntoView({ block: "nearest", behavior: "smooth" });
  }
  $("#scene-add-effect").onclick = () => chooseLayer("effect");
  $("#scene-add-background").onclick = () => chooseLayer("background");
  // Keep the renderer dimensions steady during gestures; only transform this layer.
  const stage = $("#stage"), fingers = new Map();
  let gesture;
  function beginGesture() {
    const points = [...fingers.values()].slice(0, 2), layer = current();
    if (!points.length) { gesture = null; return; }
    gesture = { id: layer.id, x: layer.x, y: layer.y, scale: layer.scale,
      center: points.length === 2 ? { x: (points[0].x + points[1].x) / 2, y: (points[0].y + points[1].y) / 2 } : points[0],
      distance: points.length === 2 ? Math.hypot(points[0].x - points[1].x, points[0].y - points[1].y) : 0 };
  }
  stage.addEventListener("pointerdown", event => {
    if (!document.documentElement.classList.contains("scene-edit") || document.documentElement.classList.contains("scene-preview") || isBase(current())) return;
    if (event.pointerType === "mouse" && event.button !== 0) return;
    event.preventDefault(); event.stopPropagation();
    fingers.set(event.pointerId, { x: event.clientX, y: event.clientY });
    stage.setPointerCapture(event.pointerId); beginGesture();
  });
  stage.addEventListener("pointermove", event => {
    if (!fingers.has(event.pointerId) || !gesture) return;
    event.preventDefault();
    fingers.set(event.pointerId, { x: event.clientX, y: event.clientY });
    const layer = layers.find(item => item.id === gesture.id);
    if (!layer || isBase(layer)) return;
    const points = [...fingers.values()].slice(0, 2), bounds = stage.getBoundingClientRect();
    const center = points.length === 2 ? { x: (points[0].x + points[1].x) / 2, y: (points[0].y + points[1].y) / 2 } : points[0];
    const distance = points.length === 2 ? Math.hypot(points[0].x - points[1].x, points[0].y - points[1].y) : 0;
    const clamp = (value, min, max) => Math.max(min, Math.min(max, value));
    layer.scale = clamp(gesture.scale * (gesture.distance > 0 ? distance / gesture.distance : 1), .3, 3);
    const ratio = layer.scale / gesture.scale;
    layer.x = clamp((center.x - bounds.left - bounds.width / 2 - (gesture.center.x - bounds.left - bounds.width / 2 - gesture.x * bounds.width) * ratio) / bounds.width, -.8, .8);
    layer.y = clamp((center.y - bounds.top - bounds.height / 2 - (gesture.center.y - bounds.top - bounds.height / 2 - gesture.y * bounds.height) * ratio) / bounds.height, -.8, .8);
    const node = layer.kind === "character" ? $("#canvas") : nodes.get(layer.id);
    if (node) applyNode(layer, node, layers.indexOf(layer));
    editSelected();
  });
  function endGesture(event) {
    if (!fingers.delete(event.pointerId)) return;
    beginGesture(); save();
  }
  stage.addEventListener("pointerup", endGesture);
  stage.addEventListener("pointercancel", endGesture);
  stage.addEventListener("lostpointercapture", endGesture);
  $("#scene-add-image").onclick = () => $("#scene-file").click();
  $("#scene-file").onchange = async (event) => {
    const file = event.target.files[0];
    event.target.value = "";
    if (!file) return;
    if (layers.length >= 12) return message("最多可放置 12 个图层。");
    if (!["image/png", "image/jpeg", "image/webp", "video/mp4"].includes(file.type)) return message("请选择 PNG、JPG、WebP 或 MP4 文件。");
    if (file.size > 64 * 1024 * 1024) return message("单个素材请控制在 64 MB 以内。");
    const id = identifier();
    try {
      const contents = new Blob([await file.arrayBuffer()], {type: file.type});
      await media(id, contents);
      const layer = { id, media: id, kind: file.type.startsWith("video/") ? "video" : "image", name: file.name, opacity: 1, visible: true, scale: 1, x: 0, y: 0 };
      layers.splice(Math.max(1, layers.findIndex(item => item.kind === "character")), 0, layer);
      selected = id; update(); message();
    } catch (error) {
      console.error("Scene media import failed", error);
      message("素材读取或保存失败，请重新选择文件。");
    }
  };
  $("#apply-wallpaper").onclick = $("#welcome-wallpaper").onclick = () => {
    if (wallpaperApplied || save()) {
      wallpaperChanging = true;
      updateWallpaperButton();
      native.applyWallpaper();
    }
    else showAppNotice("壁纸场景保存失败，请检查可用存储空间。");
  };
  $("#scene-apply").onclick = () => {
    save(); $("#scene-back").click();
    controlsCollapsed = true;
    localStorage.setItem("controlsCollapsed", "true");
    window.dispatchEvent(new Event("scene-editor-change"));
  };
  window.addEventListener("storage", event => {
    if (event.key !== key || !event.newValue) return;
    try { layers = normalize(JSON.parse(event.newValue)); render(); if (!$("#scene-editor").hidden) drawStack(); } catch {}
  });
  document.addEventListener("visibilitychange", () => setVisible(!document.hidden && (!wallpaper || wallpaperVisible)));
  window.sceneLayers = {
    setVisible, get layers() { return JSON.parse(JSON.stringify(layers)); }, open: openEditor,
    media, removeMedia, releaseUnusedMedia, missingMedia, restore,
  };
  render();
})();
