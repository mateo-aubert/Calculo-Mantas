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
let scanImage = null;
let scanCrop = null;
let draggingCrop = false;
let ocrSessionPromise = null;

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
  imageInput: document.querySelector("#imageInput"),
  pickImageButton: document.querySelector("#pickImageButton"),
  ocrStatus: document.querySelector("#ocrStatus"),
  scanWorkspace: document.querySelector("#scanWorkspace"),
  scanCanvas: document.querySelector("#scanCanvas"),
  readColumnButton: document.querySelector("#readColumnButton"),
  clearImageButton: document.querySelector("#clearImageButton"),
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

  elements.pickImageButton.addEventListener("click", () => {
    elements.imageInput.click();
  });

  elements.imageInput.addEventListener("change", () => {
    const file = elements.imageInput.files?.[0];
    if (file) loadImageFile(file);
  });

  elements.clearImageButton.addEventListener("click", () => {
    scanImage = null;
    scanCrop = null;
    elements.imageInput.value = "";
    elements.scanWorkspace.classList.add("hidden");
    setOcrStatus("Foto quitada. Puedes importar otra hoja.");
  });

  elements.readColumnButton.addEventListener("click", () => {
    readSelectedColumn();
  });

  elements.scanCanvas.addEventListener("pointerdown", startCropDrag);
  elements.scanCanvas.addEventListener("pointermove", moveCropDrag);
  elements.scanCanvas.addEventListener("pointerup", endCropDrag);
  elements.scanCanvas.addEventListener("pointercancel", endCropDrag);
}

async function loadImageFile(file) {
  setOcrStatus("Preparando fotografía…");
  try {
    const bitmap = await createImageBitmap(file);
    scanImage = bitmap;
    scanCrop = defaultCrop(bitmap.width, bitmap.height);
    elements.scanWorkspace.classList.remove("hidden");
    drawScanCanvas();
    setOcrStatus("Foto lista. Marca la columna de matrículas y pulsa “Leer matrículas”.");
  } catch {
    setOcrStatus("No se pudo abrir la imagen. Prueba con otra fotografía.");
  }
}

function defaultCrop(width, height) {
  return {
    x: Math.round(width * 0.36),
    y: Math.round(height * 0.12),
    width: Math.round(width * 0.26),
    height: Math.round(height * 0.76),
  };
}

function drawScanCanvas() {
  if (!scanImage || !scanCrop) return;
  const canvas = elements.scanCanvas;
  const maxWidth = Math.min(980, document.body.clientWidth - 28);
  const scale = Math.min(1, maxWidth / scanImage.width);
  canvas.width = Math.round(scanImage.width * scale);
  canvas.height = Math.round(scanImage.height * scale);
  const context = canvas.getContext("2d");
  context.clearRect(0, 0, canvas.width, canvas.height);
  context.drawImage(scanImage, 0, 0, canvas.width, canvas.height);

  const rect = toCanvasRect(scanCrop);
  context.save();
  context.fillStyle = "rgba(0, 0, 0, 0.52)";
  context.fillRect(0, 0, canvas.width, canvas.height);
  context.clearRect(rect.x, rect.y, rect.width, rect.height);
  context.strokeStyle = "#FFFFFF";
  context.lineWidth = 3;
  context.strokeRect(rect.x, rect.y, rect.width, rect.height);
  context.fillStyle = "#0B75C9";
  context.fillRect(rect.x + rect.width - 18, rect.y + rect.height - 18, 18, 18);
  context.restore();
}

function toCanvasRect(rect) {
  const canvas = elements.scanCanvas;
  const scaleX = canvas.width / scanImage.width;
  const scaleY = canvas.height / scanImage.height;
  return {
    x: rect.x * scaleX,
    y: rect.y * scaleY,
    width: rect.width * scaleX,
    height: rect.height * scaleY,
  };
}

function canvasPoint(event) {
  const bounds = elements.scanCanvas.getBoundingClientRect();
  return {
    x: ((event.clientX - bounds.left) / bounds.width) * scanImage.width,
    y: ((event.clientY - bounds.top) / bounds.height) * scanImage.height,
  };
}

