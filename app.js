const CATALOG = [
  { registration: "EC-MIG", model: "787-8" },
  { registration: "EC-MIH", model: "787-8" },
  { registration: "EC-MLT", model: "787-8" },
  { registration: "EC-MMX", model: "787-8" },
  { registration: "EC-MMY", model: "787-8" },
  { registration: "EC-MOM", model: "787-8" },
  { registration: "EC-MPE", model: "787-8" },
  { registration: "EC-NVZ", model: "787-8" },
  { registration: "EC-NXA", model: "787-8" },
  { registration: "EC-NZG", model: "787-8" },
  { registration: "EC-MSZ", model: "787-9" },
  { registration: "EC-MTI", model: "787-9" },
  { registration: "EC-NBM", model: "787-9" },
  { registration: "EC-NBX", model: "787-9" },
  { registration: "EC-NCY", model: "787-9" },
  { registration: "EC-NEI", model: "787-9" },
  { registration: "EC-NFM", model: "787-9" },
  { registration: "EC-NGM", model: "787-9" },
  { registration: "EC-NGN", model: "787-9" },
  { registration: "EC-NGS", model: "787-9" },
  { registration: "EC-ODH", model: "787-9" },
  { registration: "EC-ODI", model: "787-9" },
  { registration: "EC-OEM", model: "787-9" },
  { registration: "EC-OFO", model: "787-9" },
  { registration: "EC-OFP", model: "787-9" },
  { registration: "EC-OGY", model: "787-9" },
  { registration: "EC-OMB", model: "787-9" },
  { registration: "EC-OMC", model: "787-9" },
];

const PREMIUM = new Set(["EC-NXA", "EC-NVZ", "EC-NZG", "EC-OGY"]);
const STORAGE_KEY = "calculo-mantas-pwa-v1";
const byRegistration = new Map(CATALOG.map((item) => [item.registration, item]));

const defaultState = {
  draft: [],
  active: [],
  dressedAircraft: [],
  dressedHold: [],
  retiredAircraftLoads: [],
  retiredHoldLoads: [],
};

let state = loadState();

const elements = {
  bulkInput: document.querySelector("#bulkInput"),
  loadBulkButton: document.querySelector("#loadBulkButton"),
  exampleButton: document.querySelector("#exampleButton"),
  addSelect: document.querySelector("#addSelect"),
  addButton: document.querySelector("#addButton"),
  draftList: document.querySelector("#draftList"),
  confirmButton: document.querySelector("#confirmButton"),
  summarySection: document.querySelector("#summarySection"),
  trackingSection: document.querySelector("#trackingSection"),
  aircraftCount: document.querySelector("#aircraftCount"),
  changeImpact: document.querySelector("#changeImpact"),
  totalBlankets: document.querySelector("#totalBlankets"),
  totalCages: document.querySelector("#totalCages"),
  remainingBlankets: document.querySelector("#remainingBlankets"),
  remainingCages: document.querySelector("#remainingCages"),
  trackingList: document.querySelector("#trackingList"),
  resetDayButton: document.querySelector("#resetDayButton"),
  draftRowTemplate: document.querySelector("#draftRowTemplate"),
  trackingRowTemplate: document.querySelector("#trackingRowTemplate"),
};

init();

function init() {
  fillSelect(elements.addSelect);
  bindEvents();
  render();

  if ("serviceWorker" in navigator) {
    navigator.serviceWorker.register("service-worker.js").catch(() => {});
  }
}

function bindEvents() {
  elements.loadBulkButton.addEventListener("click", () => {
    state.draft = parseRegistrations(elements.bulkInput.value);
    persist();
    render();
  });

  elements.exampleButton.addEventListener("click", () => {
    elements.bulkInput.value = [
      "EC-MTI",
      "EC-NEI",
      "EC-ODI",
      "EC-NFM",
      "EC-MLT",
      "EC-ODH",
      "EC-MPE",
    ].join("\n");
  });

  elements.addButton.addEventListener("click", () => {
    addToDraft(elements.addSelect.value);
  });

  elements.confirmButton.addEventListener("click", () => {
    confirmDraft();
  });

  elements.resetDayButton.addEventListener("click", () => {
    if (!confirm("¿Empezar una jornada nueva y borrar el seguimiento actual?")) return;
    state = { ...defaultState, draft: [] };
    persist();
    render();
  });
}

function loadState() {
  try {
    const parsed = JSON.parse(localStorage.getItem(STORAGE_KEY) ?? "null");
    return {
      ...defaultState,
      ...parsed,
      draft: Array.isArray(parsed?.draft) ? parsed.draft : [],
      active: Array.isArray(parsed?.active) ? parsed.active : [],
      dressedAircraft: Array.isArray(parsed?.dressedAircraft) ? parsed.dressedAircraft : [],
      dressedHold: Array.isArray(parsed?.dressedHold) ? parsed.dressedHold : [],
      retiredAircraftLoads: Array.isArray(parsed?.retiredAircraftLoads)
        ? parsed.retiredAircraftLoads
        : [],
      retiredHoldLoads: Array.isArray(parsed?.retiredHoldLoads) ? parsed.retiredHoldLoads : [],
    };
  } catch {
    return { ...defaultState };
  }
}

