(() => {
  "use strict";
  const themes = [
    ["rose", "蔷薇", "#aa5279", "#f6e4ec"], ["peony", "牡丹", "#ac4160", "#f9e3ea"],
    ["peach", "蜜桃", "#a45244", "#f8e7df"], ["amber", "琥珀", "#916020", "#f6edd9"],
    ["olive", "橄榄", "#69713c", "#edf0df"], ["fern", "蕨绿", "#3f7155", "#e0efe6"],
    ["mint", "薄荷", "#337569", "#e0f1eb"], ["lagoon", "海玻璃", "#327584", "#e0f1f3"],
    ["sky", "晴空", "#406da3", "#e3edf9"], ["indigo", "靛蓝", "#535d9a", "#e8eafa"],
    ["iris", "鸢尾", "#72578f", "#eee6f6"], ["plum", "梅紫", "#855375", "#f3e6ef"],
  ];
  const root = document.documentElement;
  const dialog = document.querySelector("#theme-settings");
  const grid = document.querySelector("#theme-grid");
  const entry = document.querySelector("#open-theme");
  const current = document.querySelector("#theme-current");

  function mix(first, weight, second) {
    const rgb = color => color.slice(1).match(/../g).map(channel => parseInt(channel, 16));
    const base = rgb(second);
    return '#' + rgb(first).map((component, index) =>
      Math.round(component * weight + base[index] * (1 - weight)).toString(16).padStart(2, "0")).join("");
  }

  function apply(id) {
    const [selected, name, accent, tint] = themes.find(theme => theme[0] === id) || themes[0];
    root.dataset.theme = selected;
    root.style.setProperty("--theme-accent", accent);
    root.style.setProperty("--theme-tint", tint);
    root.style.setProperty("--theme-dark", mix(accent, .35, "#302936"));
    root.style.setProperty("--theme-muted", mix(accent, .45, "#69626d"));
    root.style.setProperty("--theme-line", mix(accent, .16, "#f6f4f7"));
    root.style.setProperty("--theme-surface", mix(tint, .22, "#ffffff"));
    root.style.setProperty("--theme-wash", mix(tint, .44, "#ffffff"));
    root.style.setProperty("--theme-active", mix(accent, .24, "#ffffff"));
    current.textContent = name;
    for (const button of grid.children) button.setAttribute("aria-pressed", String(button.dataset.theme === selected));
  }

  for (const [id, name, accent, tint] of themes) {
    const button = document.createElement("button");
    button.type = "button";
    button.className = "theme-tile";
    button.dataset.theme = id;
    const color = document.createElement("span");
    color.className = "theme-sample";
    color.setAttribute("aria-hidden", "true");
    color.style.setProperty("--sample-accent", accent);
    color.style.setProperty("--sample-tint", tint);
    button.append(color, document.createTextNode(name));
    button.onclick = () => {
      window.AndroidPet.setTheme(id);
      apply(id);
    };
    grid.append(button);
  }
  window.refreshAppTheme = () => apply(window.AndroidPet.theme());
  window.refreshAppTheme();
  entry.onclick = () => dialog.showModal();
  document.querySelector("#theme-close").onclick = () => dialog.close();
})();
