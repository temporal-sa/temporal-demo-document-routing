// The demo page. Everything shown here comes from /api/state, which reads Temporal visibility,
// so the page keeps working while no worker is running.
"use strict";

const $ = (id) => document.getElementById(id);
let config = null;
let lastState = null;

// ---- helpers --------------------------------------------------------------------------------

function el(tag, attrs = {}, ...children) {
  const node = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (k === "class") node.className = v;
    else if (k.startsWith("on")) node.addEventListener(k.slice(2), v);
    else if (v !== null && v !== undefined) node.setAttribute(k, v);
  }
  for (const c of children.flat()) {
    if (c === null || c === undefined) continue;
    node.append(c instanceof Node ? c : document.createTextNode(String(c)));
  }
  return node;
}

function uiQueryUrl(query) {
  return `${config.temporalUiUrl}/namespaces/${encodeURIComponent(config.namespace)}/workflows?query=${encodeURIComponent(query)}`;
}

function uiWorkflowUrl(workflowId, runId) {
  const base = `${config.temporalUiUrl}/namespaces/${encodeURIComponent(config.namespace)}/workflows/${encodeURIComponent(workflowId)}`;
  return runId ? `${base}/${encodeURIComponent(runId)}/history` : base;
}

function money(n) {
  return n === null || n === undefined ? "" : "$" + Number(n).toLocaleString("en-US");
}

let toastTimer;
function toast(message, kind = "ok") {
  const t = $("toast");
  t.textContent = message;
  t.className = `toast ${kind}`;
  t.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => (t.hidden = true), 5000);
}

