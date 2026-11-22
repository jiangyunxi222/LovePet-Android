(() => {
  "use strict";

  const field = (entry, name, legacy = name.toLowerCase()) => entry?.[name] ?? entry?.[legacy];
  const records = value => Array.isArray(value) ? value : [];
  const motionGroup = (groups, wanted) => Object.keys(groups || {}).find(key =>
    key.toLowerCase().replace(/_/g, "").replace(/#\d+$/, "") === wanted);
  const ops = row => typeof field(row, "Command") === "string"
    ? field(row, "Command").split(";").slice(0, 128).map(task => task.trim().split(/\s+/)) : [];

  // The transparent v11 overlay used by our Workshop models has constant,
  // rectangular meshes. Decode just that layout and use ordinary drawable hits
  // for models whose hit areas are part of the visible Live2D model.
  function overlayRegions(bytes, areas, modern) {
    const data = new DataView(bytes);
    if (data.byteLength < 24 || data.getUint32(0) !== 0x6d6f630b) return [];
    const end = data.byteLength - 12;
    const width = data.getInt32(end);
    const height = data.getInt32(end + 4);
    if (width <= 0 || height <= 0) return [];
    const input = new Uint8Array(bytes);
    const mesh = new Set(areas.map(area => area.id));
    const decoder = new TextDecoder();
    const result = [];
    for (let offset = 4; offset + 105 < end; offset++) {
      if (input[offset] !== 70 || input[offset + 1] !== 50) continue;
      const length = input[offset + 2];
      if (length > 80) continue;
      const name = decoder.decode(input.subarray(offset + 3, offset + 3 + length));
      if (!mesh.has(name)) continue;
      const header = offset + 3 + length;
      if (input[header] !== 51 || decoder.decode(input.subarray(header + 2, header + 10)) !== "DST_BASE") continue;
      const marker = header + 66;
      if (marker + 36 > end || data.getUint32(marker) !== 0x0f011b08) continue;
      const left = data.getFloat32(marker + 4);
      const top = data.getFloat32(marker + 8);
      const right = data.getFloat32(marker + 12);
      const bottom = data.getFloat32(marker + 24);
      if (![left, top, right, bottom].every(Number.isFinite) || left >= right || top >= bottom) continue;
      const box = modern
        ? [left / width * 2 - 1, top / height * 2 - 1, right / width * 2 - 1, bottom / height * 2 - 1]
        : [left, top, right, bottom];
      result.push({ ...areas.find(area => area.id === name), box });
    }
    return result;
  }

  async function load(url) {
    const response = await fetch(url);
    if (!response.ok) throw new Error("模型交互配置读取失败");
    const descriptor = await response.json();
    const graph = new Map(Object.entries(descriptor.models));
    const root = graph.get(descriptor.root);
    const path = filename => new URL(filename, new URL(url, location.href)).href;
    const prepared = new Map();
    const addresses = new Map([...graph].map(([name, record]) => [record, name]));
    const visible = record => records(field(record?.model, "Textures")).length > 0;

    async function prepare(key) {
      if (prepared.has(key)) return prepared.get(key);
      const selected = graph.get(key);
      const overlay = [...graph.values()].find(record => selected.id && record.id === selected.id + "_hits");
      const activeModels = new Map([[root.id, root], [selected.id, selected]]);
      if (overlay) activeModels.set(overlay.id, overlay);
      const groups = new Map();
      const choices = new Map();
      const motions = {};
      const expressions = [];
      const keyFor = (a, b) => selected.modern ? a : b;

      function action(record, row) {
        const result = [];
        const file = record.resources[field(row, "File")];
        const sound = record.resources[field(row, "Sound")];
        if (file && record === selected) {
          const group = groups.get(file) ?? "viewer_" + groups.size;
          groups.set(file, group);
          const fadeIn = selected.modern ? row.FadeInTime ?? row.FadeIn / 1000 : row.fade_in;
          const fadeOut = selected.modern ? row.FadeOutTime ?? row.FadeOut / 1000 : row.fade_out;
          motions[group] = [{
            [keyFor("File", "file")]: path(file),
            ...(Number.isFinite(fadeIn) ? { [keyFor("FadeInTime", "fade_in")]: fadeIn } : {}),
            ...(Number.isFinite(fadeOut) ? { [keyFor("FadeOutTime", "fade_out")]: fadeOut } : {}),
          }];
          result.push({ motion: group });
        }
        if (sound) result.push({ sound: new URL(path(sound)).pathname,
          text: String(field(row, "Text") || "").slice(0, 1000) });
        if (result.length === 2) return [{ ...result[0], ...result[1] }];
        return result;
      }

      function expression(file, name = file) {
        if (!expressions.some(item => field(item, "Name") === name)) {
          expressions.push({ [keyFor("Name", "name")]: name, [keyFor("File", "file")]: path(file) });
        }
        return { expression: name };
      }
      for (const entry of records(selected.expressions)) {
        const file = selected.resources[field(entry, "File")];
        if (file) expression(file, field(entry, "Name") || file);
      }

      // Resolve only the supported data operations. Packages never supply executable code.
      let remainingSteps;
      function trace(record, group, visited = new Set()) {
        const address = addresses.get(record) + "/" + group;
        if (!record || !group || visited.has(address) || visited.size >= 12) return [];
        const seen = new Set(visited).add(address);
        const results = [];
        for (const row of records(record.groups[group]).slice(0, 512)) {
          if (--remainingSteps < 0) break;
          if (!row || typeof row !== "object") continue;
          const direct = action(record, row);
          results.push(...direct);
          for (const [op, target, argument] of ops(row)) {
            if (op === "set_exp" && record === selected && record.resources[target]) {
              results.push(expression(record.resources[target]));
            } else if (op === "start_mtn" && !direct.length) {
              const targetModel = argument ? activeModels.get(target) : record;
              results.push(...trace(targetModel, argument || target, seen));
            } else if (op === "add_submodel" && target !== key && visible(graph.get(target))) {
              results.push({ outfit: target });
            }
          }
          const next = field(row, "NextMtn", "next_mtn");
          if (!direct.length && next && !/^idle$/i.test(next)) results.push(...trace(record, next, seen));
          if (results.length > 1024) break;
        }
        return results;
      }

      function resolve(record, group) {
        remainingSteps = 4096;
        return trace(record, group);
      }

      const standard = area => {
        const name = String(field(area, "Name") || "");
        const id = field(area, "Id");
        const group = field(area, "Motion") || motionGroup(selected.groups, "tap" + name.toLowerCase()) ||
          motionGroup(selected.groups, name.toLowerCase());
        return { id, name, group, order: Number(field(area, "Order")) || 0 };
      };
      const sourceAreas = records((overlay || selected).areas).slice(0, 128).map(standard).filter(area => typeof area.id === "string");
      let areas = sourceAreas;
      if (overlay && !overlay.modern) {
        const file = await fetch(path(overlay.model.model));
        if (file.ok) areas = overlayRegions(await file.arrayBuffer(), sourceAreas, selected.modern);
      }
      const routes = areas.map(area => {
        // The app menu is independent of a package's native menu scripts.
        const actions = area.id === "menu" && overlay ? [] : resolve(overlay || selected, area.group);
        choices.set(area.id, [...new Map(actions.map(item => [JSON.stringify(item), item])).values()]);
        return area;
      }).sort((a, b) => b.order - a.order);
      const tap = motionGroup(selected.groups, "tap");
      if (tap) choices.set("", resolve(selected, tap));
      if (!routes.length) {
        const body = motionGroup(selected.groups, "tapbody");
        if (body && !tap) choices.set("", resolve(selected, body));
      }
      const idle = motionGroup(selected.groups, "idle");
      if (idle) {
        const first = resolve(selected, idle).find(item => item.motion);
        if (first) motions[keyFor("Idle", "idle")] = motions[first.motion];
      }
      const refs = {};
      for (const [name, value] of Object.entries(selected.model)) refs[name] = Array.isArray(value) ? value.map(path) : path(value);
      refs[keyFor("Motions", "motions")] = motions;
      refs[keyFor("Expressions", "expressions")] = expressions;
      if (!overlay && routes.length) refs[keyFor("HitAreas", "hit_areas")] = routes.map(({ id, name }) =>
        ({ [keyFor("Id", "id")]: id, [keyFor("Name", "name")]: name }));
      const metadata = selected.modern ? { Version: 3, FileReferences: refs, HitAreas: refs.HitAreas } : refs;
      metadata.url = new URL(url, location.href).href;
      metadata.pageMouthIds = selected.modern ? ["ParamMouthOpenY"] : ["PARAM_MOUTH_OPEN_Y"];
      const result = {
        metadata,
        modern: selected.modern,
        hit(model, x, y) {
          const point = model.toModelPosition(new PIXI.Point(x, y));
          const internal = model.internalModel;
          if (selected.modern && overlay) {
            point.x = (point.x - internal.originalWidth / 2) / internal.pixelsPerUnit;
            point.y = (point.y - internal.originalHeight / 2) / internal.pixelsPerUnit;
          }
          const names = overlay ? [] : model.hitTest(x, y);
          return routes.find(({ name, box }) => box
            ? point.x >= box[0] && point.x <= box[2] && point.y >= box[1] && point.y <= box[3]
            : names.includes(name))?.id;
        },
        touchBoxes(model) {
          const internal = model.internalModel;
          return routes.flatMap(({ name, box }) => {
            if (box) return [{ box, normalized: selected.modern && Boolean(overlay) }];
            const drawable = internal.hitAreas[name]?.index;
            if (overlay || !Number.isInteger(drawable)) return [];
            const { x, y, width, height } = internal.getDrawableBounds(drawable);
            return [{ box: [x, y, x + width, y + height] }];
          });
        },
        choose(region) {
          const candidates = choices.get(region ?? "") || [];
          return candidates[Math.floor(Math.random() * candidates.length)];
        },
        hasTap: (choices.get("") || []).length > 0,
      };
      prepared.set(key, result);
      return result;
    }
    return { select: key => visible(graph.get(key)) ? key : descriptor.initial, prepare };
  }

  window.viewerInteraction = { load };
})();