function persist() {
  localStorage.setItem(STORAGE_KEY, JSON.stringify(state));
}

function fillSelect(select, selected = CATALOG[0].registration) {
  select.replaceChildren(
    ...CATALOG.map((item) => {
      const option = document.createElement("option");
      option.value = item.registration;
      option.textContent = `${item.registration} · ${categoryFor(item.registration)}`;
      option.selected = item.registration === selected;
      return option;
    }),
  );
}

function parseRegistrations(text) {
  const matches = text.toUpperCase().match(/EC[-\s]?[A-Z0-9]{3}/g) ?? [];
  const normalized = matches
    .map(normalizeRegistration)
    .filter((registration) => byRegistration.has(registration));
  return uniqueInOrder(normalized);
}

function normalizeRegistration(value) {
  const compact = value.trim().toUpperCase().replace(/[^A-Z0-9]/g, "");
  if (/^EC[A-Z0-9]{3}$/.test(compact)) {
    return `EC-${compact.slice(2)}`;
  }
  return value.trim().toUpperCase();
}

function uniqueInOrder(values) {
  const seen = new Set();
  return values.filter((value) => {
    if (seen.has(value)) return false;
    seen.add(value);
    return true;
  });
}

function addToDraft(registration) {
  if (!byRegistration.has(registration) || state.draft.includes(registration)) return;
  state.draft = [...state.draft, registration];
  persist();
  render();
}

function confirmDraft() {
  const nextActive = uniqueInOrder(state.draft.filter((registration) => byRegistration.has(registration)));
  const nextSet = new Set(nextActive);

  for (const registration of state.active) {
    if (nextSet.has(registration)) continue;
    if (state.dressedAircraft.includes(registration)) {
      state.retiredAircraftLoads.push(registration);
    }
    if (state.dressedHold.includes(registration)) {
      state.retiredHoldLoads.push(registration);
    }
  }

  state.active = nextActive;
  state.draft = nextActive;
  state.dressedAircraft = state.dressedAircraft.filter((registration) => nextSet.has(registration));
  state.dressedHold = state.dressedHold.filter((registration) => nextSet.has(registration));
  persist();
  render();
}

function render() {
  renderDraft();
  const hasActive = state.active.length > 0;
  elements.summarySection.classList.toggle("hidden", !hasActive);
  elements.trackingSection.classList.toggle("hidden", !hasActive);
  if (hasActive) {
    renderSummary();
    renderTracking();
  }
}

function renderDraft() {
  elements.draftList.replaceChildren();

  state.draft.forEach((registration, index) => {
    const fragment = elements.draftRowTemplate.content.cloneNode(true);
    const row = fragment.querySelector(".draft-row");
    const select = row.querySelector(".draft-select");
    const badge = row.querySelector(".badge");
    const deleteButton = row.querySelector(".icon-button");

    fillSelect(select, registration);
    setBadge(badge, registration);

    select.addEventListener("change", () => {
      state.draft[index] = select.value;
      state.draft = uniqueInOrder(state.draft);
      persist();
      render();
    });

    deleteButton.addEventListener("click", () => {
      state.draft = state.draft.filter((_, currentIndex) => currentIndex !== index);
      persist();
      render();
    });

    elements.draftList.appendChild(fragment);
  });

  elements.confirmButton.disabled = state.draft.length === 0;
}

function renderSummary() {
  const activeSummary = calculateTotal(state.active);
  const remainingSummary = calculateSingleLoads(remainingLoads());
  const retiredSummary = calculateSingleLoads([
    ...state.retiredAircraftLoads,
    ...state.retiredHoldLoads,
  ]);
  const realSummary = combine(activeSummary, retiredSummary);

  elements.aircraftCount.textContent = `Aviones confirmados: ${state.active.length}`;
  renderSummaryBlock(elements.totalBlankets, activeSummary.blankets, "mantas");
  renderSummaryBlock(elements.totalCages, activeSummary.cages, "jaulas");
  renderSummaryBlock(elements.remainingBlankets, remainingSummary.blankets, "mantas");
  renderSummaryBlock(elements.remainingCages, remainingSummary.cages, "jaulas");

  const hasRetired =
    retiredSummary.blankets.tourist > 0 ||
    retiredSummary.blankets.thick > 0 ||
    retiredSummary.blankets.toppers > 0;
  elements.changeImpact.classList.toggle("hidden", !hasRetired);
  if (hasRetired) {
    elements.changeImpact.textContent = [
      `Mantas ya usadas en salidas retiradas: ${retiredSummary.blankets.tourist} turista, ${retiredSummary.blankets.thick} gordas, ${retiredSummary.blankets.toppers} toppers`,
      `Total real de jornada: ${realSummary.blankets.tourist} turista, ${realSummary.blankets.thick} gordas, ${realSummary.blankets.toppers} toppers`,
      `Jaulas del total real: ${realSummary.cages.tourist} turista, ${realSummary.cages.thick} gordas, ${realSummary.cages.toppers} toppers`,
    ].join("\n");
  }
}

