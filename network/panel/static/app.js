/** El Cartel panel — frontend */
const $ = (sel) => document.querySelector(sel);
const $$ = (sel) => document.querySelectorAll(sel);

let state = {
  overview: null,
  shards: [],
  modes: [],
  servers: null,
  sessions: [],
  authRequired: false,
  panelToken: localStorage.getItem("elcartel_panel_token") || "",
  deleteTarget: null,
};

const TITLES = {
  dashboard: "Dashboard",
  shards: "Shardy",
  sectors: "Sektory",
  servers: "Serwery",
  sessions: "Sesje",
  redis: "Redis",
  dev: "Dev tools",
  logs: "Logi",
};

function toast(msg, isError = false) {
  const el = $("#toast");
  el.textContent = msg;
  el.className = "toast show" + (isError ? " error" : "");
  setTimeout(() => el.classList.remove("show"), 3500);
}

function authHeaders() {
  const h = { "Content-Type": "application/json" };
  const tok = state.panelToken || $("#sidebarToken")?.value?.trim();
  if (tok) h.Authorization = `Bearer ${tok}`;
  return h;
}

function saveTokenFromSidebar() {
  const tok = $("#sidebarToken")?.value?.trim();
  if (tok) {
    state.panelToken = tok;
    localStorage.setItem("elcartel_panel_token", tok);
  }
}

async function api(path, opts = {}) {
  const res = await fetch(path, { ...opts, headers: { ...authHeaders(), ...opts.headers } });
  if (!res.ok) {
    const err = await res.json().catch(() => ({ detail: res.statusText }));
    throw new Error(err.detail || res.statusText);
  }
  return res.json();
}

function fmtTime(ms) {
  if (!ms) return "—";
  return new Date(ms).toLocaleTimeString("pl-PL");
}

function fmtAge(ms) {
  if (ms == null) return "—";
  if (ms < 1000) return `${ms} ms`;
  return `${(ms / 1000).toFixed(1)} s`;
}

function loadPct(players, cap) {
  if (!cap) return 0;
  return Math.min(100, Math.round((players / cap) * 100));
}

function barClass(pct) {
  if (pct >= 95) return "crit";
  if (pct >= 75) return "warn";
  return "";
}

function statusBadge(status) {
  const labels = {
    online: "Online",
    offline: "Offline",
    starting: "Startuje",
    stale: "Nieaktualny",
    redis_only: "Redis",
  };
  return `<span class="badge ${status}">${labels[status] || status}</span>`;
}

function stateBadge(st) {
  if (st === "FULL") return '<span class="badge full">FULL</span>';
  if (st === "OPEN") return '<span class="badge open">OPEN</span>';
  return `<span class="badge stale">${st || "?"}</span>`;
}

function renderConnPills(o) {
  const r = o.redis?.connected;
  const m = o.mongo?.connected;
  const p = o.proxy?.online;
  $("#connRedis").className = "pill " + (r ? "ok" : "bad");
  $("#connRedis").textContent = r ? "Redis OK" : "Redis OFF";
  $("#connMongo").className = "pill " + (m ? "ok" : "bad");
  $("#connMongo").textContent = m ? "Mongo OK" : "Mongo OFF";
  $("#connProxy").className = "pill " + (p ? "ok" : "bad");
  $("#connProxy").textContent = p ? `Proxy ${o.proxy.playersOnline}/${o.proxy.playersMax}` : "Proxy OFF";
  $("#lastUpdate").textContent = fmtTime(o.timestamp);
}

function renderCards(o) {
  const el = $("#statCards");
  const cards = [
    { label: "Gracze (shardy)", value: o.shards.players, sub: `${o.shards.online} shardów online` },
    { label: "Shardy", value: o.shards.total, sub: `${o.shards.online} aktywnych w Redis` },
    { label: "Procesy MC", value: o.servers.listening, sub: `z ${o.servers.total} katalogów` },
    { label: "Konta", value: o.mongo?.players ?? "—", sub: o.mongo?.connected ? `baza ${o.mongo.database}` : "Mongo offline" },
  ];
  el.innerHTML = cards.map((c) => `
    <div class="card">
      <div class="card-label">${c.label}</div>
      <div class="card-value">${c.value}</div>
      <div class="card-sub">${c.sub}</div>
    </div>`).join("");
}