async function api(method, path, body) {
  const res = await fetch(path, {
    method,
    headers: { "Content-Type": "application/json" },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  let data = null;
  try { data = await res.json(); } catch { /* empty body */ }
  return { ok: res.ok, status: res.status, data };
}

// ---- submit ---------------------------------------------------------------------------------

async function submit(scenario) {
  const { status, data } = await api("POST", "/api/submit", { scenario });
  if (!data || !data.results) {
    toast(data?.error || `Submit failed (HTTP ${status})`, "error");
    return;
  }
  const started = data.results.filter((r) => !r.duplicate);
  const dups = data.results.filter((r) => r.duplicate);
  if (dups.length) toast(dups[0].message, "warn");
  else toast(`Started ${started.map((r) => r.documentId).join(", ")}`);
  refresh();
}

function renderScenarios() {
  const box = $("scenarios");
  box.replaceChildren(...config.scenarios.map((s) =>
    el("button", { class: "chip", "data-scenario": s.id, onclick: () => submit(s.id) },
      el("span", { class: "chip-label" }, s.label),
      el("span", { class: "chip-hint" }, s.hint))));
}

// ---- inbox ----------------------------------------------------------------------------------

function slaCell(task) {
  if (task.slaBreached) return el("span", { class: "pill danger" }, "SLA breached");
  const left = Math.round((Date.parse(task.slaDeadline) - Date.now()) / 1000);
  if (Number.isNaN(left)) return "";
  const m = Math.floor(Math.max(left, 0) / 60);
  const s = String(Math.max(left, 0) % 60).padStart(2, "0");
  return el("span", { class: left <= 15 ? "pill warn" : "pill" }, `${m}:${s} left`);
}

// Complete is an Update on the request and Reassign a Signal. The action is credited to the task's assignee.
async function taskAction(path, body, buttons) {
  buttons.forEach((b) => (b.disabled = true));
  const { ok, data } = await api("POST", path, body);
  if (ok && data?.pending) toast(data.error, "warn");
  else if (ok) toast(`${data.message}: recorded by Temporal`);
  else {
    toast(data?.error || "Nothing was recorded", "error");
    buttons.forEach((b) => (b.disabled = false));
  }
  refresh();
}

function complete(task, commentInput, buttons) {
  taskAction("/api/tasks/complete",
    { workflowId: task.workflowId, taskId: task.taskId, comment: commentInput.value }, buttons);
}

// A blank box hands the task to the suggested person, if there is one.
function reassign(task, assigneeInput, commentInput, buttons) {
  const assignee = assigneeInput.value.trim() || suggestedReassignee(task.assignee) || "";
  taskAction("/api/tasks/reassign",
    { workflowId: task.workflowId, taskId: task.taskId, assignee, comment: commentInput.value }, buttons);
}

// The demo's two people hand tasks to each other, so suggest the other one. No suggestion for anyone else.
function suggestedReassignee(assignee) {
  if (assignee === "Bob") return "Alice";
  if (assignee === "Alice") return "Bob";
  return null;
}

let inboxSignature = "";

// Rows are rebuilt only when the set of tasks changes, so a comment being typed survives polls;
// otherwise only the SLA cells are refreshed.
function renderInbox(inbox) {
  const body = $("inbox");
  const signature = inbox.map((t) => `${t.taskId}:${t.slaBreached}`).join("|");
  if (signature === inboxSignature) {
    inbox.forEach((t) => {
      const cell = body.querySelector(`[data-sla="${CSS.escape(t.taskId)}"]`);
      if (cell) cell.replaceChildren(slaCell(t));
    });
    return;
  }
  inboxSignature = signature;
  if (!inbox.length) {
    body.replaceChildren(el("tr", {}, el("td", { colspan: 7, class: "muted" }, "No open tasks.")));
    return;
  }
  body.replaceChildren(...inbox.map((t) => {
    const comment = el("input", { class: "comment", placeholder: "comment (optional)" });
    const to = el("input", { class: "assignee", placeholder: suggestedReassignee(t.assignee) ?? "name", size: 8, "aria-label": "Reassign to" });
    const done = el("button", { class: "button small", title: "Finish the task; the request completes" }, "Complete");
    const hand = el("button", { class: "button small ghost", title: "Hand the task to the person named" }, "Reassign");
    done.addEventListener("click", () => complete(t, comment, [done, hand]));
    hand.addEventListener("click", () => reassign(t, to, comment, [done, hand]));
    to.addEventListener("keydown", (e) => { if (e.key === "Enter") hand.click(); });
    return el("tr", { class: t.slaBreached ? "breached" : "" },
      el("td", {}, el("a", { href: uiWorkflowUrl(t.workflowId, t.runId), target: "_blank", rel: "noopener" }, t.documentId),
        el("div", { class: "muted small" }, t.taskId || "")),
      el("td", {}, t.assignee || ""),
      el("td", {}, t.businessUnit),
      el("td", {}, money(t.cost)),
      el("td", {}, t.rulesVersion ? `v${t.rulesVersion}` : ""),
      el("td", { "data-sla": t.taskId }, slaCell(t)),
      el("td", { class: "actions" }, comment, done, to, hand));
  }));
}

// ---- recent ---------------------------------------------------------------------------------

function statusPill(r) {
  const cls = { Running: "", Completed: "ok", Failed: "danger", Terminated: "danger", "Timed out": "danger" }[r.status] ?? "";
  const label = r.status === "Completed" && r.step === "Rejected" ? "Rejected" : r.status;
  return el("span", { class: `pill ${label === "Rejected" ? "warn" : cls}` }, label);
}

function renderRecent(recent) {
  const body = $("recent");
  if (!recent.length) {
    body.replaceChildren(el("tr", {}, el("td", { colspan: 8, class: "muted" }, "Nothing submitted yet.")));
    return;
  }
  body.replaceChildren(...recent.map((r) => el("tr", {},
    el("td", {}, el("a", { href: uiWorkflowUrl(r.workflowId, r.runId), target: "_blank", rel: "noopener" }, r.documentId || r.workflowId)),
    el("td", {}, r.businessUnit || ""),
    el("td", {}, r.docType || ""),
    el("td", {}, money(r.cost), r.costCenter ? el("div", { class: "muted small" }, r.costCenter) : null),
    el("td", {}, r.path || ""),
    el("td", { class: "mono small" }, r.step || ""),
    el("td", {}, statusPill(r)),
    el("td", { class: "mono small" }, r.version || ""))));
}

// ---- systems and rules ------------------------------------------------------------------------

function renderSystems(systems) {
  const unreachable = !!systems.unreachable;
  $("systems-unreachable").hidden = !unreachable;
  for (const id of ["erp-down", "supplier-rejects"]) $(id).disabled = unreachable;
  if (unreachable) {
    $("erp-state").textContent = "unreachable";
    $("supplier-state").textContent = "unreachable";
    return;
  }
  $("erp-down").checked = systems.erpDown;
  $("erp-state").textContent = systems.erpDown ? "DOWN (503)" : "up";
  $("supplier-rejects").checked = systems.supplierRejects;
  $("supplier-state").textContent = systems.supplierRejects ? "REJECTS" : "accepts";
}

async function setFaults() {
  const body = { erpDown: $("erp-down").checked, supplierRejects: $("supplier-rejects").checked };
  const { ok, data } = await api("PUT", "/api/control", body);
  if (!ok) toast(data?.error || "Could not change the systems", "error");
  refresh();
}

function renderRules(rules) {
  const badge = $("rules-badge");
  badge.textContent = rules.version ? `rules v${rules.version}` : "no valid rules";
  badge.className = rules.lastRejection ? "badge warn" : "badge";
  badge.title = rules.lastRejection ? `Last edit rejected: ${rules.lastRejection}` : "data/rules.json";
  const banner = $("banner");
  if (rules.lastRejection) {
    banner.textContent = `rules.json edit rejected; still using v${rules.version ?? "?"}: ${rules.lastRejection}`;
    banner.hidden = false;
  } else {
    banner.hidden = true;
  }
}

// ---- rules card -------------------------------------------------------------------------------

let rulesCardVersion = null;

// Fields are rebuilt only when the rules version changes and nothing is being edited, so a
// threshold or name being typed survives polls.
function renderRulesCard(rules) {
  $("rules-card-version").textContent = rules.version ? `v${rules.version}` : "none";
  const box = $("thresholds");
  const dirty = box.querySelector("input[data-dirty]");
  if (rules.version !== rulesCardVersion && !dirty) {
    rulesCardVersion = rules.version;
    const field = (unit, name, attrs, label) => {
      const input = el("input", { ...attrs, "data-unit": unit, "data-field": name, "aria-label": `${unit} ${label}` });
      input.addEventListener("input", () => {
        input.dataset.dirty = "1";
        updateSaveButton();
      });
      return input;
    };
    const rows = Object.entries(rules.units || {}).map(([unit, r]) => [
      el("span", {}, unit),
      field(unit, "humanReviewAbove", { type: "number", min: "0", step: "1", value: r.humanReviewAbove }, "human review above"),
      field(unit, "firstAssignee", { type: "text", value: r.firstAssignee ?? "", autocomplete: "off" }, "first assignee"),
    ]);
    box.replaceChildren(
      ...(rows.length ? [el("span"), el("span", { class: "muted small" }, "Review above $"), el("span", { class: "muted small" }, "First assignee")] : []),
      ...rows.flat(),
    );
  }
  updateSaveButton();
}

function updateSaveButton() {
  const dirty = $("thresholds").querySelector("input[data-dirty]");
  const button = $("save-rules");
  button.disabled = !dirty || !rulesCardVersion;
  button.textContent = rulesCardVersion ? `Save as v${rulesCardVersion + 1}` : "Save";
}

async function saveRules() {
  const units = {};
  for (const input of $("thresholds").querySelectorAll("input[data-dirty]")) {
    const { unit, field } = input.dataset;
    const value = input.value.trim();
    if (field === "humanReviewAbove") {
      if (!/^-?\d+$/.test(value)) {
        toast(`${unit} threshold must be a whole number`, "error");
        return;
      }
      (units[unit] ??= {}).humanReviewAbove = Number(value);
    } else {
      if (value === "") {
        toast(`${unit} needs a first assignee`, "error");
        return;
      }
      (units[unit] ??= {}).firstAssignee = value;
    }
  }
  const { ok, data } = await api("PUT", "/api/rules", { units });
  if (!ok) {
    toast(data?.error || "Rules not saved", "error");
    return;
  }
  toast(`Saved rules v${data.version}: the next evaluation uses them`);
  rulesCardVersion = null; // rebuild the fields from what was saved
  $("thresholds").querySelectorAll("input").forEach((i) => delete i.dataset.dirty);
  refresh();
}

async function resetData() {
  if (!confirm("Reset demo data?\n\nRules go back to v1, CC-99 is removed from the known cost centers, and both system toggles are cleared. Requests already in Temporal are not touched.")) return;
  const { ok, data } = await api("POST", "/api/reset-data");
  if (!ok) {
    toast(data?.error || "Reset failed", "error");
    return;
  }
  toast(`Demo data reset: rules v${data.rules.version}, systems ${data.faults}`);
  rulesCardVersion = null;
  $("thresholds").querySelectorAll("input").forEach((i) => delete i.dataset.dirty);
  refresh();
}

function renderViews() {
  const views = [
    ["Waiting on a person", 'Step="HumanTask" AND ExecutionStatus="Running"'],
    ["Waiting on Alice", 'Step="HumanTask" AND Assignee="Alice" AND ExecutionStatus="Running"'],
    ["SLA breached", "SlaBreached=true"],
    ["Failed at budget reservation", 'ExecutionStatus="Failed" AND Step="ReserveBudget"'],
    ["Waiting for the ERP to confirm", 'Step="AwaitErpConfirmation" AND ExecutionStatus="Running"'],
    ["Rejected by the supplier", 'Step="Rejected"'],
    ["Services", 'BusinessUnit="Services"'],
    ["Equipment", 'BusinessUnit="Equipment"'],
    ["All document requests", 'WorkflowType="DocumentRequestWorkflow"'],
  ];
  $("views").replaceChildren(...views.map(([label, q]) =>
    el("li", {}, el("a", { href: uiQueryUrl(q), target: "_blank", rel: "noopener" }, label),
      el("code", { class: "small muted" }, q))));
}

// ---- polling --------------------------------------------------------------------------------

async function refresh() {
  try {
    const { ok, data } = await api("GET", "/api/state");
    if (!ok) throw new Error(data?.error || "state unavailable");
    lastState = data;
    renderInbox(data.inbox);
    renderRecent(data.recent);
    renderSystems(data.systems);
    renderRules(data.rules);
    renderRulesCard(data.rules);
  } catch (e) {
    const banner = $("banner");
    banner.textContent = `Reconnecting to Temporal… (${e.message})`;
    banner.hidden = false;
  }
}

async function init() {
  const res = await api("GET", "/api/config");
  config = res.data;
  $("task-queue").textContent = `task queue · ${config.taskQueue}`;
  $("temporal-ui").href = `${config.temporalUiUrl}/namespaces/${encodeURIComponent(config.namespace)}/workflows`;
  renderScenarios();
  renderViews();
  $("erp-down").addEventListener("change", setFaults);
  $("supplier-rejects").addEventListener("change", setFaults);
  $("save-rules").addEventListener("click", saveRules);
  $("reset-data").addEventListener("click", resetData);
  await refresh();
  setInterval(refresh, 2000);
  // Keep SLA countdowns ticking between polls.
  setInterval(() => lastState && renderInbox(lastState.inbox), 1000);
}

init();
