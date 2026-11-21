"use strict";

const performanceTracks = ["action", "expression", "voice"];

function matchesItemFlag(item, flag) {
  return item.flag === flag || item.aliasFlags?.includes(flag);
}

function performanceChoices(catalog, costume) {
  return {
    action: costume.actions || [],
    expression: costume.expressions || [],
    voice: (catalog.voices || []).filter((item) => !item.costumeIds || item.costumeIds.includes(costume.id)),
  };
}

function outfitPerformances(value) {
  if (Array.isArray(value)) return value.filter((group) => group?.id && group.name && group.tracks);
  if (value && performanceTracks.some((kind) => value[kind])) {
    return [{ id: "first", name: "演出 1", tracks: value }];
  }
  return [];
}

function mergeOutfitPerformances(groups, costumes) {
  for (const costume of costumes) {
    for (const alias of costume.aliasFlags || []) {
      if (!Object.hasOwn(groups, alias)) continue;
      const saved = outfitPerformances(groups[costume.flag]);
      const previous = outfitPerformances(groups[alias]).map(group => ({ ...group, id: alias + "-" + group.id }));
      groups[costume.flag] = [...saved, ...previous];
      delete groups[alias];
    }
  }
}

function updateOutfitPerformances(groups, outfit, id, group) {
  const list = outfitPerformances(groups[outfit]);
  const index = list.findIndex((item) => item.id === id);
  if (index >= 0) list.splice(index, 1, ...(group ? [{ id, ...group }] : []));
  else if (group) list.push({ id, ...group });
  if (list.length) groups[outfit] = list;
  else delete groups[outfit];
}