function startCropDrag(event) {
  if (!scanImage) return;
  draggingCrop = true;
  elements.scanCanvas.setPointerCapture(event.pointerId);
  const start = canvasPoint(event);
  scanCrop = { x: start.x, y: start.y, width: 1, height: 1 };
  drawScanCanvas();
}

function moveCropDrag(event) {
  if (!draggingCrop || !scanImage || !scanCrop) return;
  const point = canvasPoint(event);
  const x1 = Math.max(0, Math.min(scanImage.width, scanCrop.x));
  const y1 = Math.max(0, Math.min(scanImage.height, scanCrop.y));
  const x2 = Math.max(0, Math.min(scanImage.width, point.x));
  const y2 = Math.max(0, Math.min(scanImage.height, point.y));
  scanCrop = {
    x: Math.min(x1, x2),
    y: Math.min(y1, y2),
    width: Math.max(1, Math.abs(x2 - x1)),
    height: Math.max(1, Math.abs(y2 - y1)),
  };
  drawScanCanvas();
}

function endCropDrag(event) {
  if (!draggingCrop) return;
  draggingCrop = false;
  try {
    elements.scanCanvas.releasePointerCapture(event.pointerId);
  } catch {
    // Some browsers release capture automatically.
  }
  drawScanCanvas();
}

async function readSelectedColumn() {
  if (!scanImage || !scanCrop || scanCrop.width < 20 || scanCrop.height < 20) {
    setOcrStatus("Marca un recorte válido sobre la columna de matrículas.");
    return;
  }

  elements.readColumnButton.disabled = true;
  setOcrStatus("Cargando OCR local… puede tardar unos segundos la primera vez.");
  try {
    const session = await getOcrSession();
    const columnCanvas = cropImageToCanvas(scanImage, scanCrop);
    const rows = segmentRows(columnCanvas);
    if (rows.length === 0) {
      setOcrStatus("No encontré filas claras en el recorte. Ajusta mejor la columna o añade las matrículas manualmente.");
      return;
    }

    setOcrStatus(`Analizando ${rows.length} filas…`);
    const detected = [];
    for (const row of rows) {
      const registration = await recognizeRow(session, columnCanvas, row);
      if (registration && !detected.includes(registration)) {
        detected.push(registration);
      }
    }

    if (detected.length === 0) {
      setOcrStatus("El OCR no pudo confirmar matrículas. Puedes pegarlas o añadirlas manualmente.");
      return;
    }

    state.draft = detected;
    persist();
    render();
    setOcrStatus(`Lectura completada: ${detected.join(", ")}. Revisa y confirma cambios de salida.`);
    document.querySelector("#draftList")?.scrollIntoView({ behavior: "smooth", block: "start" });
  } catch (error) {
    console.error(error);
    setOcrStatus("No se pudo ejecutar el OCR web en este navegador. La parte manual sigue disponible.");
  } finally {
    elements.readColumnButton.disabled = false;
  }
}

function setOcrStatus(message) {
  elements.ocrStatus.textContent = message;
}

async function getOcrSession() {
  if (ocrSessionPromise) return ocrSessionPromise;
  ocrSessionPromise = (async () => {
    if (!globalThis.ort) {
      throw new Error("ONNX Runtime Web no está cargado.");
    }
    globalThis.ort.env.wasm.wasmPaths =
      "https://cdn.jsdelivr.net/npm/onnxruntime-web@1.20.1/dist/";
    return globalThis.ort.InferenceSession.create("models/ppocrv6_tiny_rec.onnx", {
      executionProviders: ["wasm"],
      graphOptimizationLevel: "all",
    });
  })();
  return ocrSessionPromise;
}