function renderModesTable(modes) {
  if (!modes.length) return '<p class="empty">Brak trybów w templates/</p>';
  return `<table>
    <thead><tr><th>Tryb</th><th>Shardy</th><th>Gracze</th><th>Port base</th><th>Cap</th></tr></thead>
    <tbody>${modes.map((m) => {
      const pct = loadPct(m.totalPlayers, m.softCap * Math.max(m.liveShards, 1));
      return `<tr>
        <td><strong>${m.displayName}</strong><br><code>${m.id}</code></td>
        <td>${m.liveShards} / ${m.localShards}</td>
        <td>
          ${m.totalPlayers}
          <div class="bar-wrap"><div class="bar-fill ${barClass(pct)}" style="width:${pct}%"></div></div>
        </td>
        <td>${m.portBase}</td>
        <td>${m.softCap} / ${m.hardCap}</td>
      </tr>`;
    }).join("")}</tbody>
  </table>`;
}

function renderLiveShards(shards) {
  const live = shards.filter((s) => s.status === "online");
  if (!live.length) return '<p class="empty">Brak aktywnych shardów</p>';
  return `<div class="live-list">${live.map((s) => {
    const pct = loadPct(s.players || 0, s.softCap || s.hardCap || 1);
    return `<div class="live-item linkish btn-detail" data-id="${s.id}" style="cursor:pointer">
      <code>${s.id}</code>
      ${stateBadge(s.state)}
      <span>${s.players || 0}/${s.softCap || "?"}</span>
      <div class="bar-wrap" style="flex:1"><div class="bar-fill ${barClass(pct)}" style="width:${pct}%"></div></div>
      <span style="color:var(--text-muted);font-size:0.8rem">${(s.tps || 0).toFixed(1)} TPS</span>
    </div>`;
  }).join("")}</div>`;
}

function renderShardsTable(shards) {
  if (!shards.length) return '<p class="empty">Brak shardow — uzyj „+ Nowy shard”</p>';
  return `<table>
    <thead><tr>
      <th>ID</th><th>Tryb</th><th>Status</th><th>Stan</th><th>Gracze</th><th>TPS</th><th>Port</th><th>Redis</th><th>Akcje</th>
    </tr></thead>
    <tbody>${shards.map((s) => {
      const pct = loadPct(s.players || 0, s.softCap || 1);
      const run = s.listening;
      const redisTag = s.inRedis
        ? (s.redisOnly ? '<span class="badge stale">tylko Redis</span>' : '<span class="badge open">tak</span>')
        : '<span class="badge offline">nie</span>';
      return `<tr class="clickable shard-row" data-id="${s.id}">
        <td><code>${s.id}</code></td>
        <td>${s.mode || "—"}</td>
        <td>${statusBadge(s.status)}</td>
        <td>${stateBadge(s.state)}</td>
        <td>
          ${s.players ?? "—"}/${s.softCap || "?"}
          <div class="bar-wrap"><div class="bar-fill ${barClass(pct)}" style="width:${pct}%"></div></div>
        </td>
        <td>${s.tps != null ? s.tps.toFixed(1) : "—"}</td>
        <td>${s.port || "—"}</td>
        <td>${redisTag}</td>
        <td class="server-actions" onclick="event.stopPropagation()">
          ${run
            ? `<button class="btn ghost sm btn-stop" data-id="${s.id}">Stop</button>`
            : `<button class="btn success sm btn-start" data-id="${s.id}">Start</button>`}
          <button class="btn ghost sm btn-detail" data-id="${s.id}">Info</button>
        </td>
      </tr>`;
    }).join("")}</tbody>
  </table>`;
}

function renderServerRow(s) {
  if (!s) return "";
  const dot = s.listening ? "online" : "offline";
  const run = s.listening;
  return `<div class="server-row">
    <span class="badge ${dot}">${s.listening ? "LISTEN" : "DOWN"}</span>
    <span class="name">${s.id}</span>
    <span class="meta">${s.role}${s.mode ? ` · ${s.mode}` : ""} · port ${s.port}${s.motd ? ` · ${s.motd}` : ""}${s.diskMb ? ` · ${s.diskMb} MB` : ""}</span>
    <div class="server-actions">
      ${run
        ? `<button class="btn ghost sm btn-stop" data-id="${s.id}">Stop</button>`
        : `<button class="btn success sm btn-start" data-id="${s.id}">Start</button>`}
      <button class="btn ghost sm btn-log" data-id="${s.id}">Log</button>
    </div>
  </div>`;
}

