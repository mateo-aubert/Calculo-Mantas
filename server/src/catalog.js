import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import path from "node:path";

const here = path.dirname(fileURLToPath(import.meta.url));
const catalogPath = path.resolve(here, "../../matriculas_modelos.json");
const rawCatalog = JSON.parse(readFileSync(catalogPath, "utf8"));

export const premiumRegistrations = new Set(["EC-NXA", "EC-NVZ", "EC-NZG"]);
export const aircraftByRegistration = new Map(
  rawCatalog.map((item) => [
    normalizeRegistration(item.matricula),
    { registration: normalizeRegistration(item.matricula), model: item.modelo },
  ]),
);
export const allowedRegistrations = [...aircraftByRegistration.keys()];

export function normalizeRegistration(value) {
  const compact = String(value ?? "").trim().toUpperCase().replace(/[^A-Z0-9]/g, "");
  return /^EC[A-Z0-9]{3}$/.test(compact)
    ? `EC-${compact.slice(2)}`
    : String(value ?? "").trim().toUpperCase();
}

export function classifyRegistration(value) {
  const registration = normalizeRegistration(value);
  if (premiumRegistrations.has(registration)) return "Premium";
  const aircraft = aircraftByRegistration.get(registration);
  if (!aircraft) return null;
  if (aircraft.model === "787-8") return "800";
  if (aircraft.model === "787-9") return "900";
  return null;
}

export function validateAndClassify(registrations) {
  const seen = new Set();
  const result = [];
  for (const candidate of registrations) {
    const registration = normalizeRegistration(candidate.registration);
    if (!aircraftByRegistration.has(registration) || seen.has(registration)) continue;
    seen.add(registration);
    result.push({
      registration,
      category: classifyRegistration(registration),
      observed_text: String(candidate.observed_text ?? ""),
    });
  }
  return result;
}
