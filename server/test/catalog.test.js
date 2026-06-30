import test from "node:test";
import assert from "node:assert/strict";
import {
  classifyRegistration,
  normalizeRegistration,
  validateAndClassify,
} from "../src/catalog.js";

test("normalizes dashed and compact formats", () => {
  assert.equal(normalizeRegistration("EC-NFM"), "EC-NFM");
  assert.equal(normalizeRegistration("ecnfm"), "EC-NFM");
});

test("applies premium before model category", () => {
  assert.equal(classifyRegistration("EC-NXA"), "Premium");
  assert.equal(classifyRegistration("EC-MIG"), "800");
  assert.equal(classifyRegistration("EC-NFM"), "900");
});

test("rejects unknown registrations and removes duplicates", () => {
  assert.deepEqual(
    validateAndClassify([
      { registration: "ECNFM", observed_text: "ECNFM" },
      { registration: "EC-NFM", observed_text: "EC-NFM" },
      { registration: "EC-XXX", observed_text: "EC-XXX" },
    ]),
    [{ registration: "EC-NFM", category: "900", observed_text: "ECNFM" }],
  );
});
