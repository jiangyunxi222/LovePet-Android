"use strict";

(() => {
  if (pet || wallpaper) return;
  const dialog = $("#app-library");
  const list = $("#app-library-items");
  const message = $("#app-library-message");
  const presetKey = "wallpaper-presets-v1";
  const saveButton = $("#scene-save-preset");
  const saveLabel = saveButton.querySelector("span");
  const saveStatus = $("#scene-save-status");
  const defaultSaveLabel = saveLabel.textContent;
  let saveLabelAnimation;
  let saveFeedbackTimer;
  let requestId = 0;
  let loading = false;
  let requestKind;
  const requests = new Map();
  const modelPreviewKey = id => "model-preview-v2:" + id;
  const pendingPreviews = new Map();
  const removedModels = new Set();
  let previewQueue = Promise.resolve();

  function callNative(start, timeout = 10000) {
    return new Promise((resolve, reject) => {
      const id = ++requestId;
      const timer = setTimeout(() => {
        requests.delete(id);
        reject(new Error("操作超时，请重试。"));
      }, timeout);
      requests.set(id, result => { clearTimeout(timer); resolve(result); });
      start(id);
    });
  }
  function resolveRequest(id, result) {
    const callback = requests.get(id);
    requests.delete(id);
    callback?.(result);
  }
  window.modelLibraryResult = resolveRequest;
  window.wallpaperPreviewResult = resolveRequest;

  async function useModel(id, costume = -1, remove = false) {
    const error = await callNative(request => native.updateSavedModel(request, id, costume, remove));
    if (error) throw new Error(error);
  }
  function storedPresets() {
    return JSON.parse(localStorage.getItem(presetKey) || "[]");
  }
  function prioritizeLastUsed(items) {
    const id = native.lastUsedWallpaperPreset();
    const index = items.findIndex(item => item.id === id);
    return index > 0 ? [items[index], ...items.slice(0, index), ...items.slice(index + 1)] : items;
  }
  function presets() { return prioritizeLastUsed(storedPresets()); }
  const presetModel = item => item.snapshot?.model || item.model;
  const presetScene = item => item.snapshot?.scene || item.layers;
  function syncPresetIndex(items, retained = items) {
    const models = [...new Set(retained.map(presetModel).filter(id => /^[0-9a-f-]{36}$/.test(id)))];
    const media = [...new Set(retained.flatMap(item => presetScene(item).map(layer => layer.media).filter(Boolean)))];
    const ids = [...new Set(retained.map(item => item.id))];
    const first = items[0];
    const latest = first?.snapshot ? {...first.snapshot, presetId: first.id} : null;
    return native.updateWallpaperPresetIndex(JSON.stringify({models, media, ids, latest}));
  }
  function writePresets(items) {
    const previous = storedPresets();
    items = prioritizeLastUsed(items);
    if (!syncPresetIndex(items, [...previous, ...items])) throw new Error("无法保存壁纸配置，请检查可用空间。");
    try { localStorage.setItem(presetKey, JSON.stringify(items)); }
    catch (error) { syncPresetIndex(previous); throw error; }
    if (previous.some(record => !items.some(item => item.id === record.id))) syncPresetIndex(items);
  }
  window.wallpaperPresetApplied = () => {
    try {
      const previous = storedPresets();
      const ordered = prioritizeLastUsed(previous);
      if (ordered !== previous) {
        writePresets(ordered);
        if (dialog.open && requestKind === "wallpaper") renderLibrary("wallpaper");
      }
    } catch (error) { console.error(error); }
  };
  async function migratePreset(item = presets()[0]) {
    if (!item || item.snapshot) return item;
    const config = native.captureWallpaperConfig(item.model, item.costume ?? -1, "", JSON.stringify(item.layers), "");
    if (!config) return item;
    const snapshot = JSON.parse(config);
    await sceneLayers.cacheConfigurationMedia(snapshot);
    const items = presets();
    const current = items.find(record => record.id === item.id);
    if (!current || current.snapshot) return current || item;
    const upgraded = {id: item.id, name: item.name, added: item.added, snapshot};
    writePresets(items.map(record => record.id === item.id ? upgraded : record));
    return upgraded;
  }
  async function restoreWallpaper(item, useCurrent = false) {
    const previous = wallpaperEditorPreset;
    try {
      if (!useCurrent) {
        item = await migratePreset(item).catch(error => { console.error(error); return item; });
        wallpaperEditorPreset = {model: presetModel(item), costume: item.snapshot?.costume ?? item.costume,
          outfit: item.snapshot?.outfit};
      }
      const present = (showModel) => transitionPanel(() => {
        if (showModel?.() === false) return;
        dialog.close();
        sceneLayers.openSavedConfiguration(presetScene(item), item.snapshot?.settings, useCurrent ? null : item);
      }, sceneLayers.waitForSceneFrame);
      if (useCurrent) await present();
      else if (!await refreshModel(present)) throw new Error("壁纸模型已失效。");
    } catch (error) {
      if (wallpaperEditorPreset !== previous) {
        wallpaperEditorPreset = previous;
        await refreshModel();
      }
      throw error;
    }
  }
  window.savedWallpaperUsesMedia = id => storedPresets().some(item => presetScene(item).some(layer => layer.media === id));
  window.wallpaperPresetApplied();
  try { syncPresetIndex(presets()); } catch (error) { console.error(error); }
  migratePreset().catch(console.error);

  async function renderModelPreview(session) {
    const viewer = session.viewerPackage ? await viewerInteraction.load(asset("plain/viewer.json", modelBase(session))) : null;
    const { metadata } = await loadCostumeAssets(session, session.catalog.costumes[0], viewer, viewer?.select());
    const model = await PIXI.live2d.Live2DModel.from(metadata, { autoInteract: false, autoUpdate: false, motionPreload: "NONE" });
    const width = 176, height = 210;
    let temporaryRenderer, target;
    try {
      const bounds = model.getLocalBounds();
      if (!bounds.width || !bounds.height) return;
      let renderer = view.app?.renderer;
      if (!renderer) {
        const canvas = document.createElement("canvas");
        temporaryRenderer = renderer = new PIXI.Renderer({view: canvas, width, height, backgroundAlpha: 0,
          context: makeWebGLContext(canvas)});
      }
      const scale = .92 * Math.min(width / bounds.width, height / bounds.height);
      model.scale.set(scale);
      model.position.set(width / 2 - (bounds.x + bounds.width / 2) * scale,
        height / 2 - (bounds.y + bounds.height / 2) * scale);
      target = PIXI.RenderTexture.create({width, height});
      renderer.render(model, {renderTexture: target});
      const thumb = renderer.extract.canvas(target);
      const pixels = thumb.getContext("2d").getImageData(0, 0, width, height).data;
      if (pixels.some((alpha, index) => index % 4 === 3 && alpha >= 28)) return thumb.toDataURL("image/webp", .8);
    } finally {
      model.destroy({children: true});
      target?.destroy(true);
      temporaryRenderer?.destroy(true);
    }
  }

  window.cacheModelPreview = (id, session) => {
    if (pendingPreviews.has(id)) return pendingPreviews.get(id);
    const job = previewQueue.then(async () => {
      const key = modelPreviewKey(id);
      const cached = await sceneLayers.media(key);
      if (cached || removedModels.has(id)) return cached;
      const preview = await renderModelPreview(session || await data("/model-record/" + encodeURIComponent(id)));
      if (!preview || removedModels.has(id)) return;
      await sceneLayers.media(key, preview);
      sceneLayers.removeMedia("model-preview:" + id).catch(console.error);
      return preview;
    }).finally(() => pendingPreviews.delete(id));
    previewQueue = job.catch(() => {});
    pendingPreviews.set(id, job);
    return job;
  };
  const previewObserver = new IntersectionObserver(entries => {
    for (const entry of entries) {
      if (!entry.isIntersecting) continue;
      const image = entry.target;
      previewObserver.unobserve(image);
      window.cacheModelPreview(image.dataset.modelId)
        .then(preview => { if (preview) image.src = preview; }).catch(console.error);
    }
  }, {root: dialog});

  function showMessage(text = "") {
    message.textContent = text;
    message.hidden = !text;
  }
  async function renderLibrary(kind) {
    previewObserver.disconnect();
    requestKind = kind;
    const models = kind === "model";
    $("#app-library-title").textContent = models ? "已导入的模型" : "已保存的壁纸";
    $("#app-library-add").hidden = !models;
    list.replaceChildren();
    showMessage();
    if (!dialog.open) dialog.showModal();
    syncAppModalVisibility();
    try {
      const items = models ? await data("/saved-models") : presets();
      if (!dialog.open || requestKind !== kind) return;
      if (!items.length) showMessage(models ? "还没有导入模型。" : "还没有保存壁纸配置。");
      for (const item of items) {
        const row = document.createElement("li");
        row.className = "library-item";
        const image = document.createElement("img");
        image.alt = "";
        image.src = "/favicon.svg";
        if (models && item.available) {
          image.dataset.modelId = item.id;
          previewObserver.observe(image);
        } else if (!models) {
          sceneLayers.media("wallpaper-preview:" + item.id)
            .then(preview => { if (preview) image.src = preview; }).catch(console.error);
        }
        const select = button(item.name, "library-use", () => run(async () => {
          if (models) {
            await useModel(item.id);
            const ready = await refreshModel(showModel => transitionPanel(() => {
              if (showModel() !== false) dialog.close();
            }));
            if (!ready) dialog.close();
          } else {
            const missing = await sceneLayers.missingMedia(presetScene(item));
            if (missing.length) throw new Error("壁纸素材已失效：" + missing.join("、") + "。请重新导入素材。");
            try {
              await restoreWallpaper(item);
            } catch (error) {
              if (!view.model || !error.message.includes("已失效")) throw error;
              showAppPrompt(null, "壁纸模型已失效",
                "可以重新导入原模型，或使用当前模型恢复这套壁纸的背景和特效。", "使用当前模型",
                confirmed => {
                  if (confirmed) run(() => restoreWallpaper(item, true));
                });
              throw error;
            }
          }
        }));
        const caption = document.createElement("small");
        caption.textContent = models
          ? !item.available ? "模型文件已失效" : item.active ? "当前模型" : item.sourceName || "本机已导入"
          : new Date(item.added).toLocaleString();
        select.append(caption);
        const remove = button("删除", "library-delete", () => {
          if (loading) return;
          showAppPrompt(null, models ? "删除模型" : "删除壁纸配置",
            models ? "从模型记录删除「" + item.name + "」？已保存或已应用的壁纸会保留所用模型。"
              : "删除「" + item.name + "」这条壁纸配置？",
            "删除", confirmed => {
              if (!confirmed) return;
              run(async () => {
                if (models) {
                  await useModel(item.id, -1, true);
                  removedModels.add(item.id);
                  await sceneLayers.removeMedia(modelPreviewKey(item.id));
                  await sceneLayers.removeMedia("model-preview:" + item.id);
                  if (item.active) await refreshModel();
                } else {
                  writePresets(presets().filter(record => record.id !== item.id));
                  migratePreset().catch(console.error);
                  sceneLayers.releaseUnusedMedia(presetScene(item).map(layer => layer.media));
                  await sceneLayers.removeMedia("wallpaper-preview:" + item.id);
                }
                await renderLibrary(kind);
              });
            });
        });
        row.append(image, select, remove);
        list.append(row);
      }
    } catch (error) {
      showMessage(error.message);
    }
  }

  async function run(action) {
    if (loading) return;
    loading = true;
    showMessage();
    $("#app-library-add").disabled = true;
    try { await action(); }
    catch (error) { showMessage(error.message); }
    finally {
      loading = false;
      $("#app-library-add").disabled = false;
    }
  }

  window.openModelLibrary = () => renderLibrary("model");
  $("#welcome-models").onclick = window.openModelLibrary;
  $("#scene-open-presets").onclick = () => renderLibrary("wallpaper");
  $("#edit-scene").onclick = () => {
    if (view.model) sceneLayers.open();
    else { settings.close(); renderLibrary("wallpaper"); }
  };
  $(".library-close").onclick = () => dialog.close();
  dialog.addEventListener("close", () => {
    previewObserver.disconnect();
    syncAppModalVisibility();
  });
  $("#app-library-add").onclick = () => { dialog.close(); native.pickModel(); };

  async function fadeSaveLabel(text) {
    saveLabelAnimation?.cancel();
    if (matchMedia("(prefers-reduced-motion: reduce)").matches) {
      saveLabelAnimation = null;
      saveLabel.textContent = text;
      return;
    }
    const fadeOut = saveLabel.animate([{opacity: 1}, {opacity: 0}], {
      duration: 110, easing: "ease-in", fill: "forwards"
    });
    saveLabelAnimation = fadeOut;
    await fadeOut.finished.catch(() => {});
    if (saveLabelAnimation !== fadeOut) return;
    saveLabel.textContent = text;
    fadeOut.cancel();
    saveLabelAnimation = saveLabel.animate([{opacity: 0}, {opacity: 1}], {
      duration: 150, easing: "ease-out"
    });
  }

  saveButton.onclick = async () => {
    if (!view.model) return;
    clearTimeout(saveFeedbackTimer);
    saveLabelAnimation?.cancel();
    saveLabelAnimation = null;
    saveLabel.textContent = defaultSaveLabel;
    saveStatus.textContent = "";
    saveButton.disabled = true;
    const root = document.documentElement;
    const id = Date.now().toString(36) + "-" + Math.random().toString(36).slice(2);
    try {
      const item = { id, name: view.session.catalog.name, snapshot: sceneLayers.captureConfiguration(), added: Date.now() };
      root.classList.add("scene-capture");
      await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
      const preview = await callNative(request => native.captureWallpaperPreview(request), 1800);
      if (!preview) throw new Error("预览图生成失败，请重试。");
      await sceneLayers.media("wallpaper-preview:" + id, preview);
      await sceneLayers.cacheConfigurationMedia(item.snapshot);
      writePresets([item, ...presets()]);
      sceneLayers.noteSavedConfiguration(item);
    } catch (error) {
      sceneLayers.removeMedia("wallpaper-preview:" + id).catch(console.error);
      showAppNotice(error.message);
      return;
    } finally {
      root.classList.remove("scene-capture");
      saveButton.disabled = false;
    }
    const feedback = "壁纸已保存";
    saveStatus.textContent = feedback;
    fadeSaveLabel(feedback);
    saveFeedbackTimer = setTimeout(() => fadeSaveLabel(defaultSaveLabel), 1600);
  };
})();