function renderServersView(srv) {
  let html = "";
  html += `<div class="server-group"><h3>Proxy</h3>${renderServerRow({ ...srv.velocity, role: "proxy" })}</div>`;
  html += `<div class="server-group"><h3>Limbo (auth)</h3>${renderServerRow(srv.limbo ? { ...srv.limbo, role: "limbo" } : null)}</div>`;
  html += `<div class="server-group"><h3>Shardy</h3>${srv.shards.map((s) => renderServerRow(s)).join("") || '<p class="empty">Brak</p>'}</div>`;
  if (srv.other?.length) {
    html += `<div class="server-group"><h3>Inne</h3>${srv.other.map((s) => renderServerRow(s)).join("")}</div>`;
  }
  return html;
}

function renderSessionsTable(sessions) {
  if (!sessions.length) return '<p class="empty">Brak sesji w Redis</p>';
  return `<table>
    <thead><tr><th>UUID</th><th>Proxy</th><th>Shard</th><th>Czas</th><th></th></tr></thead>
    <tbody>${sessions.map((s) => `<tr>
      <td><code style="font-size:0.75rem">${s.uuid}</code></td>
      <td>${s.proxy || "—"}</td>
      <td>${s.shard ? `<span class="linkish btn-detail" data-id="${s.shard}">${s.shard}</span>` : "—"}</td>
      <td>${fmtTime(s.ts)}</td>
      <td><button class="btn ghost sm btn-del-session" data-uuid="${s.uuid}">Usun</button></td>
    </tr>`).join("")}</tbody>
  </table>`;
}

function renderRedisOverview(data) {
  if (!data) return '<p class="empty">Ladowanie...</p>';
  const kc = data.keyCounts || {};
  let html = `<div class="detail-grid">
    <dt>shards:index</dt><dd>${data.shardIds?.length ?? 0} wpisow</dd>
    <dt>Swieze</dt><dd>${(data.fresh || []).join(", ") || "—"}</dd>
    <dt>Martwe</dt><dd>${(data.stale || []).join(", ") || "—"}</dd>
    <dt>session:*</dt><dd>${kc["session:*"] ?? "?"}</dd>
    <dt>lock:profile:*</dt><dd>${kc["lock:profile:*"] ?? "?"}</dd>
  </div>`;
  if (data.modeSets && Object.keys(data.modeSets).length) {
    html += '<h4 style="margin-top:1rem;font-size:0.75rem;color:var(--text-muted)">shards:mode:*</h4>';
    for (const [mode, ids] of Object.entries(data.modeSets)) {
      html += `<div class="redis-key"><code>shards:mode:${mode}</code><pre>${ids.join(", ") || "(pusty)"}</pre></div>`;
    }
  }
  return html;
}

function renderRedisKeys(keys) {
  if (!keys?.length) return '<p class="empty">Brak kluczy</p>';
  return keys.map((k) => {
    let val = "";
    if (k.value != null) {
      val = typeof k.value === "object" ? JSON.stringify(k.value, null, 2) : String(k.value);
    } else if (k.size != null) val = `size: ${k.size}`;
    return `<div class="redis-key"><code>${k.key}</code> <span class="badge stale">${k.type}</span><pre>${val || "—"}</pre></div>`;
  }).join("");
}

function renderPreflight(data) {
  if (!data) return '<p class="empty">Kliknij Preflight</p>';
  let html = (data.checks || []).map((c) =>
    `<div class="check-row"><span class="${c.ok ? "ok" : "bad"}">${c.ok ? "OK" : "FAIL"}</span><span>${c.name}</span><span style="color:var(--text-muted);font-size:0.8rem">${c.detail || ""}</span></div>`
  ).join("");
  if (data.staleRedisShards?.length) {
    html += `<p class="hint" style="margin-top:0.75rem">Martwe shardy w Redis: ${data.staleRedisShards.join(", ")}</p>`;
  }
  if (data.ports?.length) {
    html += '<div class="port-grid">' + data.ports.map((p) =>
      `<div class="port-chip ${p.listening ? "listening" : "down"}"><strong>${p.id}</strong><br>:${p.port} ${p.listening ? "LISTEN" : "down"}</div>`
    ).join("") + "</div>";
  }
  return html;
}

