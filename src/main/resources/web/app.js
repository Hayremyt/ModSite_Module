/* Mods Manager — логика (vanilla JS, без внешних зависимостей) */
"use strict";

/* ================= Состояние ================= */
const S = {
    session: null,        // {login, role, csrf, source, sources, title, version, ...suggest-поля}
    profiles: [],
    currentProfile: null, // dir
    pack: null,           // ответ /api/mods
    auditOffset: 0,
    // каталог (вкладка «Поиск»): фильтры как в Modrinth/Prism Launcher
    search: {
        source: "modrinth", type: "mod", query: "", loader: "any", gameVersion: "",
        cats: new Set(), sort: "relevance", offset: 0, limit: 20,
        total: 0, inited: false, lastProfile: null, loading: false,
    },
    categories: {},       // "source:type" → [{id,name}]
    mine: null,           // ответ /api/mods/suggestions/mine (наблюдатель)
    suggestQueue: { q: "", kind: "", items: [], pendingTotal: 0, loaded: false },
    // фильтры/сортировка списка модов (вкладка «Моды»); сохраняются в localStorage
    modsFilter: loadModsFilter(),
    settings: null,       // ответ /api/settings (вкладка «Настройки»)
    suggestBansLocal: [], // рабочий список банов предложения (до «Сохранить»)
    backups: null,        // ответ /api/backups (вкладка «Бекапы»)
    backupsTimer: null,   // опрос активной задачи бекапа
    backupsJobSeen: false, // задача была видна — покажем тост по завершении
    backupsFoldersDirty: false, // админ меняет галочки — не затирать при перерисовке
    updateJob: null,      // фоновая задача обновления (прогресс)
    scan: null,           // данные «Скан папки» (renamed/deleted/added)
};

/** Фильтры списка модов из localStorage (с защитой от мусора).
 *  query — поиск по установленным (транзиентный, не сохраняется). */
function loadModsFilter() {
    try {
        const saved = JSON.parse(localStorage.getItem("mods_mfilter") || "{}");
        const sort = ["default", "name-asc", "name-desc"].includes(saved.sort) ? saved.sort : "default";
        const kind = ["mod", "resourcepack", "shader"].includes(saved.kind) ? saved.kind : "mod";
        return { optional: !!saved.optional, updates: !!saved.updates, sort, kind, query: "" };
    } catch (e) {
        return { optional: false, updates: false, sort: "default", kind: "mod", query: "" };
    }
}

function saveModsFilter() {
    localStorage.setItem("mods_mfilter", JSON.stringify(S.modsFilter));
}

/* ================= Утилиты ================= */
const $ = (id) => document.getElementById(id);
const esc = (s) => String(s == null ? "" : s)
    .replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;").replace(/'/g, "&#39;");

// Базовый путь страниц (для статики): там же, где index.html (работает и по http, и по https)
const BASE = location.pathname.replace(/(index\.html)?\/?$/, "/");

/** База API. Сайт работает и как <ip>/mods/, и в корне домена (mods.example.com):
 *  во втором случае страницы отдаются с "/", а путь "/api" занят WebSocket
 *  лаунчера — поэтому API всегда доступен на "/mods/api/" (точка монтирования
 *  одна на любом хосте). Второй кандидат — BASE+"api/" для reverse-proxy,
 *  который маппит весь домен на /mods/ (тогда "/mods/api/" не существует).
 *  Выбор — пробой /health при запуске (resolveApiBase). */
const API_CANDIDATES = [...new Set(["/mods/api/", BASE + "api/"])];
let API_BASE = API_CANDIDATES[0];

/** Определить рабочую базу API: первый кандидат, чей /health ответил
 *  корректным JSON. Ничего не ответил — оставляем первый (будет понятная
 *  ошибка «offline» при входе, как и раньше). */
async function resolveApiBase() {
    for (const base of API_CANDIDATES) {
        try {
            const r = await fetch(base + "health", { credentials: "same-origin", cache: "no-store" });
            const p = await r.json();
            if (p && p.success === true && p.data && p.data.module === "ModsSite") {
                API_BASE = base;
                return;
            }
        } catch (e) { /* пробуем следующий кандидат */ }
    }
}

/**
 * Иконки/превью Modrinth/CurseForge грузим через серверный прокси
 * (GET api/icon?url=…): у части клиентов эти CDN заблокированы, а сервер
 * доступен. Локальные/относительные пути остаются как есть.
 */
