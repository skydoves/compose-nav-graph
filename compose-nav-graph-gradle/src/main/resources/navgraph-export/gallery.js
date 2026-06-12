// Self-contained gallery interactions: theme toggle (auto / light / dark), name filter, collapsible sections.
(function () {
  var root = document.documentElement;
  var KEY = "navgraph-gallery-theme";

  function applyTheme(t) {
    if (t === "light" || t === "dark") {
      root.setAttribute("data-theme", t);
    } else {
      root.removeAttribute("data-theme");
    }
  }
  applyTheme(localStorage.getItem(KEY));

  var themeBtn = document.getElementById("theme");
  if (themeBtn) {
    themeBtn.addEventListener("click", function () {
      var cur = localStorage.getItem(KEY) || "auto";
      var next = cur === "auto" ? "light" : cur === "light" ? "dark" : "auto";
      if (next === "auto") {
        localStorage.removeItem(KEY);
      } else {
        localStorage.setItem(KEY, next);
      }
      applyTheme(next);
    });
  }

  var search = document.getElementById("search");
  if (search) {
    search.addEventListener("input", function () {
      var q = search.value.trim().toLowerCase();
      document.querySelectorAll(".module").forEach(function (mod) {
        var modHits = 0;
        mod.querySelectorAll(".pkg").forEach(function (pkg) {
          var hits = 0;
          pkg.querySelectorAll(".card").forEach(function (card) {
            var name = card.getAttribute("data-name") || "";
            var match = !q || name.indexOf(q) !== -1;
            card.style.display = match ? "" : "none";
            if (match) hits++;
          });
          pkg.style.display = hits ? "" : "none";
          modHits += hits;
        });
        mod.style.display = modHits ? "" : "none";
      });
    });
  }

  // Group toggle: per-module sections (default) <-> merged (module headers hidden, packages still grouped).
  var GROUP_KEY = "navgraph-gallery-group";
  var groupBtn = document.createElement("button");
  groupBtn.id = "group";
  groupBtn.className = "btn";
  groupBtn.title = "Group by module / merge modules";
  var searchEl = document.getElementById("search");
  if (searchEl && searchEl.parentNode) {
    searchEl.parentNode.insertBefore(groupBtn, searchEl.nextSibling);
  }
  function applyGroup(mode) {
    document.body.classList.toggle("flat", mode === "flat");
    groupBtn.textContent = mode === "flat" ? "▤ Merged" : "▥ By module";
  }
  var groupMode = localStorage.getItem(GROUP_KEY) === "flat" ? "flat" : "module";
  applyGroup(groupMode);
  groupBtn.addEventListener("click", function () {
    groupMode = groupMode === "flat" ? "module" : "flat";
    localStorage.setItem(GROUP_KEY, groupMode);
    applyGroup(groupMode);
  });

  // Lightbox: click any thumbnail to enlarge; click the backdrop or press Esc to close.
  var lightbox = document.createElement("div");
  lightbox.className = "lightbox";
  lightbox.innerHTML = "<figure style=\"margin:0;display:flex;flex-direction:column;" +
    "align-items:center;gap:10px;max-height:100%\"><img alt=\"\"><figcaption></figcaption></figure>";
  document.body.appendChild(lightbox);
  var lbImg = lightbox.querySelector("img");
  var lbCap = lightbox.querySelector("figcaption");
  function closeLightbox() {
    lightbox.classList.remove("open");
    lbImg.removeAttribute("src");
  }
  lightbox.addEventListener("click", closeLightbox);
  document.addEventListener("keydown", function (e) {
    if (e.key === "Escape") closeLightbox();
  });
  document.querySelectorAll(".thumb img").forEach(function (img) {
    img.addEventListener("click", function () {
      lbImg.src = img.src;
      var card = img.closest(".card");
      var cap = card ? card.querySelector("figcaption") : null;
      lbCap.textContent = cap ? cap.textContent : (img.getAttribute("alt") || "");
      lightbox.classList.add("open");
    });
  });

  // Invoked from the inline onclick on each module/package header.
  window.navgraphToggle = function (el) {
    var section = el.parentElement;
    if (section) section.classList.toggle("collapsed");
  };
})();
