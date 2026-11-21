"use strict";

(() => {
  if (pet || wallpaper) return;
  const dialog = $("#app-library");
  const list = $("#app-library-items");
  const message = $("#app-library-message");
  const presetKey = "wallpaper-presets-v1";
  let requestId = 0;
  let loading = false;
  let requestKind;
  const requests = new Map();

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
  function presets() {
    return JSON.parse(localStorage.getItem(presetKey) || "[]");
  }
  function writePresets(items) {
    localStorage.setItem(presetKey, JSON.stringify(items));
  }
  async function restoreWallpaper(item) {
    sceneLayers.restore(item.layers);
    dialog.close();
    await refreshModel();
    if (view.model) sceneLayers.open();
  }
  window.savedWallpaperUsesMedia = id => presets().some(item => item.layers.some(layer => layer.media === id));

  window.modelPreviewChanged = () => {
    const canvas = $("#canvas canvas");
    if (!canvas?.width || !canvas.height || !view.session) return;
    const thumb = document.createElement("canvas");
    const scale = 220 / Math.max(canvas.width, canvas.height);
    thumb.width = Math.max(1, Math.round(canvas.width * scale));
    thumb.height = Math.max(1, Math.round(canvas.height * scale));
    try {
      if (!hasVisibleModelPixels()) return;
      thumb.getContext("2d").drawImage(canvas, 0, 0, thumb.width, thumb.height);
      sceneLayers.media("model-preview:" + view.session.version, thumb.toDataURL("image/webp", .8)).catch(console.error);
    } catch (error) { console.error("Model preview unavailable", error); }
  };

  function showMessage(text = "") {
    message.textContent = text;
    message.hidden = !text;
  }
  async function renderLibrary(kind) {
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
        sceneLayers.media((models ? "model-preview:" : "wallpaper-preview:") + item.id)
          .then(preview => { if (preview) image.src = preview; }).catch(console.error);
        const select = button(item.name, "library-use", () => run(async () => {
          if (models) {
            await useModel(item.id);
            dialog.close();
            await refreshModel();
          } else {
            const missing = await sceneLayers.missingMedia(item.layers);
            if (missing.length) throw new Error("壁纸素材已失效：" + missing.join("、") + "。请重新导入素材。");
            try {
              await useModel(item.model, item.costume);
            } catch (error) {
              if (!view.model || !error.message.includes("已失效")) throw error;
              showAppPrompt(null, "壁纸模型已失效",
                "可以重新导入原模型，或使用当前模型恢复这套壁纸的背景和特效。", "使用当前模型",
                confirmed => {
                  if (confirmed) run(() => restoreWallpaper(item));
                });
              throw error;
            }
            await restoreWallpaper(item);
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
            models ? "删除「" + item.name + "」和已导入的本机模型文件？以后可用其他模型恢复引用它的壁纸配置。"
              : "删除「" + item.name + "」这条壁纸配置？",
            "删除", confirmed => {
              if (!confirmed) return;
              run(async () => {
                if (models) {
                  await useModel(item.id, -1, true);
                  await sceneLayers.removeMedia("model-preview:" + item.id);
                  if (item.active) await refreshModel();
                } else {
                  writePresets(presets().filter(record => record.id !== item.id));
                  sceneLayers.releaseUnusedMedia(item.layers.map(layer => layer.media));
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
  dialog.addEventListener("close", syncAppModalVisibility);
  $("#app-library-add").onclick = () => { dialog.close(); native.pickModel(); };
  $("#scene-save-preset").onclick = async (event) => {
    if (!view.model) return;
    const button = event.currentTarget;
    button.disabled = true;
    const root = document.documentElement;
    const id = Date.now().toString(36) + "-" + Math.random().toString(36).slice(2);
    const item = {
      id, name: view.session.catalog.name, model: view.session.version,
      costume: view.costume?.id ?? -1, layers: sceneLayers.layers, added: Date.now(),
    };
    try {
      root.classList.add("scene-capture");
      await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
      const preview = await callNative(request => native.captureWallpaperPreview(request), 1800);
      if (!preview) throw new Error("预览图生成失败，请重试。");
      await sceneLayers.media("wallpaper-preview:" + id, preview);
      writePresets([item, ...presets()]);
      showAppNotice("已保存到壁纸配置");
    } catch (error) {
      sceneLayers.removeMedia("wallpaper-preview:" + id).catch(console.error);
      showAppNotice(error.message);
    } finally {
      root.classList.remove("scene-capture");
      button.disabled = false;
    }
  };
})();