function cropImageToCanvas(image, crop) {
  const canvas = document.createElement("canvas");
  canvas.width = Math.max(1, Math.round(crop.width));
  canvas.height = Math.max(1, Math.round(crop.height));
  const context = canvas.getContext("2d", { willReadFrequently: true });
  context.drawImage(
    image,
    crop.x,
    crop.y,
    crop.width,
    crop.height,
    0,
    0,
    canvas.width,
    canvas.height,
  );
  return canvas;
}

function segmentRows(canvas) {
  const context = canvas.getContext("2d", { willReadFrequently: true });
  const { data, width, height } = context.getImageData(0, 0, canvas.width, canvas.height);
  const projection = new Array(height).fill(0);

  for (let y = 0; y < height; y += 1) {
    let count = 0;
    for (let x = 0; x < width; x += 1) {
      const offset = (y * width + x) * 4;
      const gray = data[offset] * 0.299 + data[offset + 1] * 0.587 + data[offset + 2] * 0.114;
      if (gray < 215) count += 1;
    }
    projection[y] = count > width * 0.78 ? 0 : count;
  }

  const smoothed = projection.map((_, index) => {
    let total = 0;
    let samples = 0;
    for (let delta = -2; delta <= 2; delta += 1) {
      const y = index + delta;
      if (y >= 0 && y < height) {
        total += projection[y];
        samples += 1;
      }
    }
    return total / samples;
  });

  const threshold = Math.max(3, width * 0.012);
  const runs = [];
  let start = -1;
  for (let y = 0; y < height; y += 1) {
    if (smoothed[y] >= threshold && start < 0) start = y;
    if ((smoothed[y] < threshold || y === height - 1) && start >= 0) {
      const end = smoothed[y] < threshold ? y - 1 : y;
      if (end - start >= 5) runs.push({ y: start, height: end - start + 1 });
      start = -1;
    }
  }

  const merged = [];
  for (const run of runs) {
    const previous = merged[merged.length - 1];
    if (previous && run.y - (previous.y + previous.height) < 5) {
      previous.height = run.y + run.height - previous.y;
    } else {
      merged.push({ ...run });
    }
  }

  return merged
    .map((run) => ({
      y: Math.max(0, run.y - 10),
      height: Math.min(height - Math.max(0, run.y - 10), run.height + 20),
    }))
    .filter((run) => run.height >= 10);
}

async function recognizeRow(session, columnCanvas, row) {
  const rowCanvas = cropRowByInk(columnCanvas, row);
  if (!rowCanvas) return null;
  const { tensor, width } = rowToTensor(rowCanvas);
  const inputName = session.inputNames[0];
  const outputName = session.outputNames[0];
  const outputs = await session.run({ [inputName]: tensor });
  const output = outputs[outputName];
  const winner = bestCatalogCandidate(output.data, output.dims);
  return winner?.registration ?? null;
}

function cropRowByInk(canvas, row) {
  const context = canvas.getContext("2d", { willReadFrequently: true });
  const image = context.getImageData(0, row.y, canvas.width, row.height);
  let minX = canvas.width;
  let maxX = 0;
  for (let y = 0; y < row.height; y += 1) {
    for (let x = 0; x < canvas.width; x += 1) {
      const offset = (y * canvas.width + x) * 4;
      const gray =
        image.data[offset] * 0.299 + image.data[offset + 1] * 0.587 + image.data[offset + 2] * 0.114;
      if (gray < 215) {
        minX = Math.min(minX, x);
        maxX = Math.max(maxX, x);
      }
    }
  }
  if (maxX <= minX) return null;
  minX = Math.max(0, minX - 8);
  maxX = Math.min(canvas.width - 1, maxX + 8);

  const crop = document.createElement("canvas");
  crop.width = maxX - minX + 1;
  crop.height = row.height;
  crop
    .getContext("2d", { willReadFrequently: true })
    .drawImage(canvas, minX, row.y, crop.width, crop.height, 0, 0, crop.width, crop.height);
  return crop;
}

