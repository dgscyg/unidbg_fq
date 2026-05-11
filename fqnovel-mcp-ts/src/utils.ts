import { z } from "zod";

export function numericIdSchema(fieldName: string) {
  return z
    .string()
    .trim()
    .regex(/^\d+$/, `${fieldName} 必须是纯数字字符串`);
}

export function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value));
}

export function jsonText(value: unknown): string {
  return JSON.stringify(value, null, 2);
}

export function ensureStructuredContent(value: unknown): Record<string, unknown> {
  if (value && typeof value === "object" && !Array.isArray(value)) {
    return value as Record<string, unknown>;
  }

  return { value };
}