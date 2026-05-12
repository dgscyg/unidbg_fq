import { resolve } from "node:path";

export interface AppConfig {
  baseUrl: string;
  timeoutMs: number;
  downloadConcurrency: number;
  outputDir: string;
}

const DEFAULT_BASE_URL = "http://127.0.0.1:9999";
const DEFAULT_TIMEOUT_MS = 30_000;
const DEFAULT_DOWNLOAD_CONCURRENCY = 5;

function parseIntegerEnv(
  name: string,
  rawValue: string | undefined,
  fallback: number,
  min: number,
  max: number,
): number {
  if (!rawValue?.trim()) {
    return fallback;
  }

  const parsed = Number.parseInt(rawValue, 10);
  if (!Number.isFinite(parsed) || parsed < min || parsed > max) {
    throw new Error(`${name} 必须是 ${min}-${max} 之间的整数`);
  }

  return parsed;
}

function normalizeBaseUrl(rawValue: string | undefined): string {
  const value = rawValue?.trim() || DEFAULT_BASE_URL;
  return value.replace(/\/+$/, "");
}

export function loadConfig(): AppConfig {
  return {
    baseUrl: normalizeBaseUrl(process.env.FQNOVEL_BASE_URL),
    timeoutMs: parseIntegerEnv(
      "FQNOVEL_TIMEOUT_MS",
      process.env.FQNOVEL_TIMEOUT_MS,
      DEFAULT_TIMEOUT_MS,
      1_000,
      300_000,
    ),
    downloadConcurrency: parseIntegerEnv(
      "FQNOVEL_DOWNLOAD_CONCURRENCY",
      process.env.FQNOVEL_DOWNLOAD_CONCURRENCY,
      DEFAULT_DOWNLOAD_CONCURRENCY,
      1,
      30,
    ),
    outputDir: process.env.FQNOVEL_OUTPUT_DIR?.trim()
      ? resolve(process.env.FQNOVEL_OUTPUT_DIR)
      : process.cwd(),
  };
}