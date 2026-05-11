import type { CallToolResult } from "@modelcontextprotocol/sdk/types.js";

interface ErrorContext {
  endpoint?: string;
  status?: number;
  code?: number;
}

export class FqNovelApiError extends Error {
  readonly endpoint?: string;
  readonly status?: number;
  readonly code?: number;

  constructor(message: string, context: ErrorContext = {}) {
    super(message);
    this.name = "FqNovelApiError";
    this.endpoint = context.endpoint;
    this.status = context.status;
    this.code = context.code;
  }
}

export interface SerializedError {
  name: string;
  message: string;
  endpoint?: string;
  status?: number;
  code?: number;
}

export function serializeError(error: unknown): SerializedError {
  if (error instanceof FqNovelApiError) {
    return {
      name: error.name,
      message: error.message,
      endpoint: error.endpoint,
      status: error.status,
      code: error.code,
    };
  }

  if (error instanceof Error) {
    return {
      name: error.name,
      message: error.message,
    };
  }

  return {
    name: "UnknownError",
    message: typeof error === "string" ? error : "发生未知错误",
  };
}

export function createToolErrorResult(error: unknown): CallToolResult {
  const serialized = serializeError(error);
  const lines = [
    `${serialized.name}: ${serialized.message}`,
    serialized.endpoint ? `endpoint: ${serialized.endpoint}` : undefined,
    serialized.status ? `status: ${serialized.status}` : undefined,
    serialized.code !== undefined ? `code: ${serialized.code}` : undefined,
  ].filter(Boolean);

  return {
    isError: true,
    content: [
      {
        type: "text",
        text: lines.join("\n"),
      },
    ],
    structuredContent: {
      error: serialized,
    },
  };
}