function createPerformanceEditor({ read, save, setEnabled, onVisibility = () => {} }) {
  const dialog = document.querySelector("#performance-settings");
  const form = dialog.querySelector("form");
  const fields = form.querySelector("fieldset");
  const outfit = form.elements.costume;
  const preset = form.elements.preset;
  const name = form.elements.presetName;
  const enabled = form.elements.enabled;
  const status = form.querySelector("[role=status]");
  const savedToast = dialog.querySelector("[data-performance-toast]");
  const optionsDialog = document.querySelector("#performance-options");
  const optionsTitle = optionsDialog.querySelector("h2");
  const search = optionsDialog.querySelector("[data-option-search]");
  const optionsList = optionsDialog.querySelector(".performance-option-list");
  const optionEmpty = optionsDialog.querySelector("[data-option-empty]");
  const choiceButtons = [...dialog.querySelectorAll("[data-performance-choose]")];
  let activeChoice;
  let context;
  let savedToastTimer;

  function hideSavedToast() {
    clearTimeout(savedToastTimer);
    savedToast.hidden = true;
  }

  function syncChoices() {
    for (const button of choiceButtons) {
      const text = form.elements[button.dataset.performanceChoose].selectedOptions[0]?.textContent || "";
      button.firstElementChild.textContent = text;
      button.setAttribute("aria-label", button.dataset.label + "，" + text);
    }
  }

  function renderOptions() {
    if (!activeChoice) return;
    const field = form.elements[activeChoice.dataset.performanceChoose];
    const query = search.value.trim().toLocaleLowerCase();
    const rows = [];
    for (const option of field.options) {
      if (!option.textContent.toLocaleLowerCase().includes(query)) continue;
      const button = document.createElement("button");
      button.type = "button";
      button.textContent = option.textContent;
      button.setAttribute("aria-pressed", String(option.selected));
      button.onclick = () => {
        field.value = option.value;
        field.dispatchEvent(new Event("change", { bubbles: true }));
        optionsDialog.close();
      };
      const row = document.createElement("li");
      row.append(button);
      rows.push(row);
    }
    optionsList.replaceChildren(...rows);
    optionEmpty.hidden = rows.length > 0;
    optionsList.scrollTop = 0;
  }

  for (const button of choiceButtons) {
    button.onclick = () => {
      activeChoice = button;
      optionsTitle.textContent = "选择" + button.dataset.label;
      search.value = "";
      search.hidden = form.elements[button.dataset.performanceChoose].options.length < 10;
      renderOptions();
      optionsDialog.showModal();
      optionsList.querySelector('[aria-pressed="true"]')?.scrollIntoView({ block: "nearest" });
      onVisibility();
    };
    form.elements[button.dataset.performanceChoose].addEventListener("change", syncChoices);
  }
  search.oninput = renderOptions;
  optionsDialog.querySelector("[data-close-options]").onclick = () => optionsDialog.close();
  optionsDialog.addEventListener("keydown", (event) => {
    if (event.key !== "Escape") return;
    event.preventDefault();
    optionsDialog.close();
  });
  optionsDialog.addEventListener("close", () => {
    if (optionsDialog.open) return;
    if (dialog.open) activeChoice?.focus({ preventScroll: true });
    activeChoice = undefined;
    onVisibility();
  });
  optionsList.onkeydown = (event) => {
    if (!["ArrowUp", "ArrowDown", "Home", "End"].includes(event.key)) return;
    const buttons = [...optionsList.querySelectorAll("button")];
    const index = buttons.indexOf(event.target);
    if (index < 0) return;
    const next = event.key === "Home" ? 0 : event.key === "End" ? buttons.length - 1 :
      Math.max(0, Math.min(buttons.length - 1, index + (event.key === "ArrowDown" ? 1 : -1)));
    event.preventDefault();
    buttons[next].focus();
  };

  function fill(field, items, emptyLabel) {
    const options = emptyLabel ? [new Option(emptyLabel, "")] : [];
    for (const item of items) {
      if (item.flag) options.push(new Option(item.name, item.flag));
    }
    field.replaceChildren(...options);
  }

  function loadOutfit(id) {
    const groups = outfitPerformances(context.groups[outfit.value]);
    preset.replaceChildren(
      ...groups.map((group) => new Option(group.name, group.id)),
      new Option("新演出", "")
    );
    preset.value = groups.some((group) => group.id === id) ? id : (id === "" ? "" : groups[0]?.id || "");
    loadPreset();
  }

  function loadPreset() {
    const costume = context.costumes.find((item) => item.flag === outfit.value);
    const groups = outfitPerformances(context.groups[costume.flag]);
    const saved = groups.find((group) => group.id === preset.value);
    name.value = saved?.name || `演出 ${groups.length + 1}`;
    dialog.querySelector("[data-clear-performance]").disabled = !saved;
    const choices = performanceChoices(context.catalog, costume);
    let missing = false;
    for (const kind of performanceTracks) {
      const field = form.elements[kind];
      fill(field, choices[kind], "不指定");
      const savedFlag = saved?.tracks[kind];
      const item = savedFlag && choices[kind].find(entry => matchesItemFlag(entry, savedFlag));
      field.value = item?.flag || "";
      if (savedFlag && !item) missing = true;
    }
    status.textContent = missing ? "部分素材已变更，请更新这套演出。" : "";
    syncChoices();
  }

  async function update(change) {
    if (fields.disabled) return;
    hideSavedToast();
    fields.disabled = true;
    try {
      await change();
    } catch (error) {
      status.textContent = error.message;
    } finally {
      fields.disabled = false;
    }
  }

  dialog.querySelector("[data-close-performance]").onclick = () => dialog.close();
  dialog.addEventListener("close", () => {
    hideSavedToast();
    if (optionsDialog.open) optionsDialog.close();
    onVisibility();
  });
  outfit.onchange = () => loadOutfit();
  preset.onchange = loadPreset;
  enabled.onchange = () => update(async () => {
    const checked = enabled.checked;
    enabled.checked = context.enabled;
    await setEnabled(context.catalog.id, checked);
    enabled.checked = context.enabled = checked;
  });
  dialog.querySelector("[data-add-performance]").onclick = () => { loadOutfit(""); name.focus(); };
  dialog.querySelector("[data-clear-performance]").onclick = () => update(async () => {
    const [flag, id] = [outfit.value, preset.value];
    if (!id) return;
    await save(context.catalog.id, flag, id, null);
    updateOutfitPerformances(context.groups, flag, id, null);
    loadOutfit();
    status.textContent = "已删除这组演出。";
  });
  form.onsubmit = (event) => {
    event.preventDefault();
    update(async () => {
      const flag = outfit.value;
      const tracks = {};
      for (const kind of performanceTracks) {
        const value = form.elements[kind].value;
        if (value) tracks[kind] = value;
      }
      if (!Object.keys(tracks).length) throw new Error("请选择至少一项动作、表情或语音。");
      const id = preset.value || crypto.randomUUID();
      const nameValue = name.value.trim();
      if (!nameValue) throw new Error("请输入演出名称。");
      const group = { name: nameValue, tracks };
      await save(context.catalog.id, flag, id, group);
      updateOutfitPerformances(context.groups, flag, id, group);
      loadOutfit(id);
      if (dialog.open) {
        savedToast.hidden = false;
        savedToast.textContent = "保存成功";
        savedToastTimer = setTimeout(hideSavedToast, 2200);
      }
    });
  };

  return () => {
    if (dialog.open || fields.disabled) return;
    const state = read();
    const costumes = state?.catalog?.costumes.filter((item) => item.flag) || [];
    if (!costumes.length) return;
    context = { ...state, costumes, groups: { ...state.groups } };
    fill(outfit, costumes);
    if (costumes.some((item) => item.flag === state.costume?.flag)) outfit.value = state.costume.flag;
    enabled.checked = state.enabled;
    loadOutfit();
    dialog.showModal();
    onVisibility();
  };
}
