import path from "node:path";
import { fileURLToPath } from "node:url";
import OpenAI from "openai";

const here = path.dirname(fileURLToPath(import.meta.url));
process.loadEnvFile(path.resolve(here, "../../.env.local"));

const client = new OpenAI({ apiKey: process.env.OPENAI_API_KEY });

try {
  const response = await client.responses.create({
    model: "gpt-5.5",
    input: "Reply with OK.",
    max_output_tokens: 16,
    store: false,
  });
  console.log(JSON.stringify({
    ok: true,
    model: response.model,
    status: response.status,
  }));
} catch (error) {
  console.log(JSON.stringify({
    ok: false,
    status: error?.status ?? null,
    code: error?.code ?? error?.error?.code ?? null,
    type: error?.type ?? error?.error?.type ?? null,
    message: error?.message ?? "Unknown API error",
  }));
  process.exitCode = 1;
}