function renderMongoExtended(data) {
  if (!data) return '<p class="empty">—</p>';
  let html = '<div class="detail-grid">';
  for (const [k, v] of Object.entries(data.collections || {})) {
    html += `<dt>${k}</dt><dd>${v}</dd>`;
  }
  html += "</div>";
  if (data.modeCollections?.length) {
    html += `<p class="hint" style="margin-top:0.5rem">Kolekcje per-tryb: ${data.modeCollections.join(", ")}</p>`;
  }
  return html;
}

function renderShardDetail(d) {
  const loc = d.local || {};
  const redis = d.redis || {};
  const env = d.env || {};
  const envLines = Object.entries(env).map(([k, v]) => `<dt>${k}</dt><dd>${v}</dd>`).join("");
  const redisLines = Object.entries(redis.hash || {}).map(([k, v]) => `<dt>${k}</dt><dd>${v}</dd>`).join("");

  return `
    <div class="detail-section">
      <h4>Status</h4>
      <div class="detail-grid">
        <dt>ID</dt><dd>${d.id}</dd>
        <dt>Tryb</dt><dd>${d.mode || "—"}</dd>
        <dt>Status</dt><dd>${d.status || "—"} ${statusBadge(d.status || "offline")}</dd>
        <dt>Stan Redis</dt><dd>${d.state || "—"} ${stateBadge(d.state)}</dd>
        <dt>Gracze</dt><dd>${d.players ?? 0} / soft ${d.softCap || "?"} / hard ${d.hardCap || "?"}</dd>
        <dt>TPS / MSPT</dt><dd>${d.tps != null ? d.tps.toFixed(2) : "—"} / ${d.mspt != null ? d.mspt.toFixed(1) : "—"}</dd>
        <dt>Heartbeat</dt><dd>${fmtAge(d.heartbeatAgeMs)} ${d.fresh ? "(swiezy)" : "(stary)"}</dd>
        <dt>Sesje na shardzie</dt><dd>${d.sessionCount ?? 0}</dd>
      </div>
      <div class="detail-actions">
        ${loc.listening
          ? `<button class="btn ghost sm btn-stop" data-id="${d.id}">Stop</button>`
          : `<button class="btn success sm btn-start" data-id="${d.id}">Start</button>`}
        <button class="btn ghost sm btn-log" data-id="${d.id}">Logi</button>
        ${redis.registered ? `<button class="btn danger sm btn-redis-del" data-id="${d.id}">Usun z Redis</button>` : ""}
        ${loc.exists ? `<button class="btn danger sm btn-delete" data-id="${d.id}">Usun katalog</button>` : ""}
      </div>
    </div>
    <div class="detail-section">
      <h4>Siec / port</h4>
      <div class="detail-grid">
        <dt>Host</dt><dd>${loc.host || "—"}</dd>
        <dt>Port</dt><dd>${loc.port || "—"}</dd>
        <dt>Addr (Redis)</dt><dd>${loc.addr || d.addr || "—"}</dd>
        <dt>Nasluchuje</dt><dd>${loc.listening ? "TAK" : "NIE"}</dd>
        <dt>MOTD</dt><dd>${loc.motd || "—"}</dd>
        <dt>View / Sim</dt><dd>${loc.viewDistance || "?"} / ${loc.simulationDistance || "?"}</dd>
      </div>
    </div>
    <div class="detail-section">
      <h4>Pliki</h4>
      <div class="detail-grid">
        <dt>Katalog</dt><dd>${loc.path || d.paths?.serverDir || "—"}</dd>
        <dt>Swiat</dt><dd>${d.paths?.worldDir || "—"} (${loc.worldMb || 0} MB)</dd>
        <dt>Dysk calk.</dt><dd>${loc.diskMb || 0} MB</dd>
        <dt>paper.jar</dt><dd>${loc.hasPaperJar ? "tak" : "nie"}</dd>
        <dt>core-paper</dt><dd>${loc.coreJar || "brak"}</dd>
        <dt>Log Paper</dt><dd>${d.logs?.paper || "—"}</dd>
        <dt>Log panelu</dt><dd>${d.logs?.panel || "—"}</dd>
      </div>
    </div>
    ${envLines ? `<div class="detail-section"><h4>ENV (start.sh/bat)</h4><dl class="detail-grid">${envLines}</dl></div>` : ""}
    ${redisLines ? `<div class="detail-section"><h4>Redis hash shard:${d.id}</h4><dl class="detail-grid">${redisLines}</dl>
      <p class="hint">Klucze: ${redis.keys?.shard || ""} · ${redis.keys?.mode || ""}</p></div>` : ""}
    ${(d.logs?.paperTail || []).length ? `<div class="detail-section"><h4>Ostatnie logi Paper</h4><pre class="log-view sm">${d.logs.paperTail.join("\n")}</pre></div>` : ""}
    ${d.sessions?.length ? `<div class="detail-section"><h4>Gracze (sesje)</h4><pre class="log-view sm">${d.sessions.map((s) => s.uuid + " @ " + fmtTime(s.ts)).join("\n")}</pre></div>` : ""}
  `;
}

