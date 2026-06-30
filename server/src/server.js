import path from "node:path";
import { fileURLToPath } from "node:url";
import crypto from "node:crypto";
import { existsSync } from "node:fs";

const here = path.dirname(fileURLToPath(import.meta.url));
const localEnvPath = path.resolve(here, "../../.env.local");
if (existsSync(localEnvPath)) {
  process.loadEnvFile?.(localEnvPath);
}

import express from "express";
import helmet from "helmet";
import rateLimit from "express-rate-limit";
import OpenAI from "openai";
import { allowedRegistrations, validateAndClassify } from "./catalog.js";

const port = Number(process.env.PORT ?? 8787);
const model = process.env.OPENAI_VISION_MODEL ?? "gpt-5.5";
if (!process.env.OPENAI_API_KEY) throw new Error("Falta OPENAI_API_KEY en el servidor.");

const openai = new OpenAI({ apiKey: process.env.OPENAI_API_KEY });
const app = express();
app.disable("x-powered-by");
app.use(helmet());
app.use(express.json({ limit: "16mb" }));
app.use("/api", rateLimit({
  windowMs: 10 * 60 * 1000,
  limit: 30,
  standardHeaders: "draft-7",
  legacyHeaders: false,
}));

const outputSchema = {
  type: "object",
  properties: {
    registrations: {
      type: "array",
      description: "All aircraft registrations visible in the sheet, in document order.",
      items: {
        type: "object",
        properties: {
          registration: { type: "string", enum: allowedRegistrations },
          observed_text: {
            type: "string",
            description: "Text as visually printed, for example EC-NFM or ECNFM.",
          },
        },
        required: ["registration", "observed_text"],
        additionalProperties: false,
      },
    },
    sheet_has_readable_text: { type: "boolean" },
  },
  required: ["registrations", "sheet_has_readable_text"],
  additionalProperties: false,
};

const extractionPrompt = [
  "Analyze this flight sheet image visually and extract every aircraft registration.",
  "A registration can be printed with a hyphen (EC-NFM) or without one (ECNFM); both represent EC-NFM.",
  "Inspect every row and column carefully. Do not stop after finding the first few.",
  "Return registrations in their visual order and do not duplicate them.",
  "Only choose a registration when it is visible in the image.",
  `Allowed registrations: ${allowedRegistrations.join(", ")}.`,
].join("\n");

app.get("/health", (_request, response) => response.json({ ok: true, model }));

app.post("/api/scan", async (request, response) => {
  const requestId = crypto.randomUUID();
  const image = request.body?.image;
  if (typeof image !== "string" || !/^data:image\/(jpeg|jpg|png|webp);base64,/i.test(image)) {
    return response.status(400).json({ error: "invalid_image", request_id: requestId });
  }

  try {
    const aiResponse = await openai.responses.create({
      model,
      store: false,
      reasoning: { effort: "low" },
      input: [{
        role: "user",
        content: [
          { type: "input_text", text: extractionPrompt },
          { type: "input_image", image_url: image, detail: "original" },
        ],
      }],
      text: {
        format: {
          type: "json_schema",
          name: "flight_sheet_registrations",
          strict: true,
          schema: outputSchema,
        },
      },
    });

    if (!aiResponse.output_text) throw new Error("Respuesta estructurada vacía.");
    const parsed = JSON.parse(aiResponse.output_text);
    return response.json({
      registrations: validateAndClassify(parsed.registrations ?? []),
      sheet_has_readable_text: Boolean(parsed.sheet_has_readable_text),
      request_id: requestId,
    });
  } catch (error) {
    const status = Number(error?.status);
    const code = status === 429 ? "ai_rate_limited" : "ai_unavailable";
    console.error(JSON.stringify({
      request_id: requestId,
      code,
      status: Number.isFinite(status) ? status : null,
      message: error?.message ?? "Unknown OpenAI error",
    }));
    return response.status(status === 429 ? 429 : 502).json({
      error: code,
      request_id: requestId,
    });
  }
});

app.use((_request, response) => response.status(404).json({ error: "not_found" }));
app.listen(port, "0.0.0.0", () => {
  console.log(`Flight sheet API listening on port ${port} with ${model}`);
});