function iconSrc(url) {
    if (!url || !/^https?:\/\//i.test(url)) return url;
    return API_BASE + "icon?url=" + encodeURIComponent(url);
}

/** SVG-иконка из спрайта (стиль Lucide, без эмодзи). */
function icon(name, cls) {
    return '<svg class="icon' + (cls ? " " + cls : "") + '" aria-hidden="true"><use href="#i-' + name + '"/></svg>';
}

/** Кнопка в состоянии загрузки: спиннер + текст, затем иконка + текст. */
function setBtnLoading(btn, i18nKey, iconName, loading) {
    if (!btn) return;
    if (loading) {
        btn.innerHTML = '<span class="spin" aria-hidden="true"></span><span>' + t(i18nKey) + "</span>";
        btn.disabled = true;
    } else {
        btn.innerHTML = icon(iconName) + "<span>" + t(i18nKey) + "</span>";
        btn.disabled = false;
    }
}

function toast(message, kind) {
    const el = document.createElement("div");
    el.className = "toast " + (kind || "info");
    const iconName = kind === "success" ? "check" : kind === "error" ? "alert" : kind === "warn" ? "warn" : "info";
    el.innerHTML = icon(iconName);
    const span = document.createElement("span");
    span.textContent = message;
    el.appendChild(span);
    $("toast-container").appendChild(el);
    setTimeout(() => el.remove(), 5200);
}

/** Показать предупреждения источников (сеть/403/троттлинг) из ответа
 *  /api/mods/check или /api/mods/import — раньше они были видны только в логе
 *  сервера, и казалось, что мод «принципиально» не сопоставляется. */
function showSourceWarnings(warnings) {
    if (!Array.isArray(warnings)) return;
    warnings.slice(0, 3).forEach(w => {
        const text = typeof w === "string" && w.trim() ? w.trim() : String(w);
        toast(t("mods.sourceWarning", { msg: text }), "warn");
    });
    if (warnings.length > 3) {
        toast(t("mods.sourceWarningMore", { n: warnings.length - 3 }), "warn");
    }
}

async function api(path, options) {
    const opts = {
        method: (options && options.method) || "GET",
        headers: {},
        credentials: "same-origin",
    };
    if (opts.method !== "GET") {
        opts.headers["Content-Type"] = "application/json";
        if (S.session && S.session.csrf) {
            opts.headers["X-Mods-CSRF"] = S.session.csrf;
        }
    }
    if (options && options.body) {
        opts.body = JSON.stringify(options.body);
    }
    let response;
    try {
        response = await fetch(API_BASE + path.replace(/^\//, ""), opts);
    } catch (e) {
        return { ok: false, error: { code: "offline", message: t("login.error.offline") } };
    }
    let payload = null;
    try {
        payload = await response.json();
    } catch (e) {
        payload = { success: false, error: { code: "bad_response", message: "HTTP " + response.status } };
    }
    // 401 на самом запросе входа — это ожидаемый ответ (need_totp / неверный пароль):
    // обрабатывает doLogin, поля нельзя очищать. Сброс на экран входа — только для
    // остальных вызовов (истёкшая сессия).
    if (response.status === 401 && !(options && options.isLogin)) {
        showLogin();
    }
    return {
        ok: payload && payload.success === true,
        status: response.status,
        data: payload ? payload.data : null,
        error: payload && payload.error ? payload.error : null,
    };
}

function fmtTime(ms) {
    if (!ms) return "—";
    return new Date(ms).toLocaleString(LANG === "ru" ? "ru-RU" : "en-GB");
}

/** 1234567 → «1.2M» — для счётчиков загрузок. */
function fmtNum(n) {
    n = Number(n) || 0;
    if (n >= 1000000) return (n / 1000000).toFixed(1).replace(/\.0$/, "") + "M";
    if (n >= 1000) return (n / 1000).toFixed(1).replace(/\.0$/, "") + "k";
    return String(n);
}

/** Дата обновления в поиске: dd.mm.yyyy (без времени). */
function fmtDate(ms) {
    if (!ms) return "";
    return new Date(ms).toLocaleDateString(LANG === "ru" ? "ru-RU" : "en-GB");
}

/** Установить обработчик, если элемент существует (у наблюдателя часть
 *  элементов УДАЛЕНА из DOM целиком — не просто скрыта). */
function on(id, event, fn) {
    const el = $(id);
    if (el) el.addEventListener(event, fn);
}

/* ================= Вход ================= */
function showLogin() {
    S.session = null;
    $("view-main").classList.add("hidden");
    $("view-login").classList.remove("hidden");
    $("login-totp-label").classList.add("hidden");
    $("login-error").classList.add("hidden");
    $("login-login").value = "";
    $("login-password").value = "";
    $("login-totp").value = "";
    setTimeout(() => $("login-login").focus(), 50);
}

/** Из ответа сервера «... Banned until <ISO>» достаём время снятия — показываем его пользователю. */
function untilSuffix(serverMessage) {
    const m = /until\s+(\S+)/.exec(serverMessage || "");
    if (!m) return "";
    const d = new Date(m[1]);
    if (isNaN(d.getTime())) return "";
    return " (" + t("login.error.until") + " " + d.toLocaleTimeString() + ")";
}

async function doLogin(event) {
    event.preventDefault();
    const btn = $("login-submit");
    setBtnLoading(btn, "login.submit", "log-in", true);
    const login = $("login-login").value.trim();
    const password = $("login-password").value;
    const totp = $("login-totp").value.trim();
    const result = await api("login", { method: "POST", isLogin: true, body: { login, password, totp: totp || null } });
    setBtnLoading(btn, "login.submit", "log-in", false);
    if (result.ok) {
        await boot();
        return;
    }
    const err = result.error || {};
    const map = {
        need_totp: () => null, // показываем только поле 2FA
        wrong_totp: () => t("login.error.wrongTotp"),
        invalid_credentials: () => t("login.error.invalid"),
        ip_banned: () => t("login.error.banned") + untilSuffix(err.message),
        account_locked: () => t("login.error.locked") + untilSuffix(err.message),
        account_disabled: () => t("login.error.disabled"),
        not_admin: () => t("login.error.notAdmin"),
        rate_limited: () => t("login.error.rate"),
        bad_request: () => t("login.error.invalid"),
    };
    const handler = map[err.code];
    const message = handler ? handler() : (err.message || err.code || t("common.error"));
    if (err.code === "need_totp" || err.code === "wrong_totp") {
        $("login-totp-label").classList.remove("hidden");
    }
    const box = $("login-error");
    if (message) {
        box.innerHTML = icon("warn");
        const span = document.createElement("span");
        span.textContent = message;
        box.appendChild(span);
        box.classList.remove("hidden");
    } else {
        box.classList.add("hidden");
    }
}

async function doLogout() {
    await api("logout", { method: "POST", body: {} });
    showLogin();
}

/* ================= Загрузка ================= */
async function boot() {
    // API-база: /mods/api/ (гарантированно) или BASE+api/ (reverse-proxy)
    await resolveApiBase();
    const session = await api("session");
    if (!session.ok) {
        showLogin();
        return;
    }
    S.session = session.data;
    S.session.sources = session.data.sources || {};
    // язык по умолчанию с сервера, если пользователь не выбирал сам
    if (!localStorage.getItem("mods_lang") && session.data.lang && I18N[session.data.lang] && session.data.lang !== LANG) {
        setLang(session.data.lang);
    }
    $("view-login").classList.add("hidden");
    $("view-main").classList.remove("hidden");
    $("topbar-title").textContent = session.data.title || "Mods Manager";
    document.title = session.data.title || "Mods Manager";
    $("topbar-version").textContent = "v" + (session.data.version || "");
    const isAdmin = session.data.role === "admin" || session.data.role === "superadmin";
    const roleLabel = session.data.role || "viewer";
    $("user-badge").innerHTML = icon("user") + "<span>" + esc(session.data.login) + "</span>" +
        '<span class="role-chip' + (isAdmin ? (session.data.role === "superadmin" ? " super" : "") : " viewer") + '">' + esc(roleLabel) + "</span>";
    $("tab-settings").classList.toggle("hidden", !isAdmin);
    $("tab-backups").classList.toggle("hidden", !isAdmin);
    $("tab-suggest").classList.toggle("hidden", !isAdmin);
    // Админские кнопки: наблюдателю скрываем (display:none), админу — показываем.
    // Кнопки НЕ удаляются из DOM: смена сессии наблюдатель→админ в той же
    // вкладке восстанавливает их без перезагрузки страницы.
    // «Проверить обновления», «Обновить всё», «Скан папки», «Обновить хэши»,
    // «Перечитать профиль»; чип «авто-хэш» тоже не показываем
    ["btn-check", "btn-update-all", "btn-scan", "btn-rehash", "btn-reload-profile"]
        .forEach(id => { const el = $(id); if (el) el.classList.toggle("hidden", !isAdmin); });
    // Вкладка «Роли админов» в панели Безопасность — только суперадмину
    // (логины суперадминов задаются вручную в config.superAdmins)
    const rolesTab = $("subtab-roles");
    if (rolesTab) rolesTab.classList.toggle("hidden", session.data.role !== "superadmin");
    // Тип поиска по умолчанию: персональный выбор браузера → настройка сервера → моды
    S.search.type = ["mod", "resourcepack", "shader"].includes(localStorage.getItem("mods_ptype"))
        ? localStorage.getItem("mods_ptype")
        : (["mod", "resourcepack", "shader"].includes(session.data.defaultProjectType)
            ? session.data.defaultProjectType : "mod");
    // Свои предложения: блок в сайдбаре поиска — только наблюдателю
    const mineGroup = $("mine-group");
    if (mineGroup) mineGroup.classList.toggle("hidden", isAdmin);
    // новая сессия — сбрасываем каталог: он мог остаться от другого пользователя
    // (у вьюера не должно быть кнопок админа)
    S.search.inited = false;
    S.search.lastProfile = null;
    S.settings = null;
    S.mine = null;
    S.suggestQueue.loaded = false;
    // значки источников
    const badges = $("source-badges");
    badges.innerHTML = "";
    ["modrinth", "curseforge"].forEach(src => {
        const span = document.createElement("span");
        span.className = "source-badge" + (S.session.sources[src] ? " on" : "");
        span.textContent = src === "modrinth" ? "Modrinth" : "CurseForge";
        if (!S.session.sources[src]) span.style.textDecoration = "line-through";
        badges.appendChild(span);
    });
    await loadProfiles();
    await selectFirstProfile();
    if (isAdmin) {
        refreshSuggestBadge(); // счётчик ожидает рассмотрения — даже если вкладка не открыта
    }
}

/** Обновить счётчик предложений на вкладке (админ). */
async function refreshSuggestBadge() {
    if (!isAdmin()) return;
    const result = await api("mods/suggestions");
    if (!result.ok) return;
    S.suggestQueue.pendingTotal = result.data.pendingTotal || 0;
    S.suggestQueue.items = result.data.items || [];
    S.suggestQueue.loaded = true;
    const badge = $("cnt-suggest");
    if (badge) badge.textContent = S.suggestQueue.pendingTotal ? String(S.suggestQueue.pendingTotal) : "";
}

/* ================= Профили ================= */
async function loadProfiles() {
    const result = await api("profiles");
    if (!result.ok) return;
    S.profiles = result.data || [];
    renderProfiles();
}

async function selectFirstProfile() {
    if (S.profiles.length > 0) {
        await selectProfile(S.profiles[0].dir);
    } else {
        S.pack = null;
        renderPack();
    }
}

async function selectProfile(dir) {
    S.currentProfile = dir;
    S.modsFilter.query = ""; // поиск по установленным — заново на каждый профиль
    S.backupsFoldersDirty = false; // выбор папок бекапа — заново на каждый профиль
    const searchInput = $("mod-search");
    if (searchInput) searchInput.value = "";
    await loadPack();
    renderProfiles();
}

function renderProfiles() {
    const bar = $("profiles-bar");
    bar.innerHTML = "";
    S.profiles.forEach(profile => {
        const el = document.createElement("button");
        el.className = "profile-chip" + (profile.dir === S.currentProfile ? " active" : "");
        el.type = "button";
        const updates = profile.updatesCount > 0
            ? ` <span class="up">${icon("refresh")}${profile.updatesCount}</span>` : "";
        // состав: моды · пакеты · шейдеры (только непустые)
        const parts = [];
        const modsCount = profile.modsKindCount != null ? profile.modsKindCount : profile.modsCount;
        if (modsCount) parts.push(`<b>${modsCount}</b> ${t("stats.mods")}`);
        if (profile.resourcepacksCount) parts.push(`<b>${profile.resourcepacksCount}</b> ${t("stats.resourcepacks")}`);
        if (profile.shadersCount) parts.push(`<b>${profile.shadersCount}</b> ${t("stats.shaders")}`);
        if (!parts.length) parts.push(`<b>0</b> ${t("stats.mods")}`);
        el.innerHTML = `<span class="title">${esc(profile.title)}</span>
            <span class="meta">${esc(profile.gameVersion || "?")} · ${esc(profile.loader || "any")} ·
            ${parts.join(" · ")}${updates}</span>`;
        el.addEventListener("click", () => selectProfile(profile.dir));
        bar.appendChild(el);
    });
}

/* ================= Модпак ================= */
let orphanShowAll = false; // блок «лишних файлов» показан целиком (кнопка «Показать все»)

async function loadPack(retry) {
    if (!S.currentProfile) return;
    const result = await api("mods?profile=" + encodeURIComponent(S.currentProfile));
    if (!result.ok) {
        // На всякий случай оставляем тихий ретрай (сервер теперь во время
        // обновления отдаёт список без 409) — в запасных сценариях сети
        if (result.error && result.error.code === "update_in_progress" && (retry || 0) < 300) {
            setTimeout(() => loadPack((retry || 0) + 1), 2000);
            return;
        }
        toast(result.error ? result.error.message : t("common.error"), "error");
        return;
    }
    S.pack = result.data;
    orphanShowAll = false;
    renderPack();
    // Обновление уже идёт (например, страница перезагружена посреди задачи) —
    // сервер прислал снимок задачи: сразу открываем окно статуса
    if (result.data.job && !result.data.job.finished) {
        restoreUpdateJob(result.data.job);
    }
}

function modById(id) {
    return (S.pack && S.pack.mods || []).find(m => m.id === id);
}

/** Пары конфликтующих модов в составе. Всё, что есть в модпаке, и есть
 *  состав (включение/выключение убрано: скачан — значит нужен), поэтому
 *  конфликтуют просто любые два мода модпака, ссылающиеся друг на друга.
 *  Пара приходит в обе стороны (конфликты симметричны) — при выводе
 *  дедуплицируется (conflictIds). */
function packConflicts() {
    const mods = (S.pack && S.pack.mods) || [];
    const byId = {};
    mods.forEach(m => byId[m.id] = m);
    const conflicts = [];
    mods.forEach(m => {
        (m.conflicts || []).forEach(cid => {
            if (byId[cid]) conflicts.push([m.id, cid]);
        });
    });
    return conflicts;
}

/** Совпадает ли мод с поисковым запросом (имя / id / имя файла). */
function modMatchesQuery(mod, query) {
    if (!query) return true;
    const haystack = ((mod.name || "") + "\n" + (mod.id || "") + "\n"
        + ((mod.installed && mod.installed.fileName) || "")).toLowerCase();
    return query.toLowerCase().split(/\s+/).filter(Boolean).every(term => haystack.includes(term));
}

/** Отфильтрованный/отсортированный список модов для отображения. */
function visibleMods() {
    let list = S.pack && S.pack.mods ? S.pack.mods.slice() : [];
    const kind = S.modsFilter.kind || "mod";
    list = list.filter(m => (m.kind || "mod") === kind);
    if (S.modsFilter.optional) list = list.filter(m => m.optional);
    if (S.modsFilter.updates) list = list.filter(m => m.hasUpdate);
    const query = (S.modsFilter.query || "").trim();
    if (query) list = list.filter(m => modMatchesQuery(m, query));
    const locale = LANG === "ru" ? "ru" : "en";
    if (S.modsFilter.sort === "name-asc") {
        list.sort((a, b) => String(a.name || a.id).localeCompare(String(b.name || b.id), locale, { numeric: true }));
    } else if (S.modsFilter.sort === "name-desc") {
        list.sort((a, b) => -String(a.name || a.id).localeCompare(String(b.name || b.id), locale, { numeric: true }));
    }
    return list;
}

/** Чипы фильтров и счётчики — состояние сохраняется в localStorage
 *  (кроме поискового запроса — он живёт до смены профиля). */
function updateFilterBar() {
    const mods = S.pack && S.pack.mods || [];
    const kind = S.modsFilter.kind || "mod";
    const kindCounts = { mod: 0, resourcepack: 0, shader: 0 };
    mods.forEach(m => { kindCounts[m.kind || "mod"] = (kindCounts[m.kind || "mod"] || 0) + 1; });
    ["mod", "resourcepack", "shader"].forEach(k => {
        const chip = $("filter-kind-" + k);
        if (!chip) return;
        chip.classList.toggle("active", kind === k);
        chip.disabled = kindCounts[k] === 0 && kind !== k;
        const cnt = $("cnt-kind-" + k);
        if (cnt) cnt.textContent = kindCounts[k] ? String(kindCounts[k]) : "";
    });
    // счётчики «Опциональные»/«С обновлениями» — в рамках выбранного типа
    const scoped = mods.filter(m => (m.kind || "mod") === kind);
    const optionalCount = scoped.filter(m => m.optional).length;
    const updatesCount = scoped.filter(m => m.hasUpdate).length;
    const optionalChip = $("filter-optional");
    const updatesChip = $("filter-updates");
    optionalChip.classList.toggle("active", S.modsFilter.optional);
    optionalChip.disabled = optionalCount === 0 && !S.modsFilter.optional;
    updatesChip.classList.toggle("active", S.modsFilter.updates);
    updatesChip.disabled = updatesCount === 0 && !S.modsFilter.updates;
    $("cnt-optional").textContent = optionalCount ? String(optionalCount) : "";
    $("cnt-updates").textContent = updatesCount ? String(updatesCount) : "";
    $("mod-sort").value = S.modsFilter.sort;
    const query = (S.modsFilter.query || "").trim();
    const input = $("mod-search");
    const clear = $("mod-search-clear");
    if (input && document.activeElement !== input && input.value !== S.modsFilter.query) {
        input.value = S.modsFilter.query;
    }
    if (clear) clear.classList.toggle("hidden", !query);
}

function renderPack() {
    const grid = $("mods-grid");
    const empty = $("mods-empty");
    updateFilterBar();
    const isAdmin = S.session && (S.session.role === "admin" || S.session.role === "superadmin");
    if (!S.pack) {
        grid.innerHTML = "";
        empty.innerHTML = icon("inbox", "empty-icon") + "<p>" + esc(t("mods.selectProfile")) + "</p>";
        empty.classList.remove("hidden");
        $("pack-meta").innerHTML = "";
        $("pack-stats").innerHTML = "";
        return;
    }
    // мета-чипы; загрузчик — селектор у админа: дефолт профиля применяется
    // автоматически в поиске и проверке обновлений (сменить в поиске всё ещё можно).
    // Чип «авто-хэш» — только админу (наблюдателю он не нужен)
    const autoRehashChip = isAdmin
        ? `<span class="meta-chip">${icon("hash")}${esc(t("meta.autoRehash"))}: <b>${S.pack.autoRehash ? t("meta.autoRehashOn") : t("meta.autoRehashOff")}</b></span>`
        : "";
    const override = S.pack.loaderOverride || "";
    const effective = S.pack.loader || "any";
    const autoLabel = override ? t("loader.auto") : (t("loader.auto") + (S.pack.loader ? " — " + S.pack.loader : ""));
    const loaderChip = isAdmin
        ? `<label class="meta-chip loader-chip" title="${esc(t("meta.loaderHint"))}">${icon("zap")}${esc(t("meta.loader"))}:
             <select id="pack-loader" class="input select slim" aria-label="${esc(t("meta.loader"))}">
                 <option value="">${esc(autoLabel)}</option>
                 <option value="any">${esc(t("search.any"))}</option>
                 <option value="fabric">Fabric</option>
                 <option value="forge">Forge</option>
                 <option value="neoforge">NeoForge</option>
                 <option value="quilt">Quilt</option>
             </select></label>`
        : `<span class="meta-chip">${icon("zap")}${esc(t("meta.loader"))}: <b>${esc(effective)}</b></span>`;
    $("pack-meta").innerHTML =
        `<span class="meta-chip">${icon("package")}<b>${esc(S.pack.title)}</b></span>` +
        `<span class="meta-chip">${icon("globe")}${esc(t("meta.game"))}: <b>${esc(S.pack.gameVersion || "?")}</b></span>` +
        loaderChip +
        autoRehashChip;
    if (isAdmin) {
        const loaderSelect = $("pack-loader");
        if (loaderSelect) {
            loaderSelect.value = override;
            loaderSelect.addEventListener("change", () => saveProfileLoader(loaderSelect.value));
        }
    }
    const stats = $("pack-stats");
    const kindCounts = { mod: 0, resourcepack: 0, shader: 0 };
    (S.pack.mods || []).forEach(m => { kindCounts[m.kind || "mod"] = (kindCounts[m.kind || "mod"] || 0) + 1; });
    const updatable = (S.pack.mods || []).filter(m => m.hasUpdate).length;
    const kindLabel = {
        mod: t("stats.mods"), resourcepack: t("stats.resourcepacks"), shader: t("stats.shaders"),
    };
    // Состав показываем чипами по типам (моды · паки · шейдеры): всё, что
    // есть в модпаке, и так входит в состав — отдельные счётчики «всего»
    // и «включено» не несут информации и убраны.
    stats.innerHTML =
        (kindCounts.mod ? `<span class="stat-chip">${icon("package")}<b>${kindCounts.mod}</b> ${esc(kindLabel.mod)}</span>` : "") +
        (kindCounts.resourcepack ? `<span class="stat-chip">${icon("image")}<b>${kindCounts.resourcepack}</b> ${esc(kindLabel.resourcepack)}</span>` : "") +
        (kindCounts.shader ? `<span class="stat-chip">${icon("sparkles")}<b>${kindCounts.shader}</b> ${esc(kindLabel.shader)}</span>` : "") +
        (updatable ? `<span class="stat-chip warn">${icon("refresh")}<b>${updatable}</b> ${esc(t("stats.updates"))}</span>` : "");

    const byId = {};
    (S.pack.mods || []).forEach(m => byId[m.id] = m);
    const conflictIds = new Set();
    packConflicts().forEach(([a, b]) => { conflictIds.add(a); conflictIds.add(b); });

    grid.innerHTML = "";
    const shown = visibleMods();
    const kindSelected = (S.modsFilter.kind || "mod") !== "mod";
    const hasFilter = (S.modsFilter.optional || S.modsFilter.updates || kindSelected
        || (S.modsFilter.query || "").trim()) && S.pack.mods.length > 0;
    if (!shown.length) {
        const searched = (S.modsFilter.query || "").trim();
        empty.innerHTML = icon(hasFilter ? (searched ? "search" : "filter") : "inbox", "empty-icon") + "<p>"
            + esc(hasFilter ? (searched ? t("mods.searchNoResults") : t("mods.filteredEmpty")) : t("mods.empty")) + "</p>";
        empty.classList.remove("hidden");
    } else {
        empty.classList.add("hidden");
        const counter = document.createElement("span");
        counter.className = "muted small shown-counter";
        counter.textContent = t("mods.showing", { a: shown.length, b: S.pack.mods.length });
        grid.appendChild(counter);
    }
    for (const mod of shown) {
        grid.appendChild(renderModCard(mod, byId, conflictIds, isAdmin));
    }
    // лишние файлы: импорт в модпак (по хэшам Modrinth/CurseForge) или удаление выбранных
    if (isAdmin && S.pack.orphans && S.pack.orphans.length) {
        const n = S.pack.orphans.length;
        const div = document.createElement("div");
        div.className = "mod-card orphan-card";
        div.style.gridColumn = "1 / -1";
        div.innerHTML =
            `<div class="orphan-head">${icon("trash")}<b>${esc(t("mods.orphanTitle", { n }))}</b></div>` +
            `<div class="muted small orphan-hint">${esc(t("mods.orphanHint"))}</div>`;
        const list = document.createElement("div");
        list.className = "orphan-list" + (n > 10 ? " scrollable" : "");
        const shown = orphanShowAll ? S.pack.orphans : S.pack.orphans.slice(0, 10);
        for (const file of shown) {
            const kind = file.kind || "mod";
            const label = document.createElement("label");
            label.className = "orphan-item";
            label.innerHTML = `<input type="checkbox" checked data-kind="${esc(kind)}" value="${esc(file.name)}">` +
                `<span>${kind !== "mod" ? `<span class="badge kind-${esc(kind)}">${esc(t("kind." + kind))}</span> ` : ""}${esc(file.name)}</span>`;
            list.appendChild(label);
        }
        div.appendChild(list);
        const tools = document.createElement("div");
        tools.className = "orphan-actions";
        if (!orphanShowAll && n > 10) {
            const more = document.createElement("button");
            more.type = "button";
            more.className = "btn ghost small";
            more.textContent = `${t("mods.showAll")} (${n})`;
            more.addEventListener("click", () => { orphanShowAll = true; renderPack(); });
            tools.appendChild(more);
        }
        const importBtn = document.createElement("button");
        importBtn.type = "button";
        importBtn.className = "btn primary small orphan-import";
        importBtn.textContent = t("mods.import");
        importBtn.addEventListener("click", importOrphans);
        const cleanBtn = document.createElement("button");
        cleanBtn.type = "button";
        cleanBtn.className = "btn danger small";
        cleanBtn.textContent = t("mods.clean");
        cleanBtn.addEventListener("click", cleanOrphans);
        tools.appendChild(importBtn);
        tools.appendChild(cleanBtn);
        div.appendChild(tools);
        grid.appendChild(div);
    }
    // у наблюдателя эти кнопки скрыты (скрытие при входе) — для страховки
    // держим их ещё и задизейбленными: сервер на эти POST требует админа
    ["btn-check", "btn-scan", "btn-update-all", "btn-rehash", "btn-reload-profile"].forEach(id => {
        const btn = $(id);
        if (btn) btn.disabled = !isAdmin;
    });
}

function renderModCard(mod, byId, conflictIds, isAdmin) {
    const card = document.createElement("div");
    card.className = "mod-card" + (conflictIds.has(mod.id) ? " conflict" : "");
    // иконка (fallback при ошибке загрузки вешается ниже через addEventListener —
    // inline onerror с экранированием кавычек ломался, если имя начинается с "'")
    const iconHtml = mod.iconUrl
        ? `<img class="mod-icon" src="${esc(iconSrc(mod.iconUrl))}" alt="" loading="lazy">`
        : `<div class="mod-icon-fallback">${esc((mod.name || "?")[0].toUpperCase())}</div>`;
    // бейджи
    const badges = [];
    const kind = mod.kind || "mod";
    if (kind !== "mod") {
        badges.push(`<span class="badge kind-${esc(kind)}">${icon(kind === "resourcepack" ? "image" : "sparkles")}${esc(t("kind." + kind))}</span>`);
    }
    const srcName = mod.source === "modrinth" ? "Modrinth"
        : mod.source === "curseforge" ? "CurseForge" : esc(t("source.local"));
    badges.push(`<span class="badge ${mod.source === "modrinth" ? "mr" : mod.source === "curseforge" ? "cf" : "loc"}">${srcName}</span>`);
    badges.push(mod.optional
        ? `<span class="badge optional">${esc(t("mods.optional"))}</span>`
        : `<span class="badge required">${esc(t("mods.required"))}</span>`);
    if (mod.optionalGroup) badges.push(`<span class="badge group" title="${esc(t("mods.groupHint"))}">${icon("layers")}${esc(mod.optionalGroup)}</span>`);
    if (mod.hasUpdate) badges.push(`<span class="badge update">${icon("refresh")}${esc(t("mods.updateBadge"))}</span>`);
    if (mod.postponed) badges.push(`<span class="badge postponed">${icon("clock")}${esc(t("mods.postponedBadge"))}</span>`);
    if ((mod.dependencies || []).length) badges.push(`<span class="badge dep">${icon("link")}${mod.dependencies.length}</span>`);
    if (conflictIds.has(mod.id)) badges.push(`<span class="badge conflict">${icon("warn")}${esc(t("mods.conflictBadge"))}</span>`);
    // версия
    const inst = mod.installed || {};
    const latest = mod.latest || {};
    let versionLine = "";
    if (inst.version) {
        versionLine = `${esc(t("mods.installed"))}: <b>${esc(inst.version)}</b>`;
        if (latest.version && mod.hasUpdate) {
            versionLine += `${icon("arrow-r", "arrow")}<span class="new">${esc(latest.version)}</span>`;
        }
        if (!inst.fileExists) {
            versionLine += ` <span class="miss">(${esc(t("mods.fileMissing"))})</span>`;
        }
    } else if (mod.source === "local" && inst.fileExists && inst.fileName) {
        // локальный файл (импортирован без сопоставления с сервисом): показываем имя файла
        versionLine = `${esc(t("mods.installed"))}: <b>${esc(inst.fileName)}</b>`;
    } else {
        versionLine = `<span class="muted">${esc(t("mods.notInstalled"))}</span>`;
        if (latest.version) versionLine += ` · ${esc(t("mods.latest"))}: <b>${esc(latest.version)}</b>`;
    }
    versionLine += ` <span class="chan">${esc(mod.channel || "release")}</span>`;
    // связи (полный список — в title: строка обрезается по ширине карточки)
    const rels = [];
    if ((mod.dependencies || []).length) {
        const names = mod.dependencies.map(id => byId[id] ? esc(byId[id].name) : `<s>${esc(id)}</s>`);
        const namesPlain = mod.dependencies.map(id => (byId[id] && byId[id].name) || id).join(", ");
        rels.push(`<span class="rel-line" title="${esc(t("mods.deps"))}: ${esc(namesPlain)}">${icon("link")}<span>${esc(t("mods.deps"))}: <b>${names.join(", ")}</b></span></span>`);
    }
    const conflicts = (mod.conflicts || []).map(id => byId[id] ? esc(byId[id].name) : `<s>${esc(id)}</s>`);
    const conflictsPlain = (mod.conflicts || []).map(id => (byId[id] && byId[id].name) || id).join(", ");
    rels.push(`<span class="rel-line"${conflicts.length ? ` title="${esc(t("mods.conflicts"))}: ${esc(conflictsPlain)}"` : ""}>${icon("warn")}<span>${esc(t("mods.conflicts"))}: ${conflicts.length ? "<b>" + conflicts.join(", ") + "</b>" : esc(t("mods.noConflicts"))}</span></span>`);
    // Галочка «Опциональный» — НЕ включение/выключение (его больше нет:
    // скачан — значит нужен), а переключатель опциональности: игрок
    // в лаунчере сможет выключить этот мод у себя. Меняется сразу через API.
    const checkHtml = isAdmin
        ? `<div class="mod-check"><label title="${esc(t("mods.optionalHint"))}">
             <input type="checkbox" data-optional="${esc(mod.id)}" ${mod.optional ? "checked" : ""}>
             <span>${esc(t("mods.optionalToggle"))}</span></label></div>`
        : "";
    // действия
    const actions = [];
    if (isAdmin) {
        // «Скачать заново»: версия установлена, но файла нет на диске (пострадал
        // от обновления на старой версии, ручной чистки и т.п.) — кнопка видна
        // даже если обновлений нет, сервер доставит актуальную версию.
        const fileMissing = inst.version && !inst.fileExists;
        if (mod.source !== "local" && (mod.hasUpdate || !inst.version || fileMissing)) {
            const upBtn = document.createElement("button");
            upBtn.type = "button";
            upBtn.className = "btn small" + (fileMissing ? " primary" : "");
            upBtn.innerHTML = icon("refresh") + "<span>"
                + esc(t(fileMissing ? "mods.reinstall" : "mods.update")) + "</span>";
            upBtn.addEventListener("click", () => updateMods([mod.id], upBtn));
            actions.push(upBtn);
        }
        // «Отложить»: не ставить эту версию (пока она последняя)
        if (mod.latest && mod.latest.versionId) {
            const skipBtn = document.createElement("button");
            skipBtn.type = "button";
            const postponed = !!mod.postponed;
            skipBtn.className = "btn small " + (postponed ? "ghost" : "warn");
            skipBtn.innerHTML = icon("clock") + "<span>" + esc(postponed ? t("mods.resume") : t("mods.postpone")) + "</span>";
            skipBtn.title = postponed ? t("mods.postponedCleared") : t("mods.postponedSet");
            skipBtn.addEventListener("click", () => skipUpdate(mod, !postponed));
            actions.push(skipBtn);
        }
        const setBtn = document.createElement("button");
        setBtn.type = "button";
        setBtn.className = "btn small ghost";
        setBtn.innerHTML = icon("settings") + "<span>" + esc(t("mods.settings")) + "</span>";
        // Настройки (опциональность, группа, канал, зависимости) — для ЛЮБОЙ записи:
        // обязательный ресурспак/шейдер администратор делает опциональным здесь
        setBtn.addEventListener("click", () => openSettings(mod));
        actions.push(setBtn);
        const rmBtn = document.createElement("button");
        rmBtn.type = "button";
        rmBtn.className = "btn small danger";
        rmBtn.innerHTML = icon("trash") + "<span>" + esc(t("mods.remove")) + "</span>";
        rmBtn.addEventListener("click", () => removeMod(mod));
        actions.push(rmBtn);
    }
    const actionsDiv = document.createElement("div");
    actionsDiv.className = "mod-actions";
    actions.forEach(a => actionsDiv.appendChild(a));

    card.innerHTML = `
        ${iconHtml}
        <div class="mod-body">
            <div class="mod-head"><span class="mod-name">${esc(mod.name || mod.id)}</span></div>
            <div class="mod-badges">${badges.join("")}</div>
            <div class="mod-version">${versionLine}</div>
            ${mod.note ? `<div class="mod-note">${icon("note")}<span>${esc(mod.note)}</span></div>` : ""}
            ${mod.description ? `<div class="mod-desc">${esc(mod.description)}</div>` : ""}
            <div class="mod-rels">${rels.join("")}</div>
            ${checkHtml}
        </div>`;
    attachIconFallback(card, (mod.name || mod.id || "?")[0].toUpperCase());
    card.querySelector(".mod-body").appendChild(actionsDiv);
    // обработчик галочки «Опциональный»
    const optionalBox = card.querySelector("input[data-optional]");
    if (optionalBox) {
        optionalBox.addEventListener("change", () => toggleModOptional(mod, optionalBox.checked));
    }
    return card;
}

/* ================= Опциональность модов ================= */
/** Галочка «Опциональный» на карточке: делает мод опциональным (игрок
 *  в лаунчере сможет выключить его у себя) или обязательным для всех.
 *  Тот же вызов API, что и галочка в «Настройках» записи, — modpack.json
 *  и updateOptional профиля синхронизируются сразу. Включённым мод
 *  бывает всегда: скачан — значит нужен, выключить нельзя. */
async function toggleModOptional(mod, optional) {
    const result = await api("mods/settings", {
        method: "POST",
        body: { profile: S.currentProfile, id: mod.id, optional: !!optional },
    });
    if (result.ok) {
        const entry = result.data.entry;
        const index = S.pack.mods.findIndex(m => m.id === mod.id);
        if (index >= 0 && entry) S.pack.mods[index] = entry;
        toast(optional ? t("mods.optionalOn") : t("mods.optionalOff"), "success");
        renderPack();
    } else {
        toast(result.error ? result.error.message : t("common.error"), "error");
        renderPack(); // вернуть галочку к фактическому состоянию
    }
}

/* ================= Действия ================= */
async function checkUpdates() {
    const btn = $("btn-check");
    setBtnLoading(btn, "mods.check", "refresh", true);
    const result = await api("mods/check", { method: "POST", body: { profile: S.currentProfile, force: true } });
    setBtnLoading(btn, "mods.check", "refresh", false);
    if (result.ok) {
        const relinked = result.data.relinked || 0;
        let message = t("mods.checked") + " (" + result.data.updatesAvailable + ")";
        if (relinked > 0) message += t("mods.checkRelinked", { n: relinked });
        toast(message, "success");
        showSourceWarnings(result.data.warnings);
        // 1.1.3: файлы, удалённые руками, проверка сама не восстанавливает —
        // подсказываем кнопку «Скан папки»
        if ((result.data.missingFiles || 0) > 0) {
            toast(t("mods.missingHint", { n: result.data.missingFiles }), "warn");
        }
        await loadProfiles();
        await loadPack();
    } else if (result.error && result.error.code === "update_in_progress") {
        // пока идёт обновление, проверка невозможна — откроем окно статуса
        openUpdateProgress("update");
    } else {
        toast(result.error ? result.error.message : t("common.error"), "error");
    }
}

/** Отложить обновление мода (не ставить последнюю версию) / снять отсрочку. */
async function skipUpdate(mod, skip) {
    const result = await api("mods/skip", {
        method: "POST",
        body: { profile: S.currentProfile, id: mod.id, skip: !!skip },
    });
    if (result.ok) {
        const entry = result.data.entry;
        const index = S.pack.mods.findIndex(m => m.id === mod.id);
        if (index >= 0 && entry) S.pack.mods[index] = entry;
        toast(skip ? t("mods.postponedSet") : t("mods.postponedCleared"), "success");
        renderPack();
        loadProfiles(); // счётчик обновлений профиля мог измениться
    } else {
        toast(result.error ? result.error.message : t("common.error"), "error");
    }
}

/** Перечитать конфиги профилей с диска (profiles/*.json). */
async function reloadProfile() {
    const btn = $("btn-reload-profile");
    setBtnLoading(btn, "mods.reloadProfile", "folder-open", true);
    const result = await api("profiles/reload", { method: "POST", body: {} });
    setBtnLoading(btn, "mods.reloadProfile", "folder-open", false);
    if (result.ok) {
        toast(t("mods.profileReloaded") + " (" + (result.data.profiles || 0) + ")", "success");
        const previous = S.currentProfile;
        await loadProfiles();
        // текущий профиль сохраняем, если он ещё существует
        if (previous && S.profiles.some(p => p.dir === previous)) {
            await selectProfile(previous);
        } else {
            await selectFirstProfile();
        }
    } else {
        toast(result.error ? result.error.message : t("common.error"), "error");
    }
}

async function updateMods(ids, busyBtn) {
    let body = { profile: S.currentProfile };
    if (ids === null || ids === undefined) {
        // «Обновить всё»: обновляем ТОЛЬКО то, что нашла проверка обновлений
        // (hasUpdate), плюс моды с отсутствующим файлом (чиним сломанное)
        const mods = (S.pack && S.pack.mods) || [];
        const targets = mods.filter(m => m.hasUpdate
            || (m.installed && !m.installed.fileExists && m.source));
        if (!targets.length) {
            toast(t("mods.checked") + " (0)", "success");
            return;
        }
        if (!confirm(t("mods.updateConfirm", { count: targets.length }))) return;
        body.ids = targets.map(m => m.id);
    } else {
        body.ids = ids;
    }
    const btn = ids ? (busyBtn || null) : $("btn-update-all");
    if (btn) setBtnLoading(btn, ids ? "mods.updating" : "mods.updateAll", ids ? "refresh" : "download", true);
    // 1.1.1: сервер запускает фоновую задачу и отвечает сразу — прогресс
    // (какой мод качается, сколько байт) виден в модалке опроса
    const result = await api("mods/update", { method: "POST", body });
    if (btn) setBtnLoading(btn, ids ? "mods.update" : "mods.updateAll", ids ? "refresh" : "download", false);
    if (result.ok && result.data && result.data.started) {
        openUpdateProgress(result.data.type || "update");
    } else if (result.error && result.error.code === "update_in_progress") {
        // обновление уже идёт (запущено раньше или в другой вкладке): новую
        // задачу не стартуем — просто открываем окно статуса бегущей
        openUpdateProgress("update");
    } else if (result.error && result.error.code === "backup_busy") {
        toast(t("upd.backupBusy"), "warn");
    } else {
        toast(result.error ? result.error.message : t("common.error"), "error");
    }
}

/* ===== Прогресс фоновой задачи обновления/применения (1.1.1) ===== */

/** Открыть модалку прогресса и начать опрос /api/mods/progress. */
function openUpdateProgress(type) {
    if (S.updateJob) {
        // опрос уже идёт: просто снова показать модалку (могла быть закрыта)
        if (!$("upd-progress-list")) openUpdateProgressModal(S.updateJob.type);
        return;
    }
    S.updateJob = { type: type || "update", timer: null, openedAt: Date.now() };
    openUpdateProgressModal(type || "update");
    pollUpdateProgress();
}

/** Восстановить окно статуса после перезагрузки страницы: сервер в /api/mods
 *  отдал снимок бегущей задачи — прицепляемся к ней и показываем прогресс. */
function restoreUpdateJob(job) {
    if (S.updateJob) return; // окно уже открыто — не дублируем
    S.updateJob = { type: job.type || "update", timer: null, openedAt: Date.now() };
    openUpdateProgressModal(job.type || "update");
    renderUpdateProgress(job);
    pollUpdateProgress();
}

function openUpdateProgressModal(type) {
    openModal(
        '<h3 class="modal-title" id="upd-title">' + esc(t("upd.title")) + "</h3>"
        + '<div class="upd-phase" id="upd-phase"><span class="spin"></span><span id="upd-phase-text">'
            + esc(t("upd.preparing")) + "</span></div>"
        + '<div class="upd-bar"><div class="upd-bar-fill indeterminate" id="upd-bar"></div></div>'
        + '<div class="upd-count muted small" id="upd-count"></div>'
        + '<div class="upd-list" id="upd-progress-list" role="log" aria-live="polite"></div>',
        [{ label: t("upd.hide"), onclick: closeModal, className: "btn ghost" },
         { label: t("upd.stop"), onclick: stopUpdate, className: "btn danger", id: "upd-stop-btn" }]);
}

/** «Остановить»: начатые скачивания докачаются, не начатые — отменятся. */
async function stopUpdate() {
    const result = await api("mods/update/stop", { method: "POST", body: { profile: S.currentProfile } });
    if (result.ok) {
        markStopClicked();
    } else {
        toast(result.error ? result.error.message : t("common.error"), "error");
    }
}

/** Кнопка «Остановить» после клика: неактивна до конца задачи. */
function markStopClicked() {
    const btn = $("upd-stop-btn");
    if (btn) {
        btn.disabled = true;
        btn.textContent = t("upd.stopping");
    }
}

async function pollUpdateProgress() {
    if (!S.updateJob) return;
    // предохранитель: сменили профиль с закрытой модалкой и прочие зависшие
    // случаи — через 30 минут опрос останавливается сам (большие обновления
    // на медленной сети могут идти дольше четверти часа)
    if (Date.now() - (S.updateJob.openedAt || 0) > 30 * 60 * 1000) {
        S.updateJob = null;
        return;
    }
    let result = null;
    try {
        result = await api("mods/progress?profile=" + encodeURIComponent(S.currentProfile));
    } catch (e) { /* сеть — просто повторим через интервал */ }
    // сессия истекла — login-экран уже показан, опрос останавливаем
    if (S.updateJob && result && result.error && result.error.code === "unauthorized") {
        S.updateJob = null;
        return;
    }
    if (S.updateJob && result && result.ok && result.data && result.data.job) {
        const job = result.data.job;
        renderUpdateProgress(job);
        if (job.finished) {
            // «свежий» финиш — наш; давно завершённая задача (мимо нас) не тостится
            const fresh = !job.finishedAt || Date.now() - job.finishedAt < 30000;
            finishUpdateProgress(job, fresh);
            return;
        }
    }
    if (S.updateJob) {
        S.updateJob.timer = setTimeout(pollUpdateProgress, 700);
    }
}

function updPhaseText(job) {
    if (job.stopRequested && !job.finished) {
        return t("upd.stoppingPhase");
    }
    switch (job.phase) {
        case "checking":
            return t("upd.checking") + (job.checkTotal > 0 ? " " + job.checkDone + "/" + job.checkTotal : "");
        case "downloading": return t("upd.downloading");
        case "rehashing": return t("upd.rehashing");
        case "done": return t("upd.done");
        case "error": return t("upd.failed") + (job.error ? ": " + job.error : "");
        default: return t("upd.preparing");
    }
}

function updBytesText(m) {
    if (m.status !== "downloading") return "";
    if (m.totalBytes > 0) return fmtBytes(m.downloaded) + " / " + fmtBytes(m.totalBytes);
    return m.downloaded > 0 ? fmtBytes(m.downloaded) : "";
}

function updRow(m) {
    const icons = { waiting: "clock", checking: "search", downloading: "download", done: "check", skipped: "info", cancelled: "x", error: "alert" };
    const statusKey = "upd.st" + m.status.charAt(0).toUpperCase() + m.status.slice(1);
    const parts = [t(statusKey)];
    if (m.detail) parts.push(esc(m.detail));
    const bytes = updBytesText(m);
    if (bytes) parts.push(bytes);
    const row = document.createElement("div");
    row.className = "upd-row st-" + m.status;
    row.innerHTML = icon(icons[m.status] || "clock")
        + '<span class="upd-name">' + esc(m.title || m.id) + "</span>"
        + '<span class="upd-meta">' + parts.join(" · ") + "</span>";
    return row;
}

function renderUpdateProgress(job) {
    // модалка могла быть закрыта/заменена — тогда только ждём финиша
    const phaseText = $("upd-phase-text");
    if (!phaseText) return;
    // тип мог отличаться (прицепились к бегущей задаче) — заголовок честный
    const titleEl = $("upd-title");
    if (titleEl) titleEl.textContent = t("upd.title");
    phaseText.textContent = updPhaseText(job);
    // на финише спиннер фазы больше не крутится
    const spin = $("upd-phase").querySelector(".spin");
    if (spin) spin.style.display = job.finished ? "none" : "";
    // остановка запрошена: докачиваем начатое, очередь отменяется — кнопка больше не нужна
    if (job.stopRequested && !job.finished) markStopClicked();
    const bar = $("upd-bar");
    if (bar) {
        if (job.total > 0) {
            bar.style.width = Math.round(100 * Math.min(1, job.completed / job.total)) + "%";
            bar.classList.remove("indeterminate");
        } else {
            bar.style.width = "100%";
            bar.classList.add("indeterminate");
        }
    }
    const count = $("upd-count");
    if (count) count.textContent = job.total > 0 ? (job.completed + " / " + job.total) : "";
    const list = $("upd-progress-list");
    if (list) {
        list.innerHTML = "";
        (job.mods || []).forEach(m => list.appendChild(updRow(m)));
    }
}

function finishUpdateProgress(job, fresh) {
    if (S.updateJob && S.updateJob.timer) clearTimeout(S.updateJob.timer);
    S.updateJob = null;
    if (!fresh) return;
    const results = job.results || [];
    const failed = results.filter(r => r.error);
    const cancelled = results.filter(r => r.cancelled).length;
    if (job.ok) {
        if (job.stopped) {
            // задача остановлена кнопкой: сколько докачано, сколько отменено
            const done = results.filter(r => !r.error && !r.cancelled).length;
            toast(t("upd.stoppedToast", { done: done, cancelled: cancelled }), "warn");
        } else {
            toast(t("mods.updated") + ": " + (results.length - failed.length - cancelled) + "/"
                + Math.max(1, results.length - cancelled),
                failed.length ? "warn" : "success");
        }
    } else {
        toast(job.error ? job.error : t("common.error"), "error");
    }
    setTimeout(async () => {
        if ($("upd-progress-list")) closeModal();
        await loadProfiles();
        await loadPack();
    }, 900);
}

async function rehash() {
    if (!isAdmin()) return;
    const btn = $("btn-rehash");
    setBtnLoading(btn, "mods.rehash", "hash", true);
    const result = await api("rehash", { method: "POST", body: { profile: S.currentProfile } });
    setBtnLoading(btn, "mods.rehash", "hash", false);
    if (result.ok) {
        toast(t("mods.rehashed"), "success");
    } else {
        toast(result.error ? result.error.message : t("mods.rehashFailed"), "error");
    }
}

async function removeMod(mod) {
    if (!confirm(t("mods.removeConfirm", { name: mod.name || mod.id }))) return;
    const result = await api("mods/remove", { method: "POST", body: { profile: S.currentProfile, ids: [mod.id] } });
    if (result.ok) {
        await loadProfiles();
        await loadPack();
    } else {
        toast(result.error ? result.error.message : t("common.error"), "error");
    }
}

async function cleanOrphans() {
    const card = document.querySelector(".orphan-card");
    const files = card
        ? Array.from(card.querySelectorAll(".orphan-item input:checked"))
            .map(i => ({ name: i.value, kind: i.dataset.kind || "mod" }))
        : [];
    if (card && !files.length) {
        toast(t("mods.selectOne"), "warn");
        return;
    }
    const result = await api("mods/clean", { method: "POST", body: { profile: S.currentProfile, files } });
    if (result.ok) {
        toast(t("mods.cleaned") + ": " + (result.data.removed || 0), "success");
        orphanShowAll = false;
        await loadPack();
    } else {
        toast(result.error ? result.error.message : t("common.error"), "error");
    }
}

/** Импорт выбранных «лишних» файлов в модпак: сопоставление по хэшам Modrinth/CurseForge. */
async function importOrphans() {
    const card = document.querySelector(".orphan-card");
    if (!card) return;
    const files = Array.from(card.querySelectorAll(".orphan-item input:checked"))
        .map(i => ({ name: i.value, kind: i.dataset.kind || "mod" }));
    if (!files.length) {
        toast(t("mods.selectOne"), "warn");
        return;
    }
    const btn = card.querySelector(".orphan-import");
    const original = btn.textContent;
    btn.disabled = true;
    btn.textContent = t("mods.importing");
    const result = await api("mods/import", { method: "POST", body: { profile: S.currentProfile, files } });
    if (!result.ok) {
        toast(result.error ? result.error.message : t("common.error"), "error");
        btn.disabled = false;
        btn.textContent = original;
        return;
    }
    const d = result.data;
    toast(t("mods.importDone", {
        imported: d.imported || 0,
        local: d.local || 0,
        skipped: d.skipped || 0,
        failed: d.failed || 0,
    }), (d.failed || 0) > 0 ? "warn" : "success");
    if (d.limit) toast(t("mods.importLimit", { n: d.limit }), "warn");
    showSourceWarnings(d.warnings);
    orphanShowAll = false;
    await loadProfiles();
    await loadPack();
}

/* ================= Скан папки (1.1.3) ================= */
/** Кнопка «Скан папки»: перечитываем папки контента и показываем, где они
 *  разошлись со списком модпака — удалённые руками файлы (восстановить /
 *  убрать из списка), добавленные руками (в состав / удалить с диска) и
 *  переименования файлов с кодами цвета (&a, §c… — GravitLauncher на них
 *  спотыкается; переименовываются на сервере автоматически). Каждый пункт
 *  обрабатывается по одному, «оставшиеся» — массовыми кнопками. */
async function openFolderScan() {
    if (!isAdmin()) return;
    const btn = $("btn-scan");
    setBtnLoading(btn, "mods.scan", "search", true);
    const result = await api("mods/scan", { method: "POST", body: { profile: S.currentProfile } });
    setBtnLoading(btn, "mods.scan", "search", false);
    if (!result.ok) {
        if (result.error && result.error.code === "update_in_progress") {
            openUpdateProgress("update");
        } else {
            toast(result.error ? result.error.message : t("common.error"), "error");
        }
        return;
    }
    S.scan = {
        renamed: result.data.renamed || [],
        deleted: result.data.deleted || [],
        added: result.data.added || [],
    };
    renderScanModal();
}

/** Крайний случай «нажал на одном — и пока делается — нажать на все»:
 *  пока идёт задача обновления (восстановление — тоже задача), все кнопки
 *  скана неактивны; сервер дополнительно отклонит повтор (update_in_progress). */
function scanBusy() {
    return !!S.updateJob;
}

function scanKindBadge(kind) {
    return kind && kind !== "mod"
        ? `<span class="badge kind-${esc(kind)}">${esc(t("kind." + kind))}</span> `
        : "";
}

function renderScanModal() {
    if (!S.scan) return;
    const d = S.scan;
    const busy = scanBusy();
    const rows = [];
    // переименования — информационная секция (уже выполнены на сервере)
    if (d.renamed.length) {
        rows.push(`<div class="scan-head">${icon("info")}<b>${esc(t("mods.scanRenamed", { n: d.renamed.length }))}</b></div>`);
        rows.push('<div class="scan-list">');
        d.renamed.forEach(r => {
            rows.push(`<div class="scan-row">${scanKindBadge(r.kind)}`
                + `<span class="scan-name">${esc(r.from)}</span>`
                + `<span class="scan-meta">${icon("arrow-r", "arrow")} ${esc(r.to)}</span></div>`);
        });
        rows.push("</div>");
    }
    // удалённые вручную: восстановить (скачать заново) или убрать из списка
    if (d.deleted.length) {
        rows.push(`<div class="scan-head warn">${icon("warn")}<b>${esc(t("mods.scanDeleted", { n: d.deleted.length }))}</b></div>`);
        rows.push('<div class="scan-list">');
        d.deleted.forEach(item => {
            const id = esc(item.id);
            const restore = item.canRestore
                ? `<button class="btn small primary" data-scan-restore="${id}" ${busy ? "disabled" : ""}>${icon("download")}<span>${esc(t("mods.scanRestore"))}</span></button>`
                : "";
            rows.push(`<div class="scan-row">${scanKindBadge(item.kind)}`
                + `<span class="scan-name">${esc(item.name || item.id)}</span>`
                + `<span class="scan-meta">${esc(item.fileName || "")}${item.version ? " · " + esc(item.version) : ""}${item.canRestore ? "" : " · " + esc(t("mods.scanNoSource"))}</span>`
                + `<span class="scan-actions">${restore}`
                + `<button class="btn small danger" data-scan-remove="${id}" ${busy ? "disabled" : ""}>${icon("trash")}<span>${esc(t("mods.scanRemoveEntry"))}</span></button>`
                + "</span></div>");
        });
        rows.push("</div>");
    }
    // добавленные вручную: принять в состав или удалить файл
    if (d.added.length) {
        rows.push(`<div class="scan-head">${icon("file")}<b>${esc(t("mods.scanAdded", { n: d.added.length }))}</b></div>`);
        rows.push('<div class="scan-list">');
        d.added.forEach(f => {
            const key = esc(f.name);
            const kind = esc(f.kind || "mod");
            rows.push(`<div class="scan-row">${scanKindBadge(f.kind)}`
                + `<span class="scan-name">${key}</span>`
                + `<span class="scan-actions">`
                + `<button class="btn small primary" data-scan-adopt="${key}" data-scan-adopt-kind="${kind}" ${busy ? "disabled" : ""}>${icon("package")}<span>${esc(t("mods.scanAdopt"))}</span></button>`
                + `<button class="btn small danger" data-scan-delete="${key}" data-scan-delete-kind="${kind}" ${busy ? "disabled" : ""}>${icon("trash")}<span>${esc(t("mods.scanDeleteFile"))}</span></button>`
                + "</span></div>");
        });
        rows.push("</div>");
    }
    if (!rows.length) {
        rows.push(`<div class="scan-empty">${icon("check")}<span>${esc(t("mods.scanEmpty"))}</span></div>`);
    }
    // массовые действия — по «оставшимся» (тем, что ещё в списках)
    const bulk = [];
    if (d.deleted.length) {
        const restorable = d.deleted.filter(x => x.canRestore).length;
        if (restorable) {
            bulk.push(`<button class="btn small primary" id="scan-restore-all" ${busy ? "disabled" : ""}>${icon("download")}<span>${esc(t("mods.scanRestoreAll", { n: restorable }))}</span></button>`);
        }
        bulk.push(`<button class="btn small danger" id="scan-remove-all" ${busy ? "disabled" : ""}>${icon("trash")}<span>${esc(t("mods.scanRemoveAll", { n: d.deleted.length }))}</span></button>`);
    }
    if (d.added.length) {
        bulk.push(`<button class="btn small primary" id="scan-adopt-all" ${busy ? "disabled" : ""}>${icon("package")}<span>${esc(t("mods.scanAdoptAll", { n: d.added.length }))}</span></button>`);
        bulk.push(`<button class="btn small danger" id="scan-delete-all" ${busy ? "disabled" : ""}>${icon("trash")}<span>${esc(t("mods.scanDeleteAll", { n: d.added.length }))}</span></button>`);
    }
    const body = `<h3 class="modal-title">${esc(t("mods.scanTitle"))}</h3>`
        + `<div class="muted small scan-hint">${esc(t("mods.scanHint"))}</div>`
        + rows.join("")
        + (bulk.length ? `<div class="scan-bulk">${bulk.join("")}</div>` : "")
        + (busy ? `<div class="muted small">${esc(t("mods.scanJobBusy"))}</div>` : "");
    openModal(body, [{ label: t("mods.close"), onclick: () => { closeModal(); loadPack(); }, className: "btn ghost" }]);
    const backdrop = $("modal-backdrop");
    if (!backdrop) return;
    backdrop.querySelectorAll("[data-scan-restore]").forEach(b =>
        b.addEventListener("click", () => scanRestore([b.dataset.scanRestore])));
    backdrop.querySelectorAll("[data-scan-remove]").forEach(b =>
        b.addEventListener("click", () => scanRemoveEntries([b.dataset.scanRemove])));
    backdrop.querySelectorAll("[data-scan-adopt]").forEach(b =>
        b.addEventListener("click", () => scanAdopt([{ name: b.dataset.scanAdopt, kind: b.dataset.scanAdoptKind }])));
    backdrop.querySelectorAll("[data-scan-delete]").forEach(b =>
        b.addEventListener("click", () => scanDeleteFiles([{ name: b.dataset.scanDelete, kind: b.dataset.scanDeleteKind }])));
    const restoreAll = $("scan-restore-all");
    if (restoreAll) restoreAll.addEventListener("click", () => {
        const ids = S.scan.deleted.filter(x => x.canRestore).map(x => x.id);
        if (ids.length && confirm(t("mods.scanRestoreConfirm", { n: ids.length }))) scanRestore(ids);
    });
    const removeAll = $("scan-remove-all");
    if (removeAll) removeAll.addEventListener("click", () => {
        const ids = S.scan.deleted.map(x => x.id);
        if (ids.length && confirm(t("mods.scanRemoveConfirm", { n: ids.length }))) scanRemoveEntries(ids);
    });
    const adoptAll = $("scan-adopt-all");
    if (adoptAll) adoptAll.addEventListener("click", () => {
        const files = S.scan.added.map(f => ({ name: f.name, kind: f.kind || "mod" }));
        if (files.length && confirm(t("mods.scanAdoptConfirm", { n: files.length }))) scanAdopt(files);
    });
    const deleteAll = $("scan-delete-all");
    if (deleteAll) deleteAll.addEventListener("click", () => {
        const files = S.scan.added.map(f => ({ name: f.name, kind: f.kind || "mod" }));
        if (files.length && confirm(t("mods.scanDeleteConfirm", { n: files.length }))) scanDeleteFiles(files);
    });
}

/** Восстановить удалённые вручную файлы: обычная задача обновления по явному
 *  списку — отсутствующий файл скачивается даже без новой версии. Прогресс —
 *  в модалке обновления; кнопки скана снова разблокируются после финиша. */
async function scanRestore(ids) {
    const result = await api("mods/update", { method: "POST", body: { profile: S.currentProfile, ids } });
    if (result.ok && result.data && result.data.started) {
        S.scan.deleted = S.scan.deleted.filter(x => !ids.includes(x.id));
        closeModal();
        openUpdateProgress(result.data.type || "update");
    } else if (result.error && result.error.code === "update_in_progress") {
        toast(t("mods.scanJobBusy"), "warn");
    } else if (result.error && result.error.code === "backup_busy") {
        toast(t("upd.backupBusy"), "warn");
    } else {
        toast(result.error ? result.error.message : t("common.error"), "error");
    }
}

/** Убрать записи из списка модпака — файлы уже отсутствуют на диске. */
async function scanRemoveEntries(ids) {
    const result = await api("mods/remove", { method: "POST", body: { profile: S.currentProfile, ids } });
    if (result.ok) {
        const removed = result.data.removed || ids;
        S.scan.deleted = S.scan.deleted.filter(x => !removed.includes(x.id));
        renderScanModal();
        loadProfiles();
        loadPack();
    } else {
        toast(result.error ? result.error.message : t("common.error"), "error");
    }
}

/** Принять добавленные вручную файлы в состав: сопоставление по хэшам
 *  Modrinth/CurseForge, без совпадения — локальная запись. */
async function scanAdopt(files) {
    const result = await api("mods/import", { method: "POST", body: { profile: S.currentProfile, files } });
    if (!result.ok) {
        if (result.error && result.error.code === "update_in_progress") {
            toast(t("mods.scanJobBusy"), "warn");
        } else {
            toast(result.error ? result.error.message : t("common.error"), "error");
        }
        return;
    }
    const d = result.data;
    toast(t("mods.importDone", {
        imported: d.imported || 0,
        local: d.local || 0,
        skipped: d.skipped || 0,
        failed: d.failed || 0,
    }), (d.failed || 0) > 0 ? "warn" : "success");
    const names = new Set(files.map(f => (f.kind || "mod") + ":" + f.name));
    S.scan.added = S.scan.added.filter(f => !names.has((f.kind || "mod") + ":" + f.name));
    renderScanModal();
    loadProfiles();
    loadPack();
}

/** Удалить добавленные вручную файлы с диска. */
async function scanDeleteFiles(files) {
    const result = await api("mods/clean", { method: "POST", body: { profile: S.currentProfile, files } });
    if (result.ok) {
        const names = new Set(files.map(f => (f.kind || "mod") + ":" + f.name));
        S.scan.added = S.scan.added.filter(f => !names.has((f.kind || "mod") + ":" + f.name));
        renderScanModal();
        loadPack();
    } else {
        toast(result.error ? result.error.message : t("common.error"), "error");
    }
}

/* ================= Загрузчик профиля и поиск по установленным ================= */
/** Сохранить загрузчик профиля по умолчанию (POST /api/profiles/settings).
 *  Применяется автоматически в поиске (чипы загрузчиков) и при проверке
 *  обновлений; в поиске на конкретный запрос его всё ещё можно поменять. */
async function saveProfileLoader(loader) {
    if (!S.currentProfile) return;
    const result = await api("profiles/settings", {
        method: "POST",
        body: { profile: S.currentProfile, loader: loader },
    });
    if (!result.ok) {
        toast(result.error ? result.error.message : t("common.error"), "error");
        renderPack(); // откатить селектор к прежнему значению
        return;
    }
    if (S.pack) {
        S.pack.loader = result.data.loader;
        S.pack.loaderOverride = result.data.loaderOverride || "";
    }
    const profile = S.profiles.find(p => p.dir === S.currentProfile);
    if (profile) profile.loader = result.data.loader;
    renderProfiles();
    renderPack();
    toast(t("mods.loaderSaved"), "success");
    // поиск: дефолт загрузчика сменился — обновить чипы и перезапустить поиск,
    // если вкладка открыта прямо сейчас
    if (S.search.inited) {
        applyProfileFilters();
        syncSearchControls();
        renderSearchFilters();
        if (!$("panel-search").classList.contains("hidden")) runSearch(true);
    }
}

/** Живой фильтр списка модов по запросу (имя / id / имя файла). */
let modSearchTimer = null;
function onModSearchInput() {
    if (modSearchTimer) clearTimeout(modSearchTimer);
    modSearchTimer = setTimeout(() => {
        S.modsFilter.query = $("mod-search").value;
        updateFilterBar();
        renderPack();
    }, 160);
}

function clearModSearch() {
    S.modsFilter.query = "";
    $("mod-search").value = "";
    updateFilterBar();
    renderPack();
    $("mod-search").focus();
}

/* ================= Каталог модов (вкладка «Поиск») ================= */
const LOADERS = {
    mod: ["any", "fabric", "forge", "neoforge", "quilt"],
    // у ресурспаков загрузчика нет — только «Любой»
    resourcepack: ["any"],
    // «загрузчики» шейдеров на Modrinth — iris / optifine / canvas
    shader: ["any", "iris", "optifine", "canvas"],
};
const MC_VERSIONS = ["1.21.4", "1.21.3", "1.21.1", "1.21", "1.20.6", "1.20.4", "1.20.2", "1.20.1",
    "1.19.4", "1.19.2", "1.18.2", "1.17.1", "1.16.5", "1.15.2", "1.12.2", "1.10.2", "1.8.9", "1.7.10"];

/** Загрузчики, подходящие текущему типу контента. */
function typeLoaders() {
    return LOADERS[S.search.type] || LOADERS.mod;
}

/** Открытие вкладки «Поиск»: первая инициализация или обновление под профиль. */
function onSearchTabOpen() {
    if (!S.search.inited) {
        initSearchTab();
        return;
    }
    if (S.search.lastProfile !== S.currentProfile) {
        applyProfileFilters();
        syncSearchControls();
        renderSearchFilters();
        runSearch(true);
    }
}

function initSearchTab() {
    S.search.inited = true;
    $("mc-versions").innerHTML = MC_VERSIONS.map(v => `<option value="${v}">`).join("");
    applyProfileFilters();
    syncSearchControls();
    renderSearchFilters();
    runSearch(true);
    if (!isAdmin()) loadMine(); // наблюдателю — список своих предложений
}

function isAdmin() {
    return !!(S.session && (S.session.role === "admin" || S.session.role === "superadmin"));
}

/** Суперадмин (логин задан вручную в config.superAdmins):
 *  полные админ-права + управление ролями других админов. */
function isSuperAdmin() {
    return !!(S.session && S.session.role === "superadmin");
}

/** Дефолт фильтров — версия и загрузчик выбранного профиля (как в Prism Launcher).
 *  Для ресурспаков/шейдеров загрузчик профиля не применяется (только «Любой»). */
function applyProfileFilters() {
    const profile = S.profiles.find(p => p.dir === S.currentProfile);
    S.search.lastProfile = S.currentProfile;
    S.search.gameVersion = (profile && profile.gameVersion) || "";
    const loaders = typeLoaders();
    S.search.loader = loaders.includes(S.search.loader) ? S.search.loader : "any";
    S.search.offset = 0;
}

/** Синхронизировать контролы с состоянием (после смены профиля/загрузчика). */
function syncSearchControls() {
    $("search-query").value = S.search.query;
    $("search-type").value = S.search.type;
    $("search-source").value = S.search.source;
    $("filter-sort").value = S.search.sort;
    $("filter-version").value = S.search.gameVersion;
}

function renderSearchFilters() {
    // чипы загрузчиков — по типу контента (моды: fabric/forge/…, шейдеры: iris/optifine/canvas,
    // ресурспаки: только «Любой»)
    const chips = $("loader-chips");
    chips.innerHTML = "";
    const loaders = typeLoaders();
    // загрузчик вне списка текущего типа (напр. «fabric» при переключении на шейдеры) — сбрасываем
    if (!loaders.includes(S.search.loader)) S.search.loader = "any";
    loaders.forEach(loader => {
        const chip = document.createElement("button");
        chip.type = "button";
        chip.className = "chip" + (S.search.loader === loader ? " active" : "");
        chip.textContent = loader === "any" ? t("search.any")
            : loader === "iris" ? "Iris"
            : loader === "optifine" ? "OptiFine"
            : loader === "canvas" ? "Canvas"
            : loader.charAt(0).toUpperCase() + loader.slice(1);
        chip.addEventListener("click", () => {
            S.search.loader = loader;
            renderSearchFilters();
            runSearch(true);
        });
        chips.appendChild(chip);
    });
    renderCategoryList();
}

/** Категории источника по типу контента (Modrinth /tag/category, CF /v1/categories) — с кэшем. */
async function loadCategories(source, type) {
    const key = source + ":" + (type || "mod");
    if (S.categories[key]) return S.categories[key];
    const result = await api("mods/categories?source=" + encodeURIComponent(source)
        + "&type=" + encodeURIComponent(type || "mod"));
    S.categories[key] = result.ok ? (result.data || []) : [];
    return S.categories[key];
}

async function renderCategoryList() {
    const box = $("filter-cats");
    const source = S.search.source;
    const type = S.search.type;
    const cats = await loadCategories(source, type);
    box.innerHTML = "";
    if (!cats.length) {
        box.innerHTML = `<span class="muted small">${esc(t("search.noCategories"))}</span>`;
        return;
    }
    cats.forEach(category => {
        const label = document.createElement("label");
        label.className = "cat-item";
        const checked = S.search.cats.has(category.id);
        label.innerHTML = `<input type="checkbox" value="${esc(category.id)}" ${checked ? "checked" : ""}>
            <span>${esc(category.name)}</span>`;
        label.querySelector("input").addEventListener("change", (e) => {
            if (e.target.checked) S.search.cats.add(category.id);
            else S.search.cats.delete(category.id);
            runSearch(true);
        });
        box.appendChild(label);
    });
}

async function runSearch(reset) {
    if (!S.currentProfile) {
        $("search-grid").innerHTML = "";
        const empty = $("search-empty");
        empty.innerHTML = icon("inbox", "empty-icon") + "<p>" + esc(t("search.needProfile")) + "</p>";
        empty.classList.remove("hidden");
        $("search-meta").innerHTML = "";
        $("search-page").textContent = "";
        $("btn-search-prev").disabled = true;
        $("btn-search-next").disabled = true;
        return;
    }
    if (reset) S.search.offset = 0;
    if (S.search.loading) return;
    S.search.loading = true;
    $("search-meta").innerHTML =
        '<span class="spin" aria-hidden="true"></span><span class="muted small">' + esc(t("search.searching")) + "</span>";
    const s = S.search;
    const params = new URLSearchParams({
        profile: S.currentProfile,
        source: s.source,
        type: s.type || "mod",
        query: s.query,
        loader: s.loader,
        gameVersion: s.gameVersion || "any",
        sort: s.sort,
        offset: String(s.offset),
        limit: String(s.limit),
    });
    if (s.cats.size) params.set("categories", Array.from(s.cats).join(","));
    const result = await api("mods/search?" + params.toString());
    S.search.loading = false;
    if (!result.ok) {
        $("search-meta").innerHTML = "";
        $("search-grid").innerHTML = "";
        $("search-page").textContent = "";
        $("btn-search-prev").disabled = true;
        $("btn-search-next").disabled = true;
        const empty = $("search-empty");
        empty.innerHTML = icon("alert", "empty-icon") + "<p>"
            + esc(result.error ? result.error.message : t("common.error")) + "</p>";
        empty.classList.remove("hidden");
        return;
    }
    const items = result.data.items || [];
    S.search.total = result.data.total || 0;
    renderSearchResults(items);
    // предупреждение источника (например, CurseForge: загрузчик без версии игры)
    const warnBox = $("search-warning");
    if (warnBox) {
        if (result.data.warning) {
            warnBox.textContent = t("search." + result.data.warning);
            warnBox.classList.remove("hidden");
        } else {
            warnBox.textContent = "";
            warnBox.classList.add("hidden");
        }
    }
    updateSearchPager();
}

/** Fallback-иконка карточки: если картинка не загрузилась — буква вместо неё. */
function attachIconFallback(card, letter) {
    const img = card.querySelector("img.mod-icon");
    if (!img) return;
    img.addEventListener("error", () => {
        const div = document.createElement("div");
        div.className = "mod-icon-fallback";
        div.textContent = letter;
        img.replaceWith(div);
    });
}

function renderSearchResults(items) {
    const grid = $("search-grid");
    const empty = $("search-empty");
    grid.innerHTML = "";
    $("search-meta").innerHTML =
        '<span class="muted small">' + esc(t("search.results", { n: fmtNum(S.search.total) })) + "</span>";
    if (!items.length) {
        empty.innerHTML = icon("search", "empty-icon") + "<p>" + esc(t("search.noResults")) + "</p>";
        empty.classList.remove("hidden");
        return;
    }
    empty.classList.add("hidden");
    const isAdmin = S.session && (S.session.role === "admin" || S.session.role === "superadmin");
    items.forEach(hit => grid.appendChild(renderSearchCard(hit, isAdmin)));
}

function renderSearchCard(hit, isAdmin) {
    const card = document.createElement("div");
    card.className = "mod-card result-card";
    const iconHtml = hit.iconUrl
        ? `<img class="mod-icon" src="${esc(iconSrc(hit.iconUrl))}" alt="" loading="lazy">`
        : `<div class="mod-icon-fallback">${esc((hit.title || "?")[0].toUpperCase())}</div>`;
    const badges = [`<span class="badge ${hit.source === "modrinth" ? "mr" : "cf"}">${hit.source === "modrinth" ? "Modrinth" : "CurseForge"}</span>`];
    const kind = S.search.type || "mod";
    if (kind !== "mod") {
        badges.push(`<span class="badge kind-${esc(kind)}">${icon(kind === "resourcepack" ? "image" : "sparkles")}${esc(t("kind." + kind))}</span>`);
    }
    (hit.categories || []).forEach(category => badges.push(`<span class="badge cat">${esc(category)}</span>`));
    const stats = [`<span class="hit-stat" title="${esc(t("search.downloads"))}">${icon("download")}${fmtNum(hit.downloads)}</span>`];
    if (hit.follows) stats.push(`<span class="hit-stat" title="${esc(t("sort.follows"))}">${icon("eye")}${fmtNum(hit.follows)}</span>`);
    if (hit.dateModified) stats.push(`<span class="hit-stat">${icon("clock")}${esc(fmtDate(hit.dateModified))}</span>`);
    card.innerHTML = `
        ${iconHtml}
        <div class="mod-body">
            <div class="mod-head"><span class="mod-name">${esc(hit.title || hit.slug)}</span>
                <span class="muted small">${esc(hit.slug || "")}</span></div>
            <div class="mod-badges">${badges.join("")}</div>
            <div class="hit-stats">${stats.join("")}</div>
            ${hit.description ? `<div class="mod-desc">${esc(hit.description)}</div>` : ""}
        </div>`;
    attachIconFallback(card, (hit.title || hit.slug || "?")[0].toUpperCase());
    const actions = document.createElement("div");
    actions.className = "mod-actions";
    if (hit.alreadyAdded) {
        const chip = document.createElement("span");
        chip.className = "badge ok";
        chip.innerHTML = icon("check") + esc(t("mods.alreadyAdded"));
        actions.appendChild(chip);
    } else if (hit.suggested) {
        // уже предложено — ждёт решения администратора
        const chip = document.createElement("span");
        chip.className = "badge suggested";
        chip.innerHTML = icon("clock") + esc(t("suggest.suggestedBadge"));
        actions.appendChild(chip);
    } else if (isAdmin) {
        const btn = document.createElement("button");
        btn.type = "button";
        btn.className = "btn primary small";
        btn.innerHTML = icon("plus") + "<span>" + esc(t("mods.add")) + "</span>";
        btn.addEventListener("click", async () => {
            btn.disabled = true;
            btn.innerHTML = '<span class="spin" aria-hidden="true"></span>';
            await addMod(hit.source, hit.projectId, hit.title);
        });
        actions.appendChild(btn);
    } else if (suggestAvailable()) {
        // наблюдатель: предложить в сборку (админ решит — скачать или отклонить)
        const btn = document.createElement("button");
        btn.type = "button";
        btn.className = "btn primary small";
        btn.innerHTML = icon("send") + "<span>" + esc(t("suggest.button")) + "</span>";
        btn.addEventListener("click", async () => {
            btn.disabled = true;
            btn.innerHTML = '<span class="spin" aria-hidden="true"></span>';
            await suggestProject(hit.source, hit.projectId, btn);
        });
        actions.appendChild(btn);
    }
    card.querySelector(".mod-body").appendChild(actions);
    return card;
}

/** Доступна ли кнопка «Предложить» наблюдателю. */
function suggestAvailable() {
    if (!S.session) return false;
    if (isAdmin()) return false;
    if (S.session.suggestEnabled === false || S.session.suggestBanned) return false;
    const limit = S.session.suggestLimit || 0;
    const pending = S.mine ? S.mine.pending : (S.session.suggestPending || 0);
    if (limit > 0 && pending >= limit) return false;
    return true;
}

/** Предложить проект в сборку текущего профиля (наблюдатель). */
async function suggestProject(source, projectId, btn) {
    const result = await api("mods/suggest", {
        method: "POST",
        body: {
            profile: S.currentProfile,
            source,
            projectId,
            kind: S.search.type || "mod",
        },
    });
    if (result.ok) {
        toast(t("suggest.done"), "success");
        const d = result.data || {};
        if (d.limit > 0) toast(t("suggest.quota", { a: d.pending, b: d.limit }), "info");
        await runSearch(false); // перерисовать карточку с чипом «Предложено»
        await loadMine();
    } else {
        const err = result.error || {};
        const map = {
            suggest_disabled: () => t("suggest.error.disabled"),
            suggest_banned: () => t("suggest.error.banned"),
            suggest_limit: () => t("suggest.error.limit"),
            already_in_modpack: () => t("suggest.error.inModpack"),
            already_suggested: () => t("suggest.error.duplicate"),
        };
        toast(map[err.code] ? map[err.code]() : (err.message || t("common.error")), "error");
        if (btn) {
            btn.disabled = false;
            btn.innerHTML = icon("send") + "<span>" + esc(t("suggest.button")) + "</span>";
        }
    }
}

/** Свои предложения наблюдателя: список + счётчик лимита. */
async function loadMine() {
    if (!S.session || isAdmin()) return;
    const result = await api("mods/suggestions/mine");
    if (!result.ok) return;
    S.mine = result.data || { items: [], pending: 0, limit: 0, banned: false, enabled: true };
    renderMine();
}

function renderMine() {
    const box = $("mine-list");
    const count = $("mine-count");
    if (!box) return;
    const items = (S.mine && S.mine.items) || [];
    if (count) {
        const limit = (S.mine && S.mine.limit) || 0;
        count.textContent = limit > 0 ? `${items.length}/${limit}` : String(items.length);
    }
    box.innerHTML = "";
    if (!items.length) {
        box.innerHTML = `<div class="muted small">${esc(t("suggest.mineEmpty"))}</div>`;
        return;
    }
    items.forEach(item => {
        const div = document.createElement("div");
        div.className = "mine-item";
        const kindIcon = item.kind === "resourcepack" ? "image" : item.kind === "shader" ? "sparkles" : "package";
        div.innerHTML = `${icon(kindIcon)}<span class="mine-name" title="${esc(item.title || "")}">${esc(item.title || item.slug || item.projectId)}</span>` +
            `<span class="muted small">${esc(item.profileTitle || item.profileDir || "")}</span>`;
        box.appendChild(div);
    });
}

function updateSearchPager() {
    const s = S.search;
    const pages = Math.max(1, Math.ceil(s.total / s.limit));
    const page = Math.floor(s.offset / s.limit) + 1;
    $("search-page").textContent = pages > 1 ? t("search.page", { a: page, b: pages }) : "";
    $("btn-search-prev").disabled = page <= 1;
    $("btn-search-next").disabled = page >= pages;
}

function resetSearchFilters() {
    S.search.query = "";
    S.search.cats.clear();
    S.search.sort = "relevance";
    S.search.loader = "any";
    applyProfileFilters();
    syncSearchControls();
    renderSearchFilters();
    runSearch(true);
}

/* ================= Настройки (вкладка «Настройки») ================= */
/** Загрузить настройки модуля (кэш на сессию — обновляется при открытии вкладки). */
async function loadSettings(force) {
    if (S.settings && !force) {
        renderSettings();
        return;
    }
    const result = await api("settings");
    if (!result.ok) {
        toast(result.error ? result.error.message : t("settings.loadFailed"), "error");
        return;
    }
    S.settings = result.data;
    S.settings.priority = (result.data.updateSourcePriority || []).slice(); // рабочая копия порядка
    renderSettings();
}

function renderSettings() {
    if (!S.settings) return;
    renderPriorityList();
    $("set-autoRehash").checked = !!S.settings.autoRehash;
    $("set-verifyHash").checked = !!S.settings.verifyHash;
    $("set-allowWithoutHash").checked = !!S.settings.allowWithoutHash;
    $("set-autoResolveDeps").checked = !!S.settings.autoResolveDeps;
    $("set-syncProfileOptional").checked = !!S.settings.syncProfileOptional;
    $("set-defaultOptionalEnabled").checked = !!S.settings.defaultOptionalEnabled;
    $("set-defaultChannel").value = S.settings.defaultChannel || "release";
    $("set-cacheTtlMinutes").value = S.settings.cacheTtlMinutes || 10;
    $("set-maxConcurrentDownloads").value = S.settings.maxConcurrentDownloads || 4;
    $("set-httpTimeoutSeconds").value = S.settings.httpTimeoutSeconds || 30;
    $("set-defaultType").value = ["mod", "resourcepack", "shader"].includes(S.settings.defaultProjectType)
        ? S.settings.defaultProjectType : "mod";
    $("set-suggestEnabled").checked = !!S.settings.suggestEnabled;
    $("set-suggestLimit").value = S.settings.suggestMaxPerUser != null ? S.settings.suggestMaxPerUser : 5;
    $("set-ignoreMods").value = (S.settings.ignoreMods || []).join("\n");
    $("set-ignoreResourcepacks").value = (S.settings.ignoreResourcepacks || []).join("\n");
    $("set-ignoreShaders").value = (S.settings.ignoreShaders || []).join("\n");
    $("set-siteDomains").value = (S.settings.siteDomains || []).join("\n");
    S.suggestBansLocal = Array.isArray(S.settings.suggestBannedUsers) ? S.settings.suggestBannedUsers.slice() : [];
    renderSuggestBans();
    // уровень вывода в консоль + темы оформления (рабочие копии до «Сохранить»)
    const level = $("set-consoleLevel");
    if (level) level.value = ["off", "error", "warn", "all"].includes(S.settings.consoleLevel) ? S.settings.consoleLevel : "warn";
    S.themesDisabledLocal = Array.isArray(S.settings.themesDisabled) ? S.settings.themesDisabled.slice() : [];
    S.themesCustomLocal = Array.isArray(S.settings.themesCustom)
        ? S.settings.themesCustom.map(x => Object.assign({}, x)) : [];
    renderAppearance();
}

/** Строки textarea шаблонов игнорирования (.gitignore) в массив: без пустых
 *  строк и хвостовых пробелов; комментарии (#) сохраняются. */
function ignoreBoxValue(id) {
    const box = $(id);
    if (!box) return [];
    return box.value.split("\n")
        .map(line => line.replace(/\s+$/, ""))
        .filter(line => line.trim() !== "");
}

/** Список доменов сайта из textarea: по одному на строку или через запятую,
 *  без пустых значений и дублей (нормализация регистров/порта — на сервере). */
function domainListValue(id) {
    const box = $(id);
    if (!box) return [];
    const seen = new Set();
    const out = [];
    box.value.split(/[\n,]+/)
        .map(s => s.trim())
        .filter(s => s !== "")
        .forEach(d => {
            if (!seen.has(d)) {
                seen.add(d);
                out.push(d);
            }
        });
    return out;
}

/** Список логинов без права предлагать (рабочая копия до «Сохранить»). */
function renderSuggestBans() {
    const ul = $("suggest-bans");
    if (!ul) return;
    ul.innerHTML = "";
    if (!S.suggestBansLocal.length) {
        ul.innerHTML = `<li class="muted">—</li>`;
        return;
    }
    S.suggestBansLocal.forEach(login => {
        const li = document.createElement("li");
        li.innerHTML = `<span>${esc(login)}</span>`;
        const btn = document.createElement("button");
        btn.type = "button";
        btn.className = "icon-btn";
        btn.innerHTML = icon("x");
        btn.title = t("admin.remove");
        btn.setAttribute("aria-label", t("admin.remove"));
        btn.addEventListener("click", () => {
            S.suggestBansLocal = S.suggestBansLocal.filter(x => x !== login);
            renderSuggestBans();
        });
        li.appendChild(btn);
        ul.appendChild(li);
    });
}

function suggestBanAdd() {
    const input = $("suggest-ban-input");
    if (!input) return;
    const login = input.value.trim().toLowerCase();
    if (!login) return;
    if (login.length > 64) {
        toast(t("suggest.error.badLogin"), "warn");
        return;
    }
    if (!S.suggestBansLocal.includes(login)) {
        S.suggestBansLocal.push(login);
        renderSuggestBans();
    }
    input.value = "";
    input.focus();
}

/** Список источников в порядке приоритета: позиция, бейдж, доступность, ↑↓. */
function renderPriorityList() {
    const box = $("prio-list");
    box.innerHTML = "";
    const sources = (S.settings && S.settings.sources) || {};
    const priority = S.settings.priority || [];
    priority.forEach((sourceId, index) => {
        const row = document.createElement("div");
        row.className = "prio-row";
        const name = sourceId === "modrinth" ? "Modrinth" : "CurseForge";
        const available = sources[sourceId] !== false;
        row.innerHTML =
            `<span class="prio-pos">${index + 1}</span>` +
            `<span class="badge ${sourceId === "modrinth" ? "mr" : "cf"}">${name}</span>` +
            (available ? "" : `<span class="muted small">${esc(t("settings.sourceUnavailable"))}</span>`) +
            `<span class="prio-controls"></span>`;
        const controls = row.querySelector(".prio-controls");
        const up = document.createElement("button");
        up.type = "button";
        up.className = "btn small ghost";
        up.innerHTML = icon("chevron-u");
        up.title = t("settings.priorityUp");
        up.setAttribute("aria-label", t("settings.priorityUp"));
        up.disabled = index === 0;
        up.addEventListener("click", () => movePriority(index, -1));
        const down = document.createElement("button");
        down.type = "button";
        down.className = "btn small ghost";
        down.innerHTML = icon("chevron-d");
        down.title = t("settings.priorityDown");
        down.setAttribute("aria-label", t("settings.priorityDown"));
        down.disabled = index === priority.length - 1;
        down.addEventListener("click", () => movePriority(index, 1));
        controls.appendChild(up);
        controls.appendChild(down);
        box.appendChild(row);
    });
}

/** Сдвинуть источник в приоритете (локально, до «Сохранить»). */
function movePriority(index, delta) {
    const priority = S.settings.priority;
    const target = index + delta;
    if (target < 0 || target >= priority.length) return;
    const [item] = priority.splice(index, 1);
    priority.splice(target, 0, item);
    renderPriorityList();
}

async function saveModuleSettings(event) {
    if (!S.settings) return;
    const btn = event && event.currentTarget && event.currentTarget.id
        ? event.currentTarget : $("btn-settings-save");
    setBtnLoading(btn, "settings.save", "check", true);
    const result = await api("settings", {
        method: "POST",
        body: {
            updateSourcePriority: S.settings.priority,
            defaultChannel: $("set-defaultChannel").value,
            cacheTtlMinutes: parseInt($("set-cacheTtlMinutes").value, 10) || 10,
            maxConcurrentDownloads: parseInt($("set-maxConcurrentDownloads").value, 10) || 4,
            httpTimeoutSeconds: parseInt($("set-httpTimeoutSeconds").value, 10) || 30,
            autoRehash: $("set-autoRehash").checked,
            verifyHash: $("set-verifyHash").checked,
            allowWithoutHash: $("set-allowWithoutHash").checked,
            autoResolveDeps: $("set-autoResolveDeps").checked,
            syncProfileOptional: $("set-syncProfileOptional").checked,
            defaultOptionalEnabled: $("set-defaultOptionalEnabled").checked,
            defaultProjectType: $("set-defaultType").value,
            ignoreMods: ignoreBoxValue("set-ignoreMods"),
            ignoreResourcepacks: ignoreBoxValue("set-ignoreResourcepacks"),
            ignoreShaders: ignoreBoxValue("set-ignoreShaders"),
            siteDomains: domainListValue("set-siteDomains"),
            suggestEnabled: $("set-suggestEnabled").checked,
            suggestMaxPerUser: parseInt($("set-suggestLimit").value, 10) || 0,
            suggestBannedUsers: S.suggestBansLocal.slice(),
            // уровень вывода в консоль и темы оформления
            consoleLevel: $("set-consoleLevel") ? $("set-consoleLevel").value : undefined,
            themeDefault: $("set-themeDefault") ? $("set-themeDefault").value : undefined,
            themeMode: $("set-themeMode") ? $("set-themeMode").value : undefined,
            themesDisabled: S.themesDisabledLocal.slice(),
            themesCustom: S.themesCustomLocal.slice(),
        },
    });
    setBtnLoading(btn, "settings.save", "check", false);
    if (!result.ok) {
        toast(result.error ? result.error.message : t("common.error"), "error");
        return;
    }
    S.settings = result.data;
    S.settings.priority = (result.data.updateSourcePriority || []).slice();
    renderSettings();
    // конфигурация тем изменилась — применяем без перезагрузки страницы
    // (theme.js перечитается при следующем открытии сайта)
    applyThemesConfig({
        defaultTheme: result.data.themeDefault,
        defaultMode: result.data.themeMode,
        disabled: result.data.themesDisabled || [],
        custom: result.data.themesCustom || [],
    });
    toast(t("settings.saved"), "success");
}

async function addMod(source, projectId, title) {
    const result = await api("mods/add", {
        method: "POST",
        body: {
            profile: S.currentProfile,
            source,
            projectId,
            kind: S.search.type || "mod",
            optional: false,
            channel: "release",
        },
    });
    if (!result.ok) {
        toast(t("mods.addFailed") + ": " + (result.error ? result.error.message : ""), "error");
        return;
    }
    toast(t("mods.added", { name: title }), "success");
    const data = result.data || {};
    const suggestions = (data.missingDependencies || []);
    if (suggestions.length) {
        const names = suggestions.map(s => s.name || s.projectId).join(", ");
        if (confirm(t("mods.suggestDeps", { deps: names }))) {
            for (const dep of suggestions) {
                await addMod(dep.source, dep.projectId, dep.name);
            }
        }
    }
    const conflictSuggestions = (data.missingConflicts || []);
    if (conflictSuggestions.length) {
        const names = conflictSuggestions.map(s => s.name || s.projectId).join(", ");
        toast(t("mods.conflicts") + ": " + names, "warn");
    }
    await loadProfiles();
    await loadPack();
    closeModal();
}

/* ================= Настройки мода ================= */
function openSettings(mod) {
    // Связи (зависимости/конфликты) — только с ОПЦИОНАЛЬНЫМИ модами:
    // обязательные всегда включены, ссылка на них не работает.
    // Члены той же группы исключены — у группы один общий переключатель.
    const relCandidates = (S.pack.mods || []).filter(m =>
        m.id !== mod.id && m.optional && (!mod.optionalGroup || m.optionalGroup !== mod.optionalGroup));
    const relRows = relCandidates.map(m => {
        const isDep = (mod.dependencies || []).includes(m.id);
        const isConf = (mod.conflicts || []).includes(m.id);
        return `<div class="rel-row">
            <span class="rel-name" title="${esc(m.id)}">${esc(m.name || m.id)}</span>
            <span class="rel-controls">
                <label class="rel-check dep${isDep ? " on" : ""}"><input type="checkbox" data-rel="dep" data-id="${esc(m.id)}" ${isDep ? "checked" : ""}><span>${esc(t("mods.relDep"))}</span></label>
                <label class="rel-check conf${isConf ? " on" : ""}"><input type="checkbox" data-rel="conf" data-id="${esc(m.id)}" ${isConf ? "checked" : ""}><span>${esc(t("mods.relConflict"))}</span></label>
            </span>
        </div>`;
    }).join("");
    const relBlock = relCandidates.length
        ? `<div class="rel-list">${relRows}</div>`
        : `<div class="muted small rel-empty">${esc(t("mods.relEmpty"))}</div>`;
    // существующие группы — ВЫБОР из уже созданных (текстовый ввод остался
    // только для варианта «Новая группа…»); рядом с именем — число участников
    const groupCounts = new Map();
    (S.pack.mods || []).forEach(m => {
        if (m.optionalGroup) groupCounts.set(m.optionalGroup, (groupCounts.get(m.optionalGroup) || 0) + 1);
    });
    const locale = LANG === "ru" ? "ru" : "en";
    const groupNames = [...groupCounts.keys()].sort((a, b) => a.localeCompare(b, locale, { numeric: true }));
    const groupOptions = groupNames.map(g =>
        `<option value="${esc(g)}" ${g === mod.optionalGroup ? "selected" : ""}>${esc(g)} (${groupCounts.get(g)})</option>`).join("");
    const body = `
        <h2>${esc(mod.name || mod.id)}</h2>
        <div class="modal-body">
            <label class="modal-field check-field">
                <input type="checkbox" id="set-optional" ${mod.optional ? "checked" : ""}>
                <span>${t("mods.optionalToggle")}</span>
            </label>
            <div class="modal-field">
                <span class="field-label">${t("mods.groupLabel")}</span>
                <select id="set-group-select" class="input select">
                    <option value="" ${!mod.optionalGroup ? "selected" : ""}>${esc(t("mods.groupNone"))}</option>
                    ${groupOptions}
                    <option value="__new__">${esc(t("mods.groupNew"))}</option>
                </select>
                <input type="text" id="set-group-new" class="input hidden" maxlength="100"
                       placeholder="${esc(t("mods.groupPlaceholder"))}">
                <div class="muted small field-hint">${t("mods.groupHint")}</div>
            </div>
            <div class="modal-field">
                <span class="field-label">${t("mods.channel")}</span>
                <select id="set-channel" class="input select">
                    ${["release", "beta", "alpha"].map(c =>
                        `<option value="${c}" ${mod.channel === c ? "selected" : ""}>${c}</option>`).join("")}
                </select>
            </div>
            <div class="modal-field">
                <span class="field-label">${t("mods.note")}</span>
                <input type="text" id="set-note" class="input" maxlength="500" value="${esc(mod.note || "")}">
            </div>
            <div class="modal-field tight">
                <span class="field-label">${t("mods.relations")}</span>
                <div class="muted small field-hint">${t("mods.relOnlyOptional")}</div>
            </div>
            ${relBlock}
        </div>`;
    const modal = openModal(body, [
        { label: t("mods.save"), className: "btn primary", onclick: () => saveSettings(mod) },
        { label: t("mods.cancel"), className: "btn", onclick: closeModal },
    ]);
    // Выбор «Новая группа…» показывает текстовое поле имени
    const groupSelect = modal.querySelector("#set-group-select");
    const groupNew = modal.querySelector("#set-group-new");
    if (groupSelect && groupNew) {
        groupSelect.addEventListener("change", () => {
            const isNew = groupSelect.value === "__new__";
            groupNew.classList.toggle("hidden", !isNew);
            if (isNew) setTimeout(() => groupNew.focus(), 40);
        });
    }
    // Взаимоисключающие галочки: у одного мода связь либо «Зависимость»,
    // либо «Конфликт», либо никакой (обе сняты = связь удалена)
    const relList = modal.querySelector(".rel-list");
    if (relList) {
        relList.addEventListener("change", e => {
            const input = e.target;
            if (!input.matches || !input.matches("input[data-rel]")) return;
            input.closest(".rel-check").classList.toggle("on", input.checked);
            if (!input.checked) return;
            const otherKind = input.dataset.rel === "dep" ? "conf" : "dep";
            const other = relList.querySelector(
                `input[data-rel="${otherKind}"][data-id="${CSS.escape(input.dataset.id)}"]`);
            if (other) {
                other.checked = false;
                other.closest(".rel-check").classList.remove("on");
            }
        });
    }
    return modal;
}

async function saveSettings(mod) {
    const relList = $("modal-backdrop");
    const dependencies = Array.from(
        relList.querySelectorAll(".rel-list input[data-rel='dep']:checked")).map(i => i.dataset.id);
    const conflicts = Array.from(
        relList.querySelectorAll(".rel-list input[data-rel='conf']:checked")).map(i => i.dataset.id);
    // Группа: из селекта; «Новая группа…» — из текстового поля, пустое имя
    // не сохраняем (иначе мод молча вылетел бы из текущей группы)
    let optionalGroup = "";
    const groupSelect = $("set-group-select");
    const groupNew = $("set-group-new");
    if (groupSelect) {
        if (groupSelect.value === "__new__") {
            optionalGroup = (groupNew ? groupNew.value : "").trim();
            if (!optionalGroup) {
                toast(t("mods.groupNewEmpty"), "warn");
                if (groupNew) groupNew.focus();
                return;
            }
        } else {
            optionalGroup = groupSelect.value.trim();
        }
    }
    const result = await api("mods/settings", {
        method: "POST",
        body: {
            profile: S.currentProfile,
            id: mod.id,
            optional: $("set-optional").checked,
            optionalGroup,
            channel: $("set-channel").value,
            note: $("set-note").value,
            dependencies,
            conflicts,
        },
    });
    if (result.ok) {
        closeModal();
        await loadPack();
    } else {
        toast(result.error ? result.error.message : t("common.error"), "error");
    }
}

/* ================= Модалки ================= */
function openModal(bodyHtml, buttons) {
    closeModal();
    const backdrop = document.createElement("div");
    backdrop.className = "modal-backdrop";
    backdrop.id = "modal-backdrop";
    backdrop.setAttribute("role", "dialog");
    backdrop.setAttribute("aria-modal", "true");
    backdrop.innerHTML = `<div class="modal">${bodyHtml}<div class="modal-actions"></div></div>`;
    const actions = backdrop.querySelector(".modal-actions");
    if (buttons && buttons.length) {
        buttons.forEach(b => {
            const btn = document.createElement("button");
            btn.type = "button";
            if (b.id) btn.id = b.id;
            btn.className = b.className || "btn";
            btn.textContent = b.label;
            btn.addEventListener("click", b.onclick);
            actions.appendChild(btn);
        });
    } else {
        const closeBtn = document.createElement("button");
        closeBtn.type = "button";
        closeBtn.className = "btn";
        closeBtn.textContent = t("mods.close");
        closeBtn.addEventListener("click", closeModal);
        actions.appendChild(closeBtn);
    }
    backdrop.addEventListener("mousedown", (e) => {
        if (e.target === backdrop) closeModal();
    });
    // фокус-трап: Tab не выходит за пределы модалки
    backdrop.addEventListener("keydown", (e) => {
        if (e.key !== "Tab") return;
        const focusables = backdrop.querySelectorAll("button:not(:disabled), input, select, textarea, [href]");
        if (!focusables.length) return;
        const first = focusables[0];
        const last = focusables[focusables.length - 1];
        if (e.shiftKey && document.activeElement === first) {
            e.preventDefault();
            last.focus();
        } else if (!e.shiftKey && document.activeElement === last) {
            e.preventDefault();
            first.focus();
        }
    });
    $("modal-root").appendChild(backdrop);
    // автофокус на первом поле
    const firstField = backdrop.querySelector("input, select");
    if (firstField) setTimeout(() => firstField.focus(), 80);
    return backdrop;
}

function closeModal() {
    const existing = $("modal-backdrop");
    if (existing) existing.remove();
    stopThemePreview();
}

/* Esc закрывает модалку */
document.addEventListener("keydown", (e) => {
    if (e.key === "Escape") closeModal();
});

/* ================= Админ-панель ================= */
async function loadBans() {
    const result = await api("bans");
    if (!result.ok) return;
    const data = result.data || {};
    const bansBody = $("table-bans").querySelector("tbody");
    bansBody.innerHTML = "";
    const bans = Object.entries(data.ipBans || {});
    if (!bans.length) {
        bansBody.innerHTML = `<tr><td colspan="5" class="muted">${t("admin.noBans")}</td></tr>`;
    } else {
        for (const [ip, info] of bans) {
            const tr = document.createElement("tr");
            tr.innerHTML = `<td>${esc(ip)}</td>
                <td>${fmtTime(info.until)}</td>
                <td>L${info.level}</td>
                <td>${info.totalFails}</td>
                <td></td>`;
            const btn = document.createElement("button");
            btn.type = "button";
            btn.className = "btn small danger";
            btn.innerHTML = icon("x") + "<span>" + esc(t("admin.unban")) + "</span>";
            btn.addEventListener("click", () => unban(ip, null));
            tr.lastElementChild.appendChild(btn);
            bansBody.appendChild(tr);
        }
    }
    const locksBody = $("table-locks").querySelector("tbody");
    locksBody.innerHTML = "";
    const locks = Object.entries(data.accountLocks || {});
    if (!locks.length) {
        locksBody.innerHTML = `<tr><td colspan="4" class="muted">${t("admin.noBans")}</td></tr>`;
    } else {
        for (const [login, info] of locks) {
            const tr = document.createElement("tr");
            tr.innerHTML = `<td>${esc(login)}</td>
                <td>${fmtTime(info.until)}</td>
                <td>L${info.level}</td>
                <td></td>`;
            const btn = document.createElement("button");
            btn.type = "button";
            btn.className = "btn small danger";
            btn.innerHTML = icon("x") + "<span>" + esc(t("admin.unlock")) + "</span>";
            btn.addEventListener("click", () => unban(null, login));
            tr.lastElementChild.appendChild(btn);
            locksBody.appendChild(tr);
        }
    }
}

async function unban(ip, login) {
    const body = {};
    if (ip) body.ip = ip;
    if (login) body.login = login;
    await api("bans/unban", { method: "POST", body });
    await loadBans();
}

async function loadIpFilter() {
    const result = await api("ipfilter");
    if (!result.ok) return;
    renderIpList("list-whitelist", result.data.whitelist || [], "white");
    renderIpList("list-blacklist", result.data.blacklist || [], "black");
}

function renderIpList(listId, entries, kind) {
    const ul = $(listId);
    ul.innerHTML = "";
    if (!entries.length) {
        ul.innerHTML = `<li class="muted">—</li>`;
        return;
    }
    entries.forEach(entry => {
        const li = document.createElement("li");
        li.innerHTML = `<span>${esc(entry)}</span>`;
        const btn = document.createElement("button");
        btn.type = "button";
        btn.className = "icon-btn";
        btn.innerHTML = icon("x");
        btn.title = t("admin.remove");
        btn.setAttribute("aria-label", t("admin.remove"));
        btn.addEventListener("click", async () => {
            await api("ipfilter", { method: "POST", body: { action: "remove", list: kind, ip: entry } });
            await loadIpFilter();
        });
        li.appendChild(btn);
        ul.appendChild(li);
    });
}

async function ipFilterAdd(kind, value) {
    const ip = value.trim();
    if (!ip) return;
    const result = await api("ipfilter", { method: "POST", body: { action: "add", list: kind, ip } });
    if (result.ok && result.data.success) {
        if (kind === "white") $("wl-input").value = "";
        else $("bl-input").value = "";
        await loadIpFilter();
    } else {
        toast(result.error ? result.error.message : t("common.error"), "error");
    }
}

async function loadAudit() {
    const result = await api(`audit?offset=${S.auditOffset}&limit=60`);
    if (!result.ok) return;
    const pre = $("audit-log");
    pre.textContent = (result.data.lines || []).join("\n") || "—";
}

async function loadUsers() {
    const result = await api("users");
    if (!result.ok) return;
    const tbody = $("table-users").querySelector("tbody");
    tbody.innerHTML = "";
    (result.data || []).forEach(account => {
        const tr = document.createElement("tr");
        tr.innerHTML = `<td>${esc(account.login)}</td>
            <td>${esc(account.role)}</td>
            <td>${account.totp ? t("admin.yes") : t("admin.no")}</td>
            <td>${account.enabled ? t("admin.on") : t("admin.off")}</td>
            <td style="min-width:230px">
                <div style="display:flex;gap:6px">
                    <input type="text" class="input" maxlength="128" placeholder="••••••" style="min-width:120px" aria-label="${esc(t("admin.newPassword"))}">
                    <span></span>
                </div>
            </td>`;
        const setBtn = document.createElement("button");
        setBtn.type = "button";
        setBtn.className = "btn small";
        setBtn.textContent = t("admin.setPassword");
        tr.querySelector("div").appendChild(setBtn);
        const input = tr.querySelector("input");
        setBtn.addEventListener("click", async () => {
            const newPassword = input.value;
            if (newPassword.length < 6) {
                toast(t("common.error") + ": ≥6", "error");
                return;
            }
            const setResult = await api("users/password", {
                method: "POST",
                body: { login: account.login, newPassword },
            });
            if (setResult.ok) {
                toast(t("common.done"), "success");
                input.value = "";
                if (account.login === S.session.login) {
                    toast(t("topbar.logout") + "?", "warn");
                }
            } else {
                toast(setResult.error ? setResult.error.message : t("common.error"), "error");
            }
        });
        tbody.appendChild(tr);
    });
}

/* ================= Роли админов (только суперадмин) ================= */
/** Таблица известных логинов и их эффективных ролей.
 *  Эффективная роль: суперадмин > «наблюдатель» (оверрайд) > базовая роль источника. */
async function loadAdminRoles() {
    if (!isSuperAdmin()) return;
    const result = await api("admins");
    if (!result.ok) {
        toast(result.error ? result.error.message : t("common.error"), "error");
        return;
    }
    const tbody = $("table-admin-roles").querySelector("tbody");
    tbody.innerHTML = "";
    (result.data.admins || []).forEach(admin => {
        const tr = document.createElement("tr");
        const effective = admin.effectiveRole || admin.baseRole || "viewer";
        tr.innerHTML = `<td>${esc(admin.login)}</td>
            <td>${esc(admin.source || "")}${admin.enabled ? "" : " · " + esc(t("admin.off"))}</td>
            <td><span class="role-chip${effective === "viewer" ? " viewer" : (effective === "superadmin" ? " super" : "")}">${esc(effective)}</span></td>
            <td>${admin.forcedViewer ? esc(t("admin.roles.forced")) : "—"}</td>
            <td></td>`;
        const cell = tr.children[4];
        if (!admin.canOverride) {
            const note = document.createElement("span");
            note.className = "muted small";
            note.textContent = admin.superAdmin ? t("admin.roles.superNote") : "";
            cell.appendChild(note);
        } else {
            const btn = document.createElement("button");
            btn.type = "button";
            btn.className = "btn small" + (admin.forcedViewer ? "" : " danger");
            btn.textContent = admin.forcedViewer ? t("admin.roles.allowAdmin") : t("admin.roles.makeViewer");
            btn.addEventListener("click", async () => {
                const makeViewer = !admin.forcedViewer;
                const res = await api("admins/override", {
                    method: "POST",
                    body: { login: admin.login, viewer: String(makeViewer) },
                });
                if (res.ok) {
                    toast(t(makeViewer ? "admin.roles.viewerSet" : "admin.roles.viewerCleared"), "success");
                    await loadAdminRoles();
                } else {
                    toast(res.error ? res.error.message : t("common.error"), "error");
                }
            });
            cell.appendChild(btn);
        }
        tbody.appendChild(tr);
    });
    // Полный список помеченных логинов (в т.ч. те, кто сейчас не в сети)
    const viewers = result.data.viewers || [];
    const extra = $("roles-viewers-extra");
    if (extra) {
        extra.textContent = viewers.length
            ? t("admin.roles.viewersList", { list: viewers.join(", ") })
            : "";
    }
}

/** Принудительно сделать наблюдателем произвольный логин — например, админа
 *  из БД лаунчера, которого нет в таблице (он появится здесь после первого входа). */
async function addForcedViewer() {
    const input = $("viewer-add-input");
    if (!input) return;
    const login = (input.value || "").trim();
    if (!login) return;
    const res = await api("admins/override", {
        method: "POST",
        body: { login, viewer: "true" },
    });
    if (res.ok) {
        toast(t("admin.roles.viewerSet"), "success");
        input.value = "";
        await loadAdminRoles();
    } else {
        toast(res.error ? res.error.message : t("common.error"), "error");
    }
}

function switchSecurityTab(name) {
    ["bans", "ipfilter", "audit", "users", "roles"].forEach(n => {
        const section = $("admin-" + n);
        const tab = $("subtab-" + n);
        if (section) section.classList.toggle("hidden", n !== name);
        if (tab) tab.classList.toggle("active", n === name);
    });
    if (name === "bans") loadBans();
    if (name === "ipfilter") loadIpFilter();
    if (name === "audit") loadAudit();
    if (name === "users") loadUsers();
    if (name === "roles") loadAdminRoles();
}

/** Поднавигация «Настройки»: Обновления / Безопасность. */
function switchSettingsTab(name) {
    ["updates", "appearance", "security"].forEach(n => {
        $("settings-" + n).classList.toggle("hidden", n !== name);
        $("subtab-settings-" + n).classList.toggle("active", n === name);
    });
    if (name === "updates") loadSettings(false);
    if (name === "appearance") loadSettings(false);
    if (name === "security") switchSecurityTab("bans");
}

/* ================= Вкладка «Предложения» (админ) ================= */
/** Загрузить очередь предложений с фильтрами (поиск по названию/пользователю, тип). */
async function loadSuggestions() {
    if (!isAdmin()) return;
    const q = ($("suggest-search") && $("suggest-search").value || "").trim();
    const kind = $("suggest-kind") ? $("suggest-kind").value : "";
    S.suggestQueue.q = q;
    S.suggestQueue.kind = kind;
    const params = new URLSearchParams();
    if (q) params.set("q", q);
    if (kind) params.set("kind", kind);
    const result = await api("mods/suggestions" + (params.toString() ? "?" + params.toString() : ""));
    if (!result.ok) {
        toast(result.error ? result.error.message : t("common.error"), "error");
        return;
    }
    S.suggestQueue.items = result.data.items || [];
    S.suggestQueue.pendingTotal = result.data.pendingTotal || 0;
    S.suggestQueue.loaded = true;
    renderSuggestTable();
    const badge = $("cnt-suggest");
    if (badge) badge.textContent = S.suggestQueue.pendingTotal ? String(S.suggestQueue.pendingTotal) : "";
}

let suggestSearchTimer = null;
function onSuggestSearchInput() {
    if (suggestSearchTimer) clearTimeout(suggestSearchTimer);
    suggestSearchTimer = setTimeout(loadSuggestions, 250);
}

function renderSuggestTable() {
    const tbody = $("table-suggest");
    if (!tbody) return;
    const body = tbody.querySelector("tbody");
    body.innerHTML = "";
    const items = S.suggestQueue.items || [];
    if (!items.length) {
        body.innerHTML = `<tr><td colspan="6" class="muted">${esc(t("suggest.empty"))}</td></tr>`;
        return;
    }
    items.forEach(item => {
        const tr = document.createElement("tr");
        const kindIcon = item.kind === "resourcepack" ? "image" : item.kind === "shader" ? "sparkles" : "package";
        const iconHtml = item.iconUrl
            ? `<img class="suggest-icon" src="${esc(iconSrc(item.iconUrl))}" alt="" loading="lazy">`
            : `<div class="mod-icon-fallback small-fallback">${esc((item.title || "?")[0].toUpperCase())}</div>`;
        tr.innerHTML = `<td>${esc(item.login)}</td>
            <td><span class="badge kind-${esc(item.kind || "mod")}">${icon(kindIcon)}${esc(t("kind." + (item.kind || "mod")))}</span></td>
            <td><div class="suggest-project">${iconHtml}<div><b>${esc(item.title || item.slug || item.projectId)}</b>
                <span class="muted small">${esc(item.source || "")}${item.slug ? " · " + esc(item.slug) : ""}</span></div></div></td>
            <td>${esc(item.profileTitle || item.profileDir || "")}</td>
            <td>${fmtTime(item.createdAt)}</td>
            <td></td>`;
        const actions = document.createElement("div");
        actions.className = "suggest-actions";
        const approveBtn = document.createElement("button");
        approveBtn.type = "button";
        approveBtn.className = "btn small primary";
        approveBtn.innerHTML = icon("download") + "<span>" + esc(t("suggest.approve")) + "</span>";
        approveBtn.addEventListener("click", () => suggestDecision(item, "approve", approveBtn));
        const rejectBtn = document.createElement("button");
        rejectBtn.type = "button";
        rejectBtn.className = "btn small danger";
        rejectBtn.innerHTML = icon("x") + "<span>" + esc(t("suggest.reject")) + "</span>";
        rejectBtn.addEventListener("click", () => suggestDecision(item, "reject", rejectBtn));
        actions.appendChild(approveBtn);
        actions.appendChild(rejectBtn);
        tr.lastElementChild.appendChild(actions);
        body.appendChild(tr);
    });
}

/** Решение администратора: скачать в сборку или отклонить. */
async function suggestDecision(item, action, btn) {
    if (btn) {
        btn.disabled = true;
        btn.innerHTML = '<span class="spin" aria-hidden="true"></span>';
    }
    const result = await api("mods/suggestions/decision", {
        method: "POST",
        body: { id: item.id, action },
    });
    if (!result.ok) {
        toast(result.error ? result.error.message : t("common.error"), "error");
        await loadSuggestions(); // карточка могла устареть — перерисуем
        return;
    }
    if (action === "approve") {
        const d = result.data || {};
        if (d.downloadError) {
            // Запись добавлена, но файл не скачался — раньше это глоталось
            // молча под зелёный тост «скачано». Теперь показываем причину:
            // нет совместимой версии / канал / HTTP-сбой и т.п.
            toast(t("suggest.downloadFailed", {
                name: item.title || item.projectId,
                reason: downloadErrorReason(d.downloadError),
            }), "warn");
        } else if (d.alreadyInModpack) {
            toast(t("suggest.approvedAlready", { name: item.title || item.projectId }), "success");
        } else {
            toast(t("suggest.approved", { name: item.title || item.projectId }), "success");
        }
        await loadProfiles();
        if (item.profileDir === S.currentProfile) await loadPack();
    } else {
        toast(t("suggest.rejected"), "success");
    }
    await loadSuggestions();
}

/** Человекочитаемая причина ошибки скачивания (для тоста одобрения предложения). */
function downloadErrorReason(raw) {
    const text = String(raw || "");
    if (/no compatible version/i.test(text)) return t("suggest.reason.noCompatibleVersion");
    if (/Download failed|HTTP \d+/i.test(text)) return t("suggest.reason.http") + " (" + text + ")";
    if (/hash/i.test(text)) return t("suggest.reason.hash");
    return text;
}

/* ================= Навигация ================= */
function switchMainTab(name) {
    const isAdmin = S.session && (S.session.role === "admin" || S.session.role === "superadmin");
    if ((name === "settings" || name === "suggest" || name === "backups") && !isAdmin) return;
    ["mods", "search", "suggest", "settings", "backups"].forEach(n => {
        const tab = $("tab-" + n);
        if (tab) {
            tab.classList.toggle("active", name === n);
            tab.setAttribute("aria-selected", name === n ? "true" : "false");
        }
        const panel = $("panel-" + n);
        if (panel) panel.classList.toggle("hidden", name !== n);
    });
    if (name === "settings") switchSettingsTab("updates");
    if (name === "search") onSearchTabOpen();
    if (name === "suggest") loadSuggestions();
    if (name === "backups") loadBackups();
}

/* ================= Темы оформления (1.1.0) ================= */
/* Встроенные темы: у каждой светлый и тёмный вариант (палитры — в style.css,
   атрибут data-mode на <html>). light/dark — фоны вариантов (светч и меты
   браузера), accent — «фирменный» цвет свотча. */
const BUILTIN_THEMES = [
    { id: "glass",    key: "theme.glass",    light: "#E9F0F9", dark: "#0F172A", accent: "#22C55E" },
    { id: "comic",    key: "theme.comic",    light: "#FBEFD3", dark: "#211A10", accent: "#E63946" },
    { id: "simple",   key: "theme.simple",   light: "#F5F6F8", dark: "#101318", accent: "#2563EB" },
    { id: "neon",     key: "theme.neon",     light: "#F2FDFF", dark: "#05070E", accent: "#00F0FF" },
    { id: "pixel",    key: "theme.pixel",    light: "#E7E9DC", dark: "#23252B", accent: "#5BC236" },
    { id: "terminal", key: "theme.terminal", light: "#F3F7EE", dark: "#020A04", accent: "#33FF66" },
];
let THEME = "glass";
let MODE = "dark";
let themeMenuEl = null;
/** Временная тема во время предпросмотра в форме «Добавить тему». */
let THEME_PREVIEW = null;

/** Конфигурация тем с сервера (theme.js): defaultTheme/defaultMode/disabled/custom. */
function themesConfig() {
    return window.MODS_THEMES
        || { defaultTheme: "glass", defaultMode: "dark", disabled: [], custom: [] };
}

/** Обновить конфигурацию тем после сохранения настроек (без перезагрузки
 *  страницы). Текущая тема могла стать недоступной — переходим к доступной. */
function applyThemesConfig(cfg) {
    if (cfg && typeof cfg === "object") window.MODS_THEMES = cfg;
    ensureCustomThemesCss();
    const sel = detectTheme();
    if (sel.theme !== THEME || sel.mode !== MODE) {
        applyTheme(sel.theme, sel.mode);
    } else {
        updateThemeLabel();
        if (themeMenuEl && themeMenuEl.classList.contains("open")) renderThemeMenu();
    }
}

/** Кастомные темы сервера в виде внутренних описателей (id с префиксом c-). */
function customThemes() {
    const list = themesConfig().custom || [];
    return list.filter(x => x && x.id).map(x => ({
        id: "c-" + x.id,
        custom: true,
        name: x.name || x.id,
        light: (x.light && x.light.bg) || "#F7F7F5",
        dark: (x.dark && x.dark.bg) || "#101418",
        accent: (x.light && x.light.accent) || "#2563EB",
        font: x.font || "sans",
        radius: x.radius || "soft",
        lightColors: x.light || {},
        darkColors: x.dark || {},
    }));
}

/** Доступные темы: встроенные (минус выключенные админом) + кастомные. */
function availableThemes() {
    const disabled = themesConfig().disabled || [];
    return BUILTIN_THEMES.filter(x => !disabled.includes(x.id)).concat(customThemes());
}

function findTheme(id) {
    if (THEME_PREVIEW && id === "c-preview") return THEME_PREVIEW;
    return availableThemes().find(x => x.id === id) || null;
}

/** Свотч темы: светлый фон / акцент / тёмный фон. */
function themeSwatch(theme) {
    return "linear-gradient(135deg, " + theme.light + " 0 34%, "
        + theme.accent + " 34% 66%, " + theme.dark + " 66% 100%)";
}

function detectTheme() {
    const cfg = themesConfig();
    const avail = availableThemes();
    let theme = null;
    let mode = null;
    try {
        const q = new URLSearchParams(location.search);
        const qp = q.get("theme");
        if (avail.some(x => x.id === qp)) {
            theme = qp;
            const qm = q.get("mode");
            if (qm === "light" || qm === "dark") mode = qm;
        }
        if (!theme) {
            const saved = localStorage.getItem("mods_theme");
            if (avail.some(x => x.id === saved)) theme = saved;
        }
        if (!mode) {
            const sm = localStorage.getItem("mods_mode");
            if (sm === "light" || sm === "dark") mode = sm;
        }
    } catch (e) { /* нет localStorage — значения по умолчанию */ }
    if (!theme) {
        theme = avail.some(x => x.id === cfg.defaultTheme) ? cfg.defaultTheme
            : (avail[0] ? avail[0].id : "glass");
    }
    if (!mode) mode = cfg.defaultMode === "light" ? "light" : "dark";
    return { theme: theme, mode: mode };
}

/** Применить тему и вариант: data-theme/data-mode на <html>, меты браузера,
 *  localStorage (кроме предпросмотра), подпись кнопки. */
function applyTheme(id, mode) {
    const theme = findTheme(id) || availableThemes()[0];
    if (!theme) return THEME;
    THEME = theme.id;
    if (mode === "light" || mode === "dark") MODE = mode;
    if (theme.custom) ensureCustomThemesCss();
    document.documentElement.setAttribute("data-theme", THEME);
    document.documentElement.setAttribute("data-mode", MODE);
    if (THEME !== "c-preview") {
        try {
            localStorage.setItem("mods_theme", THEME);
            localStorage.setItem("mods_mode", MODE);
        } catch (e) { /* приватный режим */ }
    }
    const meta = document.querySelector('meta[name="theme-color"]');
    if (meta) meta.setAttribute("content", MODE === "light" ? theme.light : theme.dark);
    const scheme = document.querySelector('meta[name="color-scheme"]');
    if (scheme) scheme.setAttribute("content", MODE);
    updateThemeLabel();
    if (themeMenuEl) renderThemeMenu();
    return THEME;
}

function updateThemeLabel() {
    const theme = findTheme(THEME) || BUILTIN_THEMES[0];
    const label = $("theme-label");
    if (label) label.textContent = theme.custom ? theme.name : t(theme.key);
    ["btn-theme", "btn-theme-login"].forEach(id => {
        const b = $(id);
        if (b) b.title = t("theme.hint");
    });
}

function renderThemeMenu() {
    if (!themeMenuEl) return;
    themeMenuEl.innerHTML = "";
    availableThemes().forEach(theme => {
        const item = document.createElement("button");
        item.type = "button";
        item.className = "theme-item";
        item.setAttribute("role", "menuitemradio");
        item.setAttribute("aria-checked", theme.id === THEME ? "true" : "false");
        item.innerHTML = '<span class="theme-swatch" style="background:' + themeSwatch(theme) + '"></span>'
            + "<span>" + esc(theme.custom ? theme.name : t(theme.key)) + "</span>" + icon("check");
        item.addEventListener("click", () => {
            applyTheme(theme.id);
            closeThemeMenu();
        });
        themeMenuEl.appendChild(item);
    });
    // переключатель светлый/тёмный вариант текущей темы
    const row = document.createElement("div");
    row.className = "theme-mode-row";
    [["light", "sun", "theme.light"], ["dark", "moon", "theme.dark"]].forEach(([mode, ic, key]) => {
        const b = document.createElement("button");
        b.type = "button";
        b.className = "theme-mode-btn";
        b.setAttribute("role", "menuitemradio");
        b.setAttribute("aria-checked", MODE === mode ? "true" : "false");
        b.innerHTML = icon(ic) + "<span>" + esc(t(key)) + "</span>";
        b.addEventListener("click", () => applyTheme(THEME, mode));
        row.appendChild(b);
    });
    themeMenuEl.appendChild(row);
    // админу — быстрый переход к управлению темами
    if (isAdmin()) {
        const manage = document.createElement("div");
        manage.className = "theme-manage";
        const b = document.createElement("button");
        b.type = "button";
        b.innerHTML = icon("settings") + "<span>" + esc(t("theme.configure")) + "</span>";
        b.addEventListener("click", () => {
            closeThemeMenu();
            switchMainTab("settings");
            switchSettingsTab("appearance");
        });
        manage.appendChild(b);
        themeMenuEl.appendChild(manage);
    }
}

function openThemeMenu(anchor) {
    if (!themeMenuEl) {
        themeMenuEl = document.createElement("div");
        themeMenuEl.id = "theme-menu";
        themeMenuEl.className = "theme-menu";
        themeMenuEl.setAttribute("role", "menu");
        document.body.appendChild(themeMenuEl);
    }
    themeMenuEl.setAttribute("aria-label", t("theme.hint"));
    renderThemeMenu();
    themeMenuEl.classList.add("open");
    const rect = anchor.getBoundingClientRect();
    const w = themeMenuEl.offsetWidth;
    const left = Math.min(Math.max(8, rect.right - w), window.innerWidth - w - 8);
    themeMenuEl.style.left = left + "px";
    const top = Math.min(rect.bottom + 6, window.innerHeight - themeMenuEl.offsetHeight - 8);
    themeMenuEl.style.top = Math.max(8, top) + "px";
}

function closeThemeMenu() {
    if (themeMenuEl) themeMenuEl.classList.remove("open");
}

function toggleThemeMenu(anchor) {
    if (themeMenuEl && themeMenuEl.classList.contains("open")) closeThemeMenu();
    else openThemeMenu(anchor);
}

/* ---------- Кастомные темы: генерация CSS из базовых цветов ---------- */

/** #rgb/#rrggbb -> [r, g, b]; мусор -> [0,0,0]. */
function hexToRgb(hex) {
    let h = String(hex || "").replace("#", "").trim();
    if (h.length === 3) h = h.split("").map(c => c + c).join("");
    if (!/^[0-9a-fA-F]{6}$/.test(h)) return [0, 0, 0];
    return [parseInt(h.slice(0, 2), 16), parseInt(h.slice(2, 4), 16), parseInt(h.slice(4, 6), 16)];
}

function clamp255(v) { return Math.max(0, Math.min(255, Math.round(v))); }

/** Сместить цвет к чёрному (amount < 0) или белому (amount > 0). */
function shadeColor(hex, amount) {
    const [r, g, b] = hexToRgb(hex);
    if (amount >= 0) {
        return "#" + [r, g, b].map(v => clamp255(v + (255 - v) * amount).toString(16).padStart(2, "0")).join("");
    }
    return "#" + [r, g, b].map(v => clamp255(v * (1 + amount)).toString(16).padStart(2, "0")).join("");
}

/** Цвет с прозрачностью -> rgba(...). */
function colorWithAlpha(hex, a) {
    const [r, g, b] = hexToRgb(hex);
    return "rgba(" + r + ", " + g + ", " + b + ", " + a + ")";
}

/** Смесь двух цветов (t — доля второго). */
function mixColors(hexA, hexB, t) {
    const a = hexToRgb(hexA);
    const b = hexToRgb(hexB);
    return "#" + [0, 1, 2].map(i => clamp255(a[i] + (b[i] - a[i]) * t).toString(16).padStart(2, "0")).join("");
}

/** Контрастный текст на фоне (тёмный или белый) — для --contrast-on-accent. */
function contrastOn(hex) {
    const [r, g, b] = hexToRgb(hex);
    const luminance = (0.299 * r + 0.587 * g + 0.114 * b) / 255;
    return luminance > 0.55 ? "#10131A" : "#FFFFFF";
}

/** Полный набор токенов темы из 7 базовых цветов (производные тона считаем
 *  сами — админ задаёт только главное). dark=true — тёмный вариант. */
function customThemeTokens(c, dark) {
    const d = dark;
    const accentBright = d ? shadeColor(c.accent, 0.18) : shadeColor(c.accent, -0.12);
    return {
        "--bg": c.bg,
        "--bg-deep": shadeColor(c.bg, d ? -0.15 : -0.06),
        "--decor-hi": shadeColor(c.bg, 0.05),
        "--decor-lo": shadeColor(c.bg, d ? -0.08 : -0.04),
        "--card-solid": c.card,
        "--card": c.card,
        "--card-hover": shadeColor(c.card, d ? 0.07 : -0.04),
        "--card-tint": c.card,
        "--sheen": "none",
        "--glass-strong": colorWithAlpha(c.card, 0.95),
        "--btn-bg": colorWithAlpha(c.text, 0.06),
        "--hover-tint": colorWithAlpha(c.text, 0.08),
        "--input-bg": d ? shadeColor(c.bg, -0.12) : mixColors(c.bg, "#FFFFFF", 0.65),
        "--border": c.border,
        "--border-strong": shadeColor(c.border, d ? 0.12 : -0.18),
        "--text": c.text,
        "--text-muted": c.textMuted,
        "--text-strong": shadeColor(c.text, d ? 0.1 : -0.12),
        "--text-meta": mixColors(c.text, c.textMuted, 0.5),
        "--accent": c.accent,
        "--accent-bright": accentBright,
        "--accent-2": c.accent2,
        "--accent-2-soft": d ? shadeColor(c.accent2, 0.2) : shadeColor(c.accent2, -0.12),
        "--info": d ? shadeColor(c.accent2, 0.15) : shadeColor(c.accent2, -0.1),
        "--warn": d ? "#FFC400" : "#B45309",
        "--warn-soft": d ? "#FFD54D" : "#92400E",
        "--danger": d ? "#FF6B6B" : "#CB3440",
        "--danger-solid": d ? "#FF4444" : "#DC2626",
        "--accent-grad": "linear-gradient(135deg, " + accentBright + ", " + c.accent + ")",
        "--contrast-on-accent": contrastOn(c.accent),
        "--sel-bg": colorWithAlpha(c.accent, 0.3),
        "--sel-text": c.text,
        "--blob-a": c.accent,
        "--blob-b": c.accent2,
        "--blob-c": c.accent,
        "--knob": c.textMuted,
        "--audit-bg": colorWithAlpha(c.card, 0.92),
        "--backdrop": d ? "rgba(0, 0, 0, 0.55)" : colorWithAlpha(c.text, 0.4),
        "--scroll-thumb": colorWithAlpha(c.text, 0.18),
        "--scroll-thumb-hover": colorWithAlpha(c.text, 0.34),
        "--shadow-card": d ? "0 8px 28px rgba(0, 0, 0, 0.45)" : "0 8px 28px " + colorWithAlpha(c.text, 0.1),
        "--shadow-pop": d ? "0 24px 60px rgba(0, 0, 0, 0.55)" : "0 24px 60px " + colorWithAlpha(c.text, 0.16),
        "--shadow-accent": "0 6px 20px " + colorWithAlpha(c.accent, 0.3),
        "--login-bg": c.card,
        "--login-border": c.border,
        "--login-shadow": "0 24px 60px " + colorWithAlpha(c.text, d ? 0.5 : 0.18),
        "--focus-border": colorWithAlpha(c.accent, 0.65),
        "--focus-ring": "0 0 0 3px " + colorWithAlpha(c.accent, 0.2),
    };
}

/** CSS-блок кастомной темы: светлый вариант — база, тёмный — data-mode=dark. */
function customThemeCss(theme) {
    const font = theme.font === "mono" ? "var(--font-mono)"
        : "system-ui, -apple-system, 'Segoe UI', Roboto, 'Noto Sans', 'Helvetica Neue', Arial, sans-serif";
    const radius = theme.radius === "sharp" ? { lg: "0px", mid: "0px", sm: "0px" }
        : theme.radius === "round" ? { lg: "22px", mid: "16px", sm: "12px" } : null;
    const head = '[data-theme="' + theme.id + '"]';
    const darkHead = '[data-theme="' + theme.id + '"][data-mode="dark"]';
    const parts = [];
    const emit = (selector, tokens) => {
        const body = Object.keys(tokens).map(k => "    " + k + ": " + tokens[k] + ";").join("\n");
        parts.push(selector + " {\n" + body + "\n}");
    };
    const base = customThemeTokens(theme.lightColors, false);
    base["--font-ui"] = font;
    if (radius) {
        base["--radius-lg"] = radius.lg;
        base["--radius"] = radius.mid;
        base["--radius-sm"] = radius.sm;
    }
    emit(head, base);
    const darkTokens = customThemeTokens(theme.darkColors, true);
    if (radius) {
        darkTokens["--radius-lg"] = radius.lg;
        darkTokens["--radius"] = radius.mid;
        darkTokens["--radius-sm"] = radius.sm;
    }
    emit(darkHead, darkTokens);
    return parts.join("\n");
}

/** Собрать/пересобрать <style> с палитрами кастомных тем (и предпросмотром). */
function ensureCustomThemesCss() {
    let style = document.getElementById("custom-themes");
    if (!style) {
        style = document.createElement("style");
        style.id = "custom-themes";
        document.head.appendChild(style);
    }
    const parts = customThemes().map(customThemeCss);
    if (THEME_PREVIEW) {
        parts.push(customThemeCss({
            id: "c-preview",
            font: THEME_PREVIEW.font,
            radius: THEME_PREVIEW.radius,
            lightColors: THEME_PREVIEW.lightColors,
            darkColors: THEME_PREVIEW.darkColors,
        }));
    }
    style.textContent = parts.join("\n");
}

/* ---------- Админ: управление темами (Настройки -> Оформление) ---------- */

/** Базовые цвета кастомной темы: ключ -> i18n. */
const THEME_COLOR_KEYS = [
    ["bg", "themes.bg"],
    ["card", "themes.card"],
    ["text", "themes.text"],
    ["textMuted", "themes.textMuted"],
    ["border", "themes.border"],
    ["accent", "themes.accent"],
    ["accent2", "themes.accent2"],
];

/** id для новой кастомной темы (латиница/цифры/дефис; сервер пересчитает сам). */
function themeSlug(name) {
    let s = String(name || "").trim().toLowerCase()
        .replace(/[^a-z0-9а-яё]+/gi, "-").replace(/^-+|-+$/g, "");
    if (s.length > 24) s = s.slice(0, 24);
    return s.replace(/^-+|-+$/g, "") || "theme";
}

function renderAppearance() {
    if (!S.settings) return;
    const disabled = S.themesDisabledLocal;
    const enabledBuiltins = BUILTIN_THEMES.filter(x => !disabled.includes(x.id));
    // селект темы по умолчанию: доступные (включённые встроенные + кастомные)
    const sel = $("set-themeDefault");
    if (sel) {
        sel.innerHTML = "";
        enabledBuiltins.forEach(x => {
            const o = document.createElement("option");
            o.value = x.id;
            o.textContent = t(x.key);
            sel.appendChild(o);
        });
        S.themesCustomLocal.forEach(x => {
            const o = document.createElement("option");
            o.value = x.id;
            o.textContent = x.name;
            sel.appendChild(o);
        });
        const def = S.settings.themeDefault;
        const has = Array.from(sel.options).some(o => o.value === def);
        sel.value = has ? def : (sel.options[0] ? sel.options[0].value : "glass");
    }
    const modeSel = $("set-themeMode");
    if (modeSel) modeSel.value = S.settings.themeMode === "light" ? "light" : "dark";
    // встроенные: включена/выключена (выключенная скрыта у всех)
    const listEl = $("theme-builtin-list");
    if (listEl) {
        listEl.innerHTML = "";
        BUILTIN_THEMES.forEach(theme => {
            const on = !disabled.includes(theme.id);
            const row = document.createElement("div");
            row.className = "theme-admin-row";
            row.innerHTML = '<span class="theme-swatch" style="background:' + themeSwatch(theme) + '"></span>'
                + '<span class="theme-admin-name">' + esc(t(theme.key)) + "</span>"
                + '<span class="theme-admin-tag">' + esc(t(on ? "themes.stateOn" : "themes.stateOff")) + "</span>";
            const input = document.createElement("input");
            input.type = "checkbox";
            input.className = "toggle";
            input.checked = on;
            input.setAttribute("aria-label", t(theme.key));
            input.addEventListener("change", () => {
                if (input.checked) {
                    S.themesDisabledLocal = S.themesDisabledLocal.filter(x => x !== theme.id);
                } else {
                    // нельзя выключить последнюю доступную тему
                    const left = BUILTIN_THEMES.filter(x => x.id !== theme.id && !S.themesDisabledLocal.includes(x.id));
                    if (left.length === 0 && S.themesCustomLocal.length === 0) {
                        toast(t("themes.needOne"), "warn");
                        input.checked = true;
                        return;
                    }
                    S.themesDisabledLocal.push(theme.id);
                }
                renderAppearance();
            });
            row.appendChild(input);
            listEl.appendChild(row);
        });
    }
    // кастомные: свотч, имя, кнопка удаления (добавление — в форме)
    const customEl = $("theme-custom-list");
    if (customEl) {
        customEl.innerHTML = "";
        if (!S.themesCustomLocal.length) {
            customEl.innerHTML = '<div class="muted small">' + esc(t("themes.empty")) + "</div>";
        }
        S.themesCustomLocal.forEach((theme, index) => {
            const swatch = "linear-gradient(135deg, " + ((theme.light && theme.light.bg) || "#F7F7F5")
                + " 0 34%, " + ((theme.light && theme.light.accent) || "#2563EB") + " 34% 66%, "
                + ((theme.dark && theme.dark.bg) || "#101418") + " 66% 100%)";
            const row = document.createElement("div");
            row.className = "theme-admin-row";
            row.innerHTML = '<span class="theme-swatch" style="background:' + swatch + '"></span>'
                + '<span class="theme-admin-name">' + esc(theme.name) + "</span>"
                + '<span class="theme-admin-tag">' + esc(t("themes.customTag")) + "</span>";
            const btn = document.createElement("button");
            btn.type = "button";
            btn.className = "icon-btn";
            btn.innerHTML = icon("trash");
            btn.title = t("themes.delete");
            btn.setAttribute("aria-label", t("themes.delete"));
            btn.addEventListener("click", () => {
                S.themesCustomLocal.splice(index, 1);
                renderAppearance();
                toast(t("themes.removedLocal"), "info");
            });
            row.appendChild(btn);
            customEl.appendChild(row);
        });
    }
}

/** Цветовая сетка варианта (light/dark) для формы кастомной темы. */
function themeColorGridHtml(mode, colors) {
    return '<div class="color-grid">'
        + THEME_COLOR_KEYS.map(([key, i18nKey]) =>
            '<label class="color-field"><span>' + esc(t(i18nKey)) + '</span>'
            + '<input type="color" data-theme-color="' + mode + "-" + key
            + '" value="' + ((colors && colors[key]) || "#888888") + '"></label>').join("")
        + "</div>";
}

/** Открыть форму «Добавить тему» с живым предпросмотром. */
function openThemeAddModal() {
    if (S.themesCustomLocal.length >= 16) {
        toast(t("themes.limit"), "warn");
        return;
    }
    const defaults = {
        light: { bg: "#F7F7F5", card: "#FFFFFF", text: "#1A1D23", textMuted: "#6B7280", border: "#E2E2DE", accent: "#2563EB", accent2: "#7C3AED" },
        dark: { bg: "#101418", card: "#1A1F26", text: "#E8EAED", textMuted: "#9AA3AD", border: "#2A313A", accent: "#3B82F6", accent2: "#A78BFA" },
    };
    const html = ''
        + '<h2 style="margin:0 0 4px">' + esc(t("themes.addTitle")) + "</h2>"
        + '<p class="muted small" style="margin:0 0 14px">' + esc(t("themes.previewHint")) + "</p>"
        + '<div class="theme-edit-head">'
        + '<label class="set-field"><span class="set-label">' + esc(t("themes.name")) + '</span>'
        + '<input type="text" id="ct-name" class="input" maxlength="40" placeholder="' + esc(t("themes.namePlaceholder")) + '"></label>'
        + '<label class="set-field"><span class="set-label">' + esc(t("themes.font")) + '</span>'
        + '<select id="ct-font" class="input select"><option value="sans">' + esc(t("themes.fontSans")) + '</option><option value="mono">' + esc(t("themes.fontMono")) + "</option></select></label>"
        + '<label class="set-field"><span class="set-label">' + esc(t("themes.radius")) + '</span>'
        + '<select id="ct-radius" class="input select"><option value="soft">' + esc(t("themes.radiusSoft")) + '</option><option value="sharp">' + esc(t("themes.radiusSharp")) + '</option><option value="round">' + esc(t("themes.radiusRound")) + "</option></select></label>"
        + "</div>"
        + '<div class="theme-mode-sub">' + icon("sun") + esc(t("themes.colorsLight")) + "</div>"
        + themeColorGridHtml("light", defaults.light)
        + '<div class="theme-mode-sub">' + icon("moon") + esc(t("themes.colorsDark")) + "</div>"
        + themeColorGridHtml("dark", defaults.dark);
    const backdrop = openModal(html, [
        { label: t("common.cancel"), onclick: () => { stopThemePreview(); closeModal(); } },
        { label: t("themes.add"), className: "btn primary", onclick: addThemeFromForm },
    ]);
    if (backdrop) {
        backdrop.addEventListener("input", updateThemePreview);
    }
    updateThemePreview();
}

/** Тема из полей формы (для предпросмотра и добавления). */
function themeFromForm() {
    const light = {};
    const dark = {};
    THEME_COLOR_KEYS.forEach(([key]) => {
        const l = document.querySelector('[data-theme-color="light-' + key + '"]');
        const d = document.querySelector('[data-theme-color="dark-' + key + '"]');
        if (l) light[key] = l.value;
        if (d) dark[key] = d.value;
    });
    const nameInput = $("ct-name");
    return {
        id: "c-preview",
        name: (nameInput ? nameInput.value : "").trim() || t("themes.unnamed"),
        font: $("ct-font") ? $("ct-font").value : "sans",
        radius: $("ct-radius") ? $("ct-radius").value : "soft",
        lightColors: light,
        darkColors: dark,
    };
}

/** Живой предпросмотр: временная тема c-preview применяется целиком. */
function updateThemePreview() {
    THEME_PREVIEW = themeFromForm();
    ensureCustomThemesCss();
    applyTheme("c-preview");
}

/** Закончить предпросмотр и вернуть сохранённую тему. */
function stopThemePreview() {
    if (!THEME_PREVIEW) return;
    THEME_PREVIEW = null;
    ensureCustomThemesCss();
    const sel = detectTheme();
    applyTheme(sel.theme, sel.mode);
}

/** «Добавить» в форме: тема попадает в рабочий список (сохранится по кнопке
 *  «Сохранить настройки»). */
function addThemeFromForm() {
    const raw = themeFromForm();
    if (!$("ct-name") || !$("ct-name").value.trim()) {
        toast(t("themes.nameRequired"), "warn");
        $("ct-name").focus();
        return;
    }
    if (S.themesCustomLocal.length >= 16) {
        toast(t("themes.limit"), "warn");
        return;
    }
    let id = themeSlug(raw.name);
    const taken = x => x.id === id || BUILTIN_THEMES.some(b => b.id === id);
    for (let i = 2; S.themesCustomLocal.some(taken); i++) id = themeSlug(raw.name) + "-" + i;
    S.themesCustomLocal.push({
        id: id,
        name: raw.name,
        font: raw.font,
        radius: raw.radius,
        light: raw.lightColors,
        dark: raw.darkColors,
    });
    THEME_PREVIEW = null;
    ensureCustomThemesCss();
    const sel = detectTheme();
    applyTheme(sel.theme, sel.mode);
    closeModal();
    renderAppearance();
    toast(t("themes.addedLocal"), "success");
}

/* ================= Запуск ================= */
document.addEventListener("DOMContentLoaded", async () => {
    // язык
    LANG = detectLang(null);
    setLang(LANG);
    $("btn-lang").addEventListener("click", () => setLang(LANG === "ru" ? "en" : "ru"));
    // тема оформления (кнопки — в топбаре и на экране входа)
    {
        const initial = detectTheme();
        applyTheme(initial.theme, initial.mode);
    }
    ["btn-theme", "btn-theme-login"].forEach(id => {
        const b = $(id);
        if (b) b.addEventListener("click", () => toggleThemeMenu(b));
    });
    document.addEventListener("click", (e) => {
        if (!themeMenuEl || !themeMenuEl.classList.contains("open")) return;
        if (e.target.closest("#theme-menu") || e.target.closest("#btn-theme")
            || e.target.closest("#btn-theme-login")) return;
        closeThemeMenu();
    });
    document.addEventListener("keydown", (e) => {
        if (e.key === "Escape") closeThemeMenu();
    });
    document.addEventListener("langchange", () => {
        updateThemeLabel();
        if (themeMenuEl && themeMenuEl.classList.contains("open")) renderThemeMenu();
    });
    // показ/скрытие пароля
    $("btn-pass-toggle").addEventListener("click", () => {
        const input = $("login-password");
        const show = input.type === "password";
        input.type = show ? "text" : "password";
        $("btn-pass-toggle").innerHTML = icon(show ? "eye-off" : "eye");
    });
    // вход
    $("login-form").addEventListener("submit", doLogin);
    $("btn-logout").addEventListener("click", doLogout);
    // навигация
    on("tab-mods", "click", () => switchMainTab("mods"));
    on("tab-search", "click", () => switchMainTab("search"));
    on("tab-suggest", "click", () => switchMainTab("suggest"));
    on("tab-settings", "click", () => switchMainTab("settings"));
    on("tab-backups", "click", () => switchMainTab("backups"));
    // бекапы
    on("btn-backup-create", "click", createBackup);
    on("btn-backup-folders-save", "click", saveBackupFolders);
    on("btn-backup-folders-reset", "click", resetBackupFolders);
    const foldersList = $("backup-folders-list");
    if (foldersList) {
        foldersList.addEventListener("change", () => {
            S.backupsFoldersDirty = true; // не затирать несохранённый выбор при опросе
        });
    }
    $("subtab-settings-updates").addEventListener("click", () => switchSettingsTab("updates"));
    $("subtab-settings-appearance").addEventListener("click", () => switchSettingsTab("appearance"));
    $("subtab-settings-security").addEventListener("click", () => switchSettingsTab("security"));
    on("btn-theme-add", "click", openThemeAddModal);
    on("btn-settings2-save", "click", saveModuleSettings);
    $("subtab-bans").addEventListener("click", () => switchSecurityTab("bans"));
    $("subtab-ipfilter").addEventListener("click", () => switchSecurityTab("ipfilter"));
    $("subtab-audit").addEventListener("click", () => switchSecurityTab("audit"));
    $("subtab-users").addEventListener("click", () => switchSecurityTab("users"));
    // Роли админов: вкладка видна только суперадмину (элемент может
    // отсутствовать в кастомной разметке админа — не роняем инициализацию)
    const rolesTab = $("subtab-roles");
    if (rolesTab) rolesTab.addEventListener("click", () => switchSecurityTab("roles"));
    const viewerAdd = $("viewer-add");
    if (viewerAdd) viewerAdd.addEventListener("click", addForcedViewer);
    const viewerInput = $("viewer-add-input");
    if (viewerInput) viewerInput.addEventListener("keydown", e => {
        if (e.key === "Enter") addForcedViewer();
    });
    // действия модпака
    on("btn-check", "click", checkUpdates);
    on("btn-update-all", "click", () => updateMods(null));
    on("btn-scan", "click", openFolderScan);
    on("btn-rehash", "click", rehash);
    on("btn-reload-profile", "click", reloadProfile);
    // фильтры и сортировка списка модов (состояние — в localStorage)
    ["mod", "resourcepack", "shader"].forEach(kind => {
        on("filter-kind-" + kind, "click", () => {
            S.modsFilter.kind = kind;
            saveModsFilter();
            renderPack();
        });
    });
    on("filter-optional", "click", () => {
        S.modsFilter.optional = !S.modsFilter.optional;
        saveModsFilter();
        renderPack();
    });
    on("filter-updates", "click", () => {
        S.modsFilter.updates = !S.modsFilter.updates;
        saveModsFilter();
        renderPack();
    });
    on("mod-sort", "change", () => {
        S.modsFilter.sort = $("mod-sort").value;
        saveModsFilter();
        renderPack();
    });
    // настройки
    on("btn-settings-save", "click", saveModuleSettings);
    on("suggest-ban-add", "click", suggestBanAdd);
    on("suggest-ban-input", "keydown", (e) => {
        if (e.key === "Enter") suggestBanAdd();
    });
    // вкладка предложений (админ)
    on("suggest-search", "input", onSuggestSearchInput);
    on("suggest-kind", "change", loadSuggestions);
    on("btn-suggest-refresh", "click", loadSuggestions);
    // поиск по установленным модам (живой фильтр, без обращения к API)
    on("mod-search", "input", onModSearchInput);
    on("mod-search", "keydown", (e) => {
        if (e.key === "Escape") clearModSearch();
    });
    on("mod-search-clear", "click", clearModSearch);
    // каталог (вкладка «Поиск»)
    on("btn-search-run", "click", () => {
        S.search.query = $("search-query").value.trim();
        runSearch(true);
    });
    on("search-query", "keydown", (e) => {
        if (e.key === "Enter") {
            S.search.query = $("search-query").value.trim();
            runSearch(true);
        }
    });
    // тип контента в поиске: сбросить категории/загрузчик, перезапустить поиск;
    // выбор запоминается в браузере (персональный дефолт)
    on("search-type", "change", () => {
        S.search.type = $("search-type").value;
        localStorage.setItem("mods_ptype", S.search.type);
        S.search.cats.clear();
        S.search.loader = "any";
        applyProfileFilters();
        syncSearchControls();
        renderSearchFilters();
        runSearch(true);
    });
    on("search-source", "change", () => {
        S.search.source = $("search-source").value;
        S.search.cats.clear();
        renderSearchFilters();
        runSearch(true);
    });
    on("filter-version", "change", () => {
        S.search.gameVersion = $("filter-version").value.trim();
        runSearch(true);
    });
    on("filter-version", "keydown", (e) => {
        if (e.key === "Enter") {
            S.search.gameVersion = $("filter-version").value.trim();
            runSearch(true);
        }
    });
    on("filter-sort", "change", () => {
        S.search.sort = $("filter-sort").value;
        runSearch(true);
    });
    on("btn-search-prev", "click", () => {
        S.search.offset = Math.max(0, S.search.offset - S.search.limit);
        runSearch(false);
    });
    on("btn-search-next", "click", () => {
        S.search.offset += S.search.limit;
        runSearch(false);
    });
    on("btn-search-reset", "click", resetSearchFilters);
    // аудит
    on("btn-audit-refresh", "click", loadAudit);
    on("btn-audit-newer", "click", () => {
        S.auditOffset = Math.max(0, S.auditOffset - 60);
        loadAudit();
    });
    on("btn-audit-older", "click", () => {
        S.auditOffset += 60;
        loadAudit();
    });
    // ip-фильтр
    on("wl-add", "click", () => ipFilterAdd("white", $("wl-input").value));
    on("bl-add", "click", () => ipFilterAdd("black", $("bl-input").value));
    on("wl-input", "keydown", (e) => {
        if (e.key === "Enter") ipFilterAdd("white", $("wl-input").value);
    });
    on("bl-input", "keydown", (e) => {
        if (e.key === "Enter") ipFilterAdd("black", $("bl-input").value);
    });
    await boot();
});

/* ================= Бекапы (1.1.0, admin) ================= */

/** 1234567 → «1.2 MiB» — размеры бекапов и общий размер. */
function fmtBytes(n) {
    n = Number(n) || 0;
    if (n >= 1024 ** 3) return (n / 1024 ** 3).toFixed(2).replace(/\.?0+$/, "") + " GiB";
    if (n >= 1024 ** 2) return (n / 1024 ** 2).toFixed(1).replace(/\.0$/, "") + " MiB";
    if (n >= 1024) return (n / 1024).toFixed(1).replace(/\.0$/, "") + " KiB";
    return n + " B";
}

/** Имя бекапа по умолчанию — дата и время прямо сейчас (как на сервере). */
function defaultBackupName() {
    const d = new Date();
    const pad = (x) => String(x).padStart(2, "0");
    return d.getFullYear() + "-" + pad(d.getMonth() + 1) + "-" + pad(d.getDate())
            + " " + pad(d.getHours()) + ":" + pad(d.getMinutes());
}

function stopBackupsPoll() {
    if (S.backupsTimer) {
        clearTimeout(S.backupsTimer);
        S.backupsTimer = null;
    }
}

/** Загрузка списка бекапов профиля (+ опрос активной задачи). */
async function loadBackups() {
    if (!S.currentProfile) {
        renderBackups({ backups: [], totalSize: 0, count: 0, job: null, folders: [] });
        return;
    }
    const result = await api("backups?profile=" + encodeURIComponent(S.currentProfile));
    if (!result.ok) {
        toast(result.error ? result.error.message : t("common.error"), "error");
        stopBackupsPoll();
        return;
    }
    S.backups = result.data;
    renderBackups(result.data);
    const job = result.data.job;
    if (job) {
        S.backupsJobSeen = true;
        stopBackupsPoll();
        S.backupsTimer = setTimeout(loadBackups, 2500);
    } else {
        stopBackupsPoll();
        if (S.backupsJobSeen) {
            S.backupsJobSeen = false;
            const last = result.data.lastResult;
            if (last && Date.now() - last.finishedAt < 120000) {
                toast((last.ok ? t("backups.done") : t("backups.failed")) + ": " + last.message,
                        last.ok ? "success" : "error");
                // состав модпака мог измениться — обновляем и его
                loadMods(S.currentProfile);
            }
        }
    }
}

function renderBackups(data) {
    const tbody = $("backups-tbody");
    if (!tbody) return;
    const isAdmin = S.session && (S.session.role === "admin" || S.session.role === "superadmin");
    const disabled = data.enabled === false;
    $("backups-total").textContent = disabled ? "—" : fmtBytes(data.totalSize);
    $("backups-count").textContent = disabled ? "" : "(" + (data.count || 0) + ")";
    const empty = disabled || (data.count || 0) === 0;
    $("backups-empty").textContent = disabled ? t("backups.disabled") : t("backups.empty");
    $("backups-empty").classList.toggle("hidden", !empty);
    $("table-backups").classList.toggle("hidden", empty);
    $("btn-backup-create").disabled = disabled || !isAdmin;
    $("btn-backup-folders-save").disabled = disabled || !isAdmin;
    $("btn-backup-folders-reset").disabled = disabled || !isAdmin;
    const jobEl = $("backup-job");
    if (data.job) {
        jobEl.classList.remove("hidden");
        const label = data.job.type === "restore" ? t("backups.jobRestore") : t("backups.jobCreate");
        jobEl.innerHTML = icon("refresh") + "<span>" + esc(label)
                + (data.job.message ? " — " + esc(data.job.message) : "") + "</span>";
    } else {
        jobEl.classList.add("hidden");
    }
    if (!disabled) {
        renderBackupFolders(data);
        const nameInput = $("backup-name");
        if (document.activeElement !== nameInput && !nameInput.value) {
            nameInput.value = defaultBackupName();
        }
    } else {
        const list = $("backup-folders-list");
        if (list) list.replaceChildren();
    }
    tbody.replaceChildren();
    for (const b of data.backups || []) {
        tbody.appendChild(backupRow(b, isAdmin));
    }
}

/** Чекбоксы «что бекапить»: папки из папки сборки профиля.
 *  Отмечены — текущий выбор (сохранённый или дефолт). Пока админ меняет
 *  галочки без сохранения — перерисовку пропускаем (опрос задачи каждые 2.5 с
 *  не должен сбивать выбор). */
function renderBackupFolders(data) {
    const box = $("backup-folders-list");
    if (!box) return;
    if (S.backupsFoldersDirty) return;
    const isAdmin = S.session && (S.session.role === "admin" || S.session.role === "superadmin");
    const selected = new Set(data.folders || []);
    const list = data.profileFolders || [];
    box.replaceChildren();
    if (!list.length) {
        const empty = document.createElement("p");
        empty.className = "muted small";
        empty.textContent = t("backups.foldersEmpty");
        box.appendChild(empty);
        return;
    }
    for (const folder of list) {
        const label = document.createElement("label");
        label.className = "folder-check" + (folder.exists ? "" : " missing");
        const input = document.createElement("input");
        input.type = "checkbox";
        input.value = folder.name;
        input.checked = selected.has(folder.name);
        input.disabled = !isAdmin;
        const name = document.createElement("span");
        name.textContent = folder.name;
        label.append(input, name);
        if (!folder.exists) {
            const miss = document.createElement("span");
            miss.className = "muted small";
            miss.textContent = " — " + t("backups.folderMissing");
            label.appendChild(miss);
        }
        box.appendChild(label);
    }
}

function backupRow(b, isAdmin) {
    const tr = document.createElement("tr");
    const name = document.createElement("td");
    name.innerHTML = "<strong>" + esc(b.name) + "</strong>"
            + (b.foreign ? ' <span class="badge-foreign">' + esc(t("backups.foreignBadge")) + "</span>" : "")
            + (b.frozen ? ' <span class="badge-frozen">' + esc(t("backups.frozen")) + "</span>" : "");
    const date = document.createElement("td");
    date.textContent = fmtTime(b.createdAt);
    const size = document.createElement("td");
    size.textContent = fmtBytes(b.size);
    const content = document.createElement("td");
    content.className = "muted small";
    content.textContent = t("backups.contentSummary")
            .replace("{remote}", b.remoteCount || 0).replace("{files}", b.fileCount || 0);
    const actions = document.createElement("td");
    actions.className = "row-actions";
    if (isAdmin) {
        actions.appendChild(actionBtn("backups.rename", () => backupRenameModal(b), "note"));
        actions.appendChild(actionBtn(b.frozen ? "backups.unfreeze" : "backups.freeze",
                () => backupFreeze(b, !b.frozen), "shield"));
        actions.appendChild(actionBtn("backups.download", () => backupDownload(b), "download"));
        actions.appendChild(actionBtn("backups.restore", () => backupRestoreModal(b), "refresh"));
        actions.appendChild(actionBtn("backups.delete", () => backupDeleteModal(b), "trash"));
    }
    tr.append(name, date, size, content, actions);
    return tr;
}

function actionBtn(key, onclick, iconName) {
    const btn = document.createElement("button");
    btn.type = "button";
    btn.className = "btn ghost small";
    btn.title = t(key);
    btn.innerHTML = icon(iconName);
    btn.addEventListener("click", onclick);
    return btn;
}

async function createBackup() {
    if (!S.currentProfile) return;
    const btn = $("btn-backup-create");
    btn.disabled = true;
    const name = $("backup-name").value.trim() || defaultBackupName();
    const result = await api("backups/create", {
        method: "POST",
        body: { profile: S.currentProfile, name: name },
    });
    btn.disabled = false;
    if (result.ok) {
        toast(t("backups.started"), "success");
        $("backup-name").value = "";
        S.backupsJobSeen = false; // это наша задача — тост по завершении и так покажем
        setTimeout(loadBackups, 300);
    } else {
        toast(result.error ? result.error.message : t("common.error"), "error");
    }
}

function backupRenameModal(b) {
    const input = '<div class="modal-field"><input type="text" id="backup-rename-input" class="input" maxlength="100" value="' + esc(b.name) + '"></div>';
    const modal = openModal('<h3 class="modal-title">' + esc(t("backups.renameTitle")) + "</h3>" + input, [
        {
            label: t("common.cancel"), className: "btn ghost", onclick: closeModal,
        },
        {
            label: t("mods.save"), className: "btn primary",
            onclick: async () => {
                const value = document.getElementById("backup-rename-input").value.trim();
                if (!value) return;
                const result = await api("backups/rename", {
                    method: "POST",
                    body: { profile: S.currentProfile, id: b.id, name: value },
                });
                closeModal();
                if (result.ok) {
                    toast(t("backups.renamed"), "success");
                    loadBackups();
                } else {
                    toast(result.error ? result.error.message : t("common.error"), "error");
                }
            },
        },
    ]);
    return modal;
}

async function backupFreeze(b, frozen) {
    const result = await api("backups/freeze", {
        method: "POST",
        body: { profile: S.currentProfile, id: b.id, frozen: frozen },
    });
    if (result.ok) {
        toast(t(frozen ? "backups.frozenMsg" : "backups.unfrozenMsg"), "success");
        loadBackups();
    } else {
        toast(result.error ? result.error.message : t("common.error"), "error");
    }
}

function backupRestoreModal(b) {
    const textKey = b.foreign ? "backups.confirmRestoreForeign" : "backups.confirmRestoreText";
    openModal(
            '<h3 class="modal-title">' + esc(t("backups.confirmRestoreTitle")) + "</h3>"
            + '<p class="muted">' + esc(t(textKey).replace("{name}", b.name)) + "</p>",
            [
                { label: t("common.cancel"), className: "btn ghost", onclick: closeModal },
                {
                    label: t("backups.restore"), className: "btn primary",
                    onclick: async () => {
                        closeModal();
                        const result = await api("backups/restore", {
                            method: "POST",
                            body: { profile: S.currentProfile, id: b.id },
                        });
                        if (result.ok) {
                            toast(t("backups.restoreStarted"), "success");
                            S.backupsJobSeen = true;
                            setTimeout(loadBackups, 300);
                        } else {
                            toast(result.error ? result.error.message : t("common.error"), "error");
                        }
                    },
                },
            ]);
}

function backupDeleteModal(b) {
    if (b.frozen) {
        toast(t("backups.frozenDelete"), "warn");
        return;
    }
    openModal(
            '<h3 class="modal-title">' + esc(t("backups.confirmDeleteTitle")) + "</h3>"
            + '<p class="muted">' + esc(t("backups.confirmDeleteText").replace("{name}", b.name)) + "</p>",
            [
                { label: t("common.cancel"), className: "btn ghost", onclick: closeModal },
                {
                    label: t("backups.delete"), className: "btn danger",
                    onclick: async () => {
                        closeModal();
                        const result = await api("backups/delete", {
                            method: "POST",
                            body: { profile: S.currentProfile, id: b.id },
                        });
                        if (result.ok) {
                            toast(t("backups.deleted"), "success");
                            loadBackups();
                        } else {
                            toast(result.error ? result.error.message : t("common.error"), "error");
                        }
                    },
                },
            ]);
}

function backupDownload(b) {
    const url = API_BASE + "backups/download?profile="
            + encodeURIComponent(S.currentProfile) + "&id=" + encodeURIComponent(b.id);
    const a = document.createElement("a");
    a.href = url;
    a.download = "";
    document.body.appendChild(a);
    a.click();
    a.remove();
}

async function saveBackupFolders() {
    if (!S.currentProfile) return;
    // Собираем отмеченные галочками папки
    const folders = [...document.querySelectorAll("#backup-folders-list input[type=checkbox]:checked")]
            .map(el => el.value)
            .filter(Boolean);
    if (!folders.length) {
        toast(t("backups.foldersNone"), "warn");
        return;
    }
    const result = await api("backups/folders", {
        method: "POST",
        body: { profile: S.currentProfile, folders: folders },
    });
    if (result.ok) {
        S.backupsFoldersDirty = false;
        toast(t("backups.foldersSaved"), "success");
        loadBackups();
    } else {
        toast(result.error ? result.error.message : t("common.error"), "error");
    }
}

async function resetBackupFolders() {
    if (!S.currentProfile) return;
    // Пустой список = дефолт (три стандартные папки контента)
    const result = await api("backups/folders", {
        method: "POST",
        body: { profile: S.currentProfile, folders: [] },
    });
    if (result.ok) {
        S.backupsFoldersDirty = false;
        toast(t("backups.foldersSaved"), "success");
        loadBackups();
    } else {
        toast(result.error ? result.error.message : t("common.error"), "error");
    }
}