function renderSummaryBlock(container, values, suffix) {
  container.replaceChildren(
    pair("Turista", `${values.tourist} ${suffix}`),
    pair("Gordas", `${values.thick} ${suffix}`),
    pair("Toppers", `${values.toppers} ${suffix}`),
  );
}

function pair(label, value) {
  const fragment = document.createDocumentFragment();
  const term = document.createElement("dt");
  const definition = document.createElement("dd");
  term.textContent = label;
  definition.textContent = value;
  fragment.append(term, definition);
  return fragment;
}

function renderTracking() {
  elements.trackingList.replaceChildren();

  state.active.forEach((registration) => {
    const fragment = elements.trackingRowTemplate.content.cloneNode(true);
    const row = fragment.querySelector(".tracking-row");
    const title = row.querySelector(".tracking-registration");
    const badge = row.querySelector(".badge");
    const aircraftCheck = row.querySelector(".aircraft-check");
    const holdCheck = row.querySelector(".hold-check");

    title.textContent = registration;
    setBadge(badge, registration);
    aircraftCheck.checked = state.dressedAircraft.includes(registration);
    holdCheck.checked = state.dressedHold.includes(registration);
    row.classList.toggle("done", aircraftCheck.checked && holdCheck.checked);

    aircraftCheck.addEventListener("change", () => {
      state.dressedAircraft = toggleValue(state.dressedAircraft, registration, aircraftCheck.checked);
      persist();
      renderSummary();
      row.classList.toggle("done", aircraftCheck.checked && holdCheck.checked);
    });
    holdCheck.addEventListener("change", () => {
      state.dressedHold = toggleValue(state.dressedHold, registration, holdCheck.checked);
      persist();
      renderSummary();
      row.classList.toggle("done", aircraftCheck.checked && holdCheck.checked);
    });

    elements.trackingList.appendChild(fragment);
  });
}

function toggleValue(values, value, checked) {
  if (checked) return uniqueInOrder([...values, value]);
  return values.filter((item) => item !== value);
}

function setBadge(element, registration) {
  const category = categoryFor(registration);
  element.textContent = category;
  element.className = `badge ${badgeClass(category)}`;
}

function badgeClass(category) {
  if (category === "Premium") return "premium";
  if (category === "800") return "model-800";
  if (category === "900") return "model-900";
  return "unknown";
}

function categoryFor(registration) {
  if (PREMIUM.has(registration)) return "Premium";
  const aircraft = byRegistration.get(registration);
  if (!aircraft) return "No encontrada";
  if (aircraft.model === "787-8") return "800";
  if (aircraft.model === "787-9") return "900";
  return "No encontrada";
}

function requirementFor(registration) {
  const category = categoryFor(registration);
  if (category === "Premium") return { tourist: 30, thick: 10, toppers: 8 };
  if (category === "800") return { tourist: 29, thick: 9, toppers: 6 };
  if (category === "900") return { tourist: 32, thick: 11, toppers: 8 };
  return { tourist: 0, thick: 0, toppers: 0 };
}

function calculateTotal(registrations) {
  return calculateLoads(registrations, 2);
}

function calculateSingleLoads(registrations) {
  return calculateLoads(registrations, 1);
}

function calculateLoads(registrations, loadsPerRegistration) {
  const blankets = registrations.reduce(
    (totals, registration) => {
      const requirement = requirementFor(registration);
      totals.tourist += requirement.tourist * loadsPerRegistration;
      totals.thick += requirement.thick * loadsPerRegistration;
      totals.toppers += requirement.toppers * loadsPerRegistration;
      return totals;
    },
    { tourist: 0, thick: 0, toppers: 0 },
  );
  return { blankets, cages: cagesFor(blankets) };
}

function cagesFor(blankets) {
  return {
    tourist: Math.ceil(blankets.tourist / 16),
    thick: Math.ceil(blankets.thick / 8),
    toppers: Math.ceil(blankets.toppers / 20),
  };
}

function combine(first, second) {
  const blankets = {
    tourist: first.blankets.tourist + second.blankets.tourist,
    thick: first.blankets.thick + second.blankets.thick,
    toppers: first.blankets.toppers + second.blankets.toppers,
  };
  return { blankets, cages: cagesFor(blankets) };
}

function remainingLoads() {
  const remaining = [];
  for (const registration of state.active) {
    if (!state.dressedAircraft.includes(registration)) remaining.push(registration);
    if (!state.dressedHold.includes(registration)) remaining.push(registration);
  }
  return remaining;
}
