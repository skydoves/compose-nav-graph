/*
 * Copyright (C) 2026 Jaewoong Eum (skydoves)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// Self-contained interactivity for the exported navgraph graph. Everything is derived from the DOM
// (node/edge elements carry data-* attributes), so there is no JSON parsing or escaping to trust.
(function () {
  "use strict";
  const $ = (s, r) => (r || document).querySelector(s);
  const $$ = (s, r) => Array.prototype.slice.call((r || document).querySelectorAll(s));

  const stage = $("#stage");
  const canvas = $("#canvas");
  if (!stage || !canvas) return;

  const nodeEls = $$(".node", canvas);
  const groups = $$(".edge-g", canvas);
  const nodeById = new Map();
  const rowByIdx = new Map();
  nodeEls.forEach((el) => nodeById.set(el.dataset.id, el));
  $$("#details tbody tr").forEach((tr) => rowByIdx.set(tr.dataset.idx, tr));

  // Adjacency + incident edge groups, indexed once.
  const out = new Map();
  const inc = new Map();
  const incident = new Map();
  const push = (m, k, v) => { (m.get(k) || m.set(k, []).get(k)).push(v); };
  const add = (m, k, v) => { (m.get(k) || m.set(k, new Set()).get(k)).add(v); };
  groups.forEach((g) => {
    const f = g.dataset.from, t = g.dataset.to;
    push(out, f, t); push(inc, t, f);
    add(incident, f, g); add(incident, t, g);
  });

  // ── Pan / zoom ──────────────────────────────────────────────────────────
  const contentW = canvas.offsetWidth || 1;
  const contentH = canvas.offsetHeight || 1;
  let scale = 1, tx = 0, ty = 0;
  const clamp = (v, lo, hi) => Math.min(Math.max(v, lo), hi);

  function apply() {
    canvas.style.transform = "translate(" + tx + "px," + ty + "px) scale(" + scale + ")";
    drawViewport();
  }
  function fit() {
    const r = stage.getBoundingClientRect();
    scale = clamp(Math.min(r.width / contentW, r.height / contentH) * 0.96, 0.1, 2);
    tx = (r.width - contentW * scale) / 2;
    ty = (r.height - contentH * scale) / 2;
    apply();
  }
  function zoomAround(factor, cx, cy) {
    const ns = clamp(scale * factor, 0.15, 3);
    const k = ns / scale;
    tx = cx - (cx - tx) * k;
    ty = cy - (cy - ty) * k;
    scale = ns;
    apply();
  }
  stage.addEventListener("wheel", (e) => {
    e.preventDefault();
    const r = stage.getBoundingClientRect();
    zoomAround(Math.exp(-e.deltaY * 0.0015), e.clientX - r.left, e.clientY - r.top);
  }, { passive: false });

  let dragging = false, sx = 0, sy = 0;
  stage.addEventListener("pointerdown", (e) => {
    if (e.target.closest(".node") || e.target.closest(".minimap")) return;
    dragging = true; sx = e.clientX - tx; sy = e.clientY - ty;
    stage.classList.add("grabbing");
    if (e.target === stage || e.target === canvas) clearPin();
    try { stage.setPointerCapture(e.pointerId); } catch (_) {}
  });
  stage.addEventListener("pointermove", (e) => {
    if (!dragging) return;
    tx = e.clientX - sx; ty = e.clientY - sy; apply();
  });
  const endDrag = () => { dragging = false; stage.classList.remove("grabbing"); };
  stage.addEventListener("pointerup", endDrag);
  stage.addEventListener("pointercancel", endDrag);

  const zEl = (id) => $("#" + id);
  if (zEl("zin")) zEl("zin").onclick = () => zoomAround(1.2, stage.clientWidth / 2, stage.clientHeight / 2);
  if (zEl("zout")) zEl("zout").onclick = () => zoomAround(1 / 1.2, stage.clientWidth / 2, stage.clientHeight / 2);
  if (zEl("zfit")) zEl("zfit").onclick = fit;

  // ── Hover highlight + click-to-trace ───────────────────────────────────
  let pinned = null;

  function highlight(id) {
    canvas.classList.add("dim");
    const el = nodeById.get(id); if (el) el.classList.add("hl");
    (out.get(id) || []).forEach((t) => { const n = nodeById.get(t); if (n) n.classList.add("hl"); });
    (inc.get(id) || []).forEach((f) => { const n = nodeById.get(f); if (n) n.classList.add("hl"); });
    (incident.get(id) || new Set()).forEach((g) => g.classList.add("active"));
  }
  function clearTransient() {
    canvas.classList.remove("dim");
    nodeEls.forEach((n) => n.classList.remove("hl"));
    groups.forEach((g) => g.classList.remove("active"));
  }
  function trace(id) {
    const seen = new Set([id]);
    const queue = [id];
    while (queue.length) {
      const cur = queue.shift();
      (out.get(cur) || []).forEach((t) => { if (!seen.has(t)) { seen.add(t); queue.push(t); } });
    }
    canvas.classList.add("dim");
    nodeEls.forEach((n) => n.classList.toggle("traced", seen.has(n.dataset.id)));
    groups.forEach((g) => g.classList.toggle("active", seen.has(g.dataset.from) && seen.has(g.dataset.to)));
  }
  function clearPin() {
    pinned = null;
    nodeEls.forEach((n) => n.classList.remove("traced"));
    clearTransient();
  }

  nodeEls.forEach((el) => {
    const id = el.dataset.id;
    el.addEventListener("mouseenter", () => { if (!pinned) highlight(id); });
    el.addEventListener("mouseleave", () => { if (!pinned) clearTransient(); });
    el.addEventListener("click", (e) => {
      e.stopPropagation();
      if (pinned === id) { clearPin(); return; }
      pinned = id;
      clearTransient();
      nodeEls.forEach((n) => n.classList.remove("traced"));
      trace(id);
      flashRow(el.dataset.idx);
    });
  });

  document.addEventListener("keydown", (e) => { if (e.key === "Escape") { clearPin(); $("#search").blur(); } });

  // ── Search / filter (syncs diagram + table) ────────────────────────────
  const search = $("#search");
  if (search) {
    search.addEventListener("input", () => {
      const q = search.value.trim().toLowerCase();
      nodeEls.forEach((el) => {
        const hit = !q || (el.dataset.route || "").toLowerCase().includes(q) ||
          (el.dataset.args || "").toLowerCase().includes(q);
        el.classList.toggle("filtered", !hit);
        const row = rowByIdx.get(el.dataset.idx);
        if (row) row.classList.toggle("filtered", !hit);
      });
      groups.forEach((g) => {
        const fh = nodeById.get(g.dataset.from);
        const th = nodeById.get(g.dataset.to);
        g.classList.toggle("filtered", (fh && fh.classList.contains("filtered")) || (th && th.classList.contains("filtered")));
      });
    });
  }

  // ── Node ↔ table-row linking ───────────────────────────────────────────
  function flashRow(idx) {
    const row = rowByIdx.get(idx);
    if (!row) return;
    row.scrollIntoView({ behavior: "smooth", block: "center" });
    row.classList.remove("flash"); void row.offsetWidth; row.classList.add("flash");
  }
  function centerNode(el) {
    const r = stage.getBoundingClientRect();
    tx = r.width / 2 - (el.offsetLeft + el.offsetWidth / 2) * scale;
    ty = r.height / 2 - (el.offsetTop + el.offsetHeight / 2) * scale;
    apply();
    el.classList.remove("flash"); void el.offsetWidth; el.classList.add("flash");
  }
  rowByIdx.forEach((row, idx) => {
    row.addEventListener("click", () => {
      const el = nodeById.get(row.dataset.id);
      if (el) { stage.scrollIntoView({ behavior: "smooth", block: "start" }); centerNode(el); }
    });
  });

  // ── Theme toggle (auto → light → dark) ─────────────────────────────────
  const root = document.documentElement;
  const themeBtn = $("#theme");
  const ICON = { auto: "🌗", light: "☀", dark: "🌙" };
  let theme = localStorage.getItem("navgraph-theme") || "auto";
  function applyTheme() {
    if (theme === "auto") root.removeAttribute("data-theme");
    else root.setAttribute("data-theme", theme);
    if (themeBtn) themeBtn.textContent = ICON[theme];
  }
  applyTheme();
  if (themeBtn) themeBtn.onclick = () => {
    theme = theme === "auto" ? "light" : theme === "light" ? "dark" : "auto";
    localStorage.setItem("navgraph-theme", theme);
    applyTheme();
  };

  // ── Minimap ─────────────────────────────────────────────────────────────
  const MM_W = 184;
  const mmScale = MM_W / contentW;
  const mmH = Math.round(contentH * mmScale);
  const mm = $("#minimap");
  let mmView = null;
  if (mm) {
    const NS = "http://www.w3.org/2000/svg";
    const svg = document.createElementNS(NS, "svg");
    svg.setAttribute("width", MM_W); svg.setAttribute("height", mmH);
    nodeEls.forEach((el) => {
      const r = document.createElementNS(NS, "rect");
      r.setAttribute("x", el.offsetLeft * mmScale); r.setAttribute("y", el.offsetTop * mmScale);
      r.setAttribute("width", el.offsetWidth * mmScale); r.setAttribute("height", el.offsetHeight * mmScale);
      r.setAttribute("rx", 2);
      r.setAttribute("class", "mm-node" + (el.classList.contains("start") ? " start" : ""));
      svg.appendChild(r);
    });
    mmView = document.createElementNS(NS, "rect");
    mmView.setAttribute("class", "mm-view");
    svg.appendChild(mmView);
    mm.appendChild(svg);

    const panTo = (e) => {
      const r = mm.getBoundingClientRect();
      const wx = (e.clientX - r.left) / mmScale, wy = (e.clientY - r.top) / mmScale;
      tx = stage.clientWidth / 2 - wx * scale; ty = stage.clientHeight / 2 - wy * scale; apply();
    };
    let mmDrag = false;
    mm.addEventListener("pointerdown", (e) => { mmDrag = true; panTo(e); try { mm.setPointerCapture(e.pointerId); } catch (_) {} });
    mm.addEventListener("pointermove", (e) => { if (mmDrag) panTo(e); });
    mm.addEventListener("pointerup", () => { mmDrag = false; });
  }
  function drawViewport() {
    if (!mmView) return;
    mmView.setAttribute("x", (-tx / scale) * mmScale);
    mmView.setAttribute("y", (-ty / scale) * mmScale);
    mmView.setAttribute("width", (stage.clientWidth / scale) * mmScale);
    mmView.setAttribute("height", (stage.clientHeight / scale) * mmScale);
  }

  // initial framing
  fit();
  window.addEventListener("resize", drawViewport);
})();