async function openShardDetail(id) {
  $("#detailTitle").textContent = id;
  $("#shardDetailBody").innerHTML = "Ladowanie...";
  $("#modalShardDetail").showModal();
  try {
    const d = await api(`/api/shards/${id}`);
    $("#shardDetailBody").innerHTML = renderShardDetail(d);
  } catch (err) {
    $("#shardDetailBody").innerHTML = `<p class="empty">${err.message}</p>`;
  }
}

async function refreshRedisTab() {
  try {
    const [overview, keys] = await Promise.all([
      api("/api/redis/overview"),
      api(`/api/redis/keys?pattern=${encodeURIComponent($("#redisKeyPattern").value)}&limit=40`),
    ]);
    $("#redisOverview").innerHTML = renderRedisOverview(overview);
    $("#redisKeys").innerHTML = renderRedisKeys(keys.keys);
  } catch (err) {
    toast(err.message, true);
  }
}

async function cleanupStaleRedis() {
  saveTokenFromSidebar();
  if (!confirm("Usunac wszystkie martwe wpisy shardow z Redis (heartbeat > 5s)?")) return;
  try {
    const res = await api("/api/redis/cleanup-stale", { method: "POST", body: "{}" });
    toast(`Usunieto ${res.count} wpisow: ${(res.removed || []).join(", ") || "—"}`);
    await refresh();
    await refreshRedisTab();
  } catch (err) {
    toast(err.message, true);
  }
}

async function removeShardFromRedis(id) {
  saveTokenFromSidebar();
  if (!confirm(`Usunac ${id} z Redis (shards:index + shard:${id})?`)) return;
  try {
    await api(`/api/shards/${id}/redis`, { method: "DELETE" });
    toast(`Usunieto ${id} z Redis`);
    $("#modalShardDetail").close();
    await refresh();
    await refreshRedisTab();
  } catch (err) {
    toast(err.message, true);
  }
}

async function deleteSession(uuid) {
  saveTokenFromSidebar();
  try {
    await api(`/api/sessions/${uuid}`, { method: "DELETE" });
    toast("Sesja usunieta");
    await refresh();
  } catch (err) {
    toast(err.message, true);
  }
}

async function runPreflight() {
  try {
    const data = await api("/api/dev/preflight");
    $("#devPreflight").innerHTML = renderPreflight(data);
  } catch (err) {
    toast(err.message, true);
  }
}

async function loadDevMongo() {
  try {
    const data = await api("/api/dev/mongo");
    $("#devMongo").innerHTML = renderMongoExtended(data);
  } catch (err) {
    $("#devMongo").innerHTML = `<p class="empty">${err.message}</p>`;
  }
}

async function runDevScript(path, label) {
  saveTokenFromSidebar();
  $("#devOutput").textContent = `Uruchamianie ${label}...`;
  try {
    const res = await api(path, { method: "POST", body: "{}" });
    $("#devOutput").textContent = res.output || res.error || JSON.stringify(res, null, 2);
    toast(res.ok ? `${label} OK` : `${label} FAIL`, !res.ok);
  } catch (err) {
    $("#devOutput").textContent = err.message;
    toast(err.message, true);
  }
}

function populateLogSelect(servers) {
  const sel = $("#logServer");
  const ids = ["velocity"];
  if (servers?.limbo) ids.push("limbo");
  (servers?.shards || []).forEach((s) => ids.push(s.id));
  sel.innerHTML = ids.map((id) => `<option value="${id}">${id}</option>`).join("");
}