function rowToTensor(rowCanvas) {
  const targetHeight = 48;
  const targetWidth = Math.max(32, Math.min(640, Math.round((rowCanvas.width * targetHeight) / rowCanvas.height)));
  const canvas = document.createElement("canvas");
  canvas.width = targetWidth;
  canvas.height = targetHeight;
  const context = canvas.getContext("2d", { willReadFrequently: true });
  context.fillStyle = "#ffffff";
  context.fillRect(0, 0, canvas.width, canvas.height);
  context.drawImage(rowCanvas, 0, 0, targetWidth, targetHeight);
  const { data } = context.getImageData(0, 0, targetWidth, targetHeight);
  const input = new Float32Array(1 * 3 * targetHeight * targetWidth);
  const plane = targetHeight * targetWidth;

  for (let y = 0; y < targetHeight; y += 1) {
    for (let x = 0; x < targetWidth; x += 1) {
      const pixel = (y * targetWidth + x) * 4;
      const index = y * targetWidth + x;
      input[index] = data[pixel + 2] / 127.5 - 1; // B
      input[plane + index] = data[pixel + 1] / 127.5 - 1; // G
      input[plane * 2 + index] = data[pixel] / 127.5 - 1; // R
    }
  }

  return {
    tensor: new globalThis.ort.Tensor("float32", input, [1, 3, targetHeight, targetWidth]),
    width: targetWidth,
  };
}

function bestCatalogCandidate(logits, dims) {
  const timeSteps = dims[1];
  const classes = dims[2];
  const candidates = CATALOG.map((item) => normalizeRegistration(item.registration).replace("-", ""));
  const scores = candidates.map((compact) => ({
    compact,
    registration: `EC-${compact.slice(2)}`,
    score: ctcScore(logits, timeSteps, classes, compact),
  }));
  scores.sort((a, b) => b.score - a.score);
  const best = scores[0];
  const second = scores[1];
  if (!best || !Number.isFinite(best.score)) return null;
  if (second && best.score - second.score < 0.18) return null;
  return best;
}

function ctcScore(logits, timeSteps, classes, compact) {
  const labels = [];
  for (const char of compact) labels.push(charIndex(char));
  const extended = [0];
  for (const label of labels) extended.push(label, 0);

  let previous = new Float64Array(extended.length).fill(Number.NEGATIVE_INFINITY);
  previous[0] = logProb(logits, 0, classes, extended[0]);
  if (extended.length > 1) previous[1] = logProb(logits, 0, classes, extended[1]);

  for (let time = 1; time < timeSteps; time += 1) {
    const current = new Float64Array(extended.length).fill(Number.NEGATIVE_INFINITY);
    for (let stateIndex = 0; stateIndex < extended.length; stateIndex += 1) {
      let value = previous[stateIndex];
      if (stateIndex > 0) value = logAdd(value, previous[stateIndex - 1]);
      if (
        stateIndex > 1 &&
        extended[stateIndex] !== 0 &&
        extended[stateIndex] !== extended[stateIndex - 2]
      ) {
        value = logAdd(value, previous[stateIndex - 2]);
      }
      current[stateIndex] = value + logProb(logits, time, classes, extended[stateIndex]);
    }
    previous = current;
  }

  return logAdd(previous[extended.length - 1], previous[extended.length - 2]);
}

function charIndex(char) {
  if (char >= "0" && char <= "9") return 33 + (char.charCodeAt(0) - 48);
  if (char >= "A" && char <= "Z") return 43 + (char.charCodeAt(0) - 65);
  return 0;
}

function logProb(logits, time, classes, classIndex) {
  const offset = time * classes;
  let max = Number.NEGATIVE_INFINITY;
  for (let index = 0; index < classes; index += 1) {
    const value = logits[offset + index];
    if (value > max) max = value;
  }
  let sum = 0;
  for (let index = 0; index < classes; index += 1) {
    sum += Math.exp(logits[offset + index] - max);
  }
  return logits[offset + classIndex] - (max + Math.log(sum));
}

function logAdd(a, b) {
  if (!Number.isFinite(a)) return b;
  if (!Number.isFinite(b)) return a;
  const max = Math.max(a, b);
  return max + Math.log(Math.exp(a - max) + Math.exp(b - max));
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