function populateModeSelect(modes) {
  const sel = $("#newShardMode");
  sel.innerHTML = modes.map((m) => `<option value="${m.id}">${m.displayName} (${m.id})</option>`).join("");
}

function escAttr(s) {
  return String(s).replace(/&/g, "&amp;").replace(/"/g, "&quot;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
}

function renderSectorSummary(d) {
  return `<div class="cards">
    <div class="card"><span class="card-label">Aktywne sektory</span><span class="card-value">${d.sectors.length}</span></div>
    <div class="card"><span class="card-label">Instancje łącznie</span><span class="card-value">${d.totalInstances}</span></div>
    <div class="card"><span class="card-label">Gracze (survival)</span><span class="card-value">${d.totalPlayers}</span></div>
    <div class="card"><span class="card-label">Rozmiar sektora</span><span class="card-value">${d.size}×${d.size}</span></div>
  </div>`;
}

function renderSectorMap(d) {
  const { sectors, bounds, size } = d;
  if (!sectors.length) {
    return `<p class="muted">Brak aktywnych sektorów survivala. Utwórz np.: <code>new-shard survival 1 2G --sector 0 0</code>, uruchom i odśwież.</p>`;
  }
  const map = {};
  sectors.forEach((s) => { map[`${s.sx},${s.sz}`] = s; });
  const cols = bounds.maxX - bounds.minX + 1;
  let html = `<div class="sector-axis-hint muted">↑ północ (−Z) &nbsp;·&nbsp; wschód (+X) → &nbsp;·&nbsp; oś = współrzędne sektora</div>`;
  html += `<div class="sector-grid" style="grid-template-columns: 44px repeat(${cols}, minmax(96px, 1fr));">`;
  html += `<div class="sector-corner">Z \\ X</div>`;
  for (let x = bounds.minX; x <= bounds.maxX; x++) html += `<div class="sector-axis">${x}</div>`;
  for (let z = bounds.minZ; z <= bounds.maxZ; z++) {
    html += `<div class="sector-axis">${z}</div>`;
    for (let x = bounds.minX; x <= bounds.maxX; x++) {
      const s = map[`${x},${z}`];
      if (!s) { html += `<div class="sector-cell empty">·</div>`; continue; }
      const load = s.softCap ? Math.min(100, Math.round((100 * s.players) / s.softCap)) : 0;
      const lvl = load >= 90 ? "hot" : load >= 50 ? "warm" : (s.live ? "cool" : "dead");
      const tip = `Sektor (${s.sx}, ${s.sz}) — region X[${x * size}..${(x + 1) * size}] Z[${z * size}..${(z + 1) * size}]\n`
        + s.instances.map((i) => `${i.id}: ${i.players}/${i.softCap} ${i.fresh ? "" : "(stale)"}`).join("\n");
      html += `<div class="sector-cell ${lvl}" title="${escAttr(tip)}">
        <div class="sc-coord">${s.sx}, ${s.sz}</div>
        <div class="sc-players">${s.players}<span class="sc-cap">/${s.softCap}</span></div>
        <div class="sc-inst">×${s.instanceCount} ${s.instanceCount === 1 ? "instancja" : "instancji"}</div>
        <div class="sc-bar"><span style="width:${load}%"></span></div>
      </div>`;
    }
  }
  html += `</div>`;
  return html;
}

async function refresh() {
  try {
    const [overview, shardData, servers, sessions, cfg, sectors] = await Promise.all([
      api("/api/overview"),
      api("/api/shards"),
      api("/api/servers"),
      api("/api/sessions"),
      api("/api/config"),
      api("/api/sectors"),
    ]);
    state.overview = overview;
    state.shards = shardData.shards;
    state.modes = shardData.modes;
    state.servers = servers;
    state.sessions = sessions.sessions;
    state.authRequired = cfg.authRequired;

    renderConnPills(overview);
    renderCards(overview);
    $("#modesTable").innerHTML = renderModesTable(overview.modes);
    $("#liveShardsList").innerHTML = renderLiveShards(shardData.shards);
    $("#shardsTable").innerHTML = renderShardsTable(shardData.shards);
    $("#sectorSummary").innerHTML = renderSectorSummary(sectors);
    $("#sectorMap").innerHTML = renderSectorMap(sectors);
    $("#serversView").innerHTML = renderServersView(servers);
    $("#sessionsTable").innerHTML = renderSessionsTable(sessions.sessions);
    populateLogSelect(servers);
    populateModeSelect(shardData.modes);

    const showTok = cfg.authRequired;
    $("#tokenLabel").classList.toggle("hidden", !showTok);
    $("#deleteTokenLabel").classList.toggle("hidden", !showTok);
    $("#sidebarTokenLabel").classList.toggle("hidden", !showTok);
    if (state.panelToken && $("#sidebarToken")) {
      $("#sidebarToken").value = state.panelToken;
    }
    $("#startInWindow")?.closest(".toggle")?.classList.toggle("hidden", cfg.platform !== "win32");
  } catch (e) {
    toast("Błąd odświeżania: " + e.message, true);
  }
}

// Tabs
$$(".nav-item").forEach((btn) => {
  btn.addEventListener("click", () => {
    $$(".nav-item").forEach((b) => b.classList.remove("active"));
    btn.classList.add("active");
    const tab = btn.dataset.tab;
    $$(".tab-panel").forEach((p) => p.classList.remove("active"));
    $(`#tab-${tab}`).classList.add("active");
    $("#pageTitle").textContent = TITLES[tab] || tab;
    if (tab === "redis") refreshRedisTab();
    if (tab === "dev") { runPreflight(); loadDevMongo(); }
  });
});

// Refresh
$("#btnRefresh").addEventListener("click", refresh);
let refreshTimer = null;
function setupAutoRefresh() {
  clearInterval(refreshTimer);
  if ($("#autoRefresh").checked) {
    refreshTimer = setInterval(refresh, 5000);
  }
}
$("#autoRefresh").addEventListener("change", setupAutoRefresh);

// New shard
$("#btnNewShard").addEventListener("click", () => {
  $("#newShardOutput").classList.add("hidden");
  $("#modalNewShard").showModal();
});

$("#cancelNewShard").addEventListener("click", () => $("#modalNewShard").close());

$("#formNewShard").addEventListener("submit", async (e) => {
  e.preventDefault();
  const fd = new FormData(e.target);
  const token = fd.get("token");
  if (token) {
    state.panelToken = token;
    localStorage.setItem("elcartel_panel_token", token);
  }
  try {
    const res = await api("/api/shards/create", {
      method: "POST",
      body: JSON.stringify({
        mode: fd.get("mode"),
        count: parseInt(fd.get("count"), 10),
        xmx: fd.get("xmx"),
      }),
    });
    const out = $("#newShardOutput");
    out.textContent = res.output || "Utworzono.";
    out.classList.remove("hidden");
    toast("Shardy utworzone — mozesz je uruchomic z zakladki Serwery");
    await refresh();
  } catch (err) {
    toast(err.message, true);
  }
});

// Delete / detail / sessions / redis actions
document.addEventListener("click", (e) => {
  const t = e.target;
  if (t.classList.contains("btn-delete")) {
    state.deleteTarget = t.dataset.id;
    $("#deleteShardText").innerHTML = `Usuniesz katalog <code>servers/${state.deleteTarget}/</code> i wpis Redis.`;
    $("#modalDeleteShard").showModal();
  }
  if (t.classList.contains("btn-redis-del")) {
    removeShardFromRedis(t.dataset.id);
  }
  if (t.classList.contains("btn-detail") || t.closest(".shard-row")) {
    const id = t.dataset.id || t.closest(".shard-row")?.dataset.id;
    if (id && !t.classList.contains("btn-stop") && !t.classList.contains("btn-start")) openShardDetail(id);
  }
  if (t.classList.contains("btn-log")) {
    const id = t.dataset.id;
    $$(".nav-item").forEach((b) => b.classList.toggle("active", b.dataset.tab === "logs"));
    $$(".tab-panel").forEach((p) => p.classList.remove("active"));
    $("#tab-logs").classList.add("active");
    $("#pageTitle").textContent = "Logi";
    $("#logServer").value = id;
    $("#modalShardDetail").close();
    loadLog();
  }
  if (t.classList.contains("btn-start")) startOneServer(t.dataset.id);
  if (t.classList.contains("btn-stop")) stopOneServer(t.dataset.id);
  if (t.classList.contains("btn-del-session")) deleteSession(t.dataset.uuid);
});

async function startOneServer(id) {
  saveTokenFromSidebar();
  const window = $("#startInWindow")?.checked || false;
  try {
    const res = await api(`/api/servers/${id}/start`, {
      method: "POST",
      body: JSON.stringify({ window }),
    });
    toast(res.note || `Start ${id} (${res.mode || "ok"})`);
    setTimeout(refresh, 2000);
  } catch (err) {
    toast(err.message, true);
  }
}

async function stopOneServer(id, force = false) {
  saveTokenFromSidebar();
  if (!force && !confirm(`Zatrzymac ${id}? (graceful stop — zapis swiata)`)) return;
  try {
    const res = await api(`/api/servers/${id}/stop`, {
      method: "POST",
      body: JSON.stringify({ force }),
    });
    toast(res.note || `Stop ${id}${res.graceful === false ? " (awaryjnie)" : ""}`);
    setTimeout(refresh, 2000);
  } catch (err) {
    if (!force && confirm(`${err.message}\n\nWymusic awaryjne zatrzymanie?`)) {
      stopOneServer(id, true);
    } else {
      toast(err.message, true);
    }
  }
}

async function startAllNetwork() {
  saveTokenFromSidebar();
  const window = $("#startInWindow")?.checked || false;
  try {
    toast("Uruchamianie sieci (limbo -> shardy -> velocity)...");
    const res = await api("/api/network/start", {
      method: "POST",
      body: JSON.stringify({ window }),
    });
    const failed = (res.results || []).filter((r) => !r.ok && !r.skipped);
    if (failed.length) {
      toast("Czesc serwerow nie wystartowala — sprawdz logi", true);
    } else {
      toast("Sieć uruchomiona");
    }
    setTimeout(refresh, 3000);
  } catch (err) {
    toast(err.message, true);
  }
}

async function stopAllNetwork() {
  saveTokenFromSidebar();
  if (!confirm("Zatrzymac cala siec? (velocity -> shardy -> limbo)")) return;
  try {
    const res = await api("/api/network/stop", { method: "POST", body: JSON.stringify({ force: false }) });
    toast(res.ok ? "Sieć zatrzymywana" : "Czesc serwerow wymaga recznego stop");
    setTimeout(refresh, 3000);
  } catch (err) {
    toast(err.message, true);
  }
}

$("#btnStartNetwork").addEventListener("click", startAllNetwork);
$("#btnStartNetwork2").addEventListener("click", startAllNetwork);
$("#btnStopNetwork").addEventListener("click", stopAllNetwork);
$("#btnStopNetwork2").addEventListener("click", stopAllNetwork);

$("#sidebarToken")?.addEventListener("change", saveTokenFromSidebar);

$("#cancelDelete").addEventListener("click", () => $("#modalDeleteShard").close());

$("#formDeleteShard").addEventListener("submit", async (e) => {
  e.preventDefault();
  const token = $("#deleteShardToken").value;
  if (token) {
    state.panelToken = token;
    localStorage.setItem("elcartel_panel_token", token);
  }
  const backup = $("#deleteBackup").checked;
  try {
    await api(`/api/shards/${state.deleteTarget}?backup=${backup}`, { method: "DELETE" });
    toast(`Usunięto ${state.deleteTarget}`);
    $("#modalDeleteShard").close();
    await refresh();
  } catch (err) {
    toast(err.message, true);
  }
});

async function loadLog() {
  const id = $("#logServer").value;
  try {
    const data = await api(`/api/logs/${id}?lines=100`);
    $("#logView").textContent = data.lines.length ? data.lines.join("\n") : "(pusty log)";
  } catch (err) {
    $("#logView").textContent = "Błąd: " + err.message;
  }
}

$("#btnLoadLog").addEventListener("click", loadLog);

$("#closeShardDetail").addEventListener("click", () => $("#modalShardDetail").close());
$("#btnCleanupRedis").addEventListener("click", cleanupStaleRedis);
$("#btnCleanupRedis2").addEventListener("click", cleanupStaleRedis);
$("#btnRefreshRedis").addEventListener("click", refreshRedisTab);
$("#btnScanKeys").addEventListener("click", refreshRedisTab);
$("#btnRunPreflight").addEventListener("click", runPreflight);
$("#btnDeployCore").addEventListener("click", () => runDevScript("/api/dev/deploy-core", "deploy-core"));
$("#btnConfigureLp").addEventListener("click", () => runDevScript("/api/dev/configure-luckperms", "configure-luckperms"));

// Init
refresh().then(setupAutoRefresh);
