import { createHash } from "node:crypto";

/** Namespace for every id this importer derives. Never change it: re-runs rely on identical ids. */
export const IMPORT_NAMESPACE = "6f1d3c52-9a8e-4b1f-8f3a-2c7e5d9b0a41";

/** RFC 4122 version 5 (SHA-1, name based) UUID. */
export function uuidv5(name: string, namespace: string = IMPORT_NAMESPACE): string {
  const ns = Buffer.from(namespace.replace(/-/g, ""), "hex");
  const hash = createHash("sha1").update(ns).update(name, "utf8").digest();
  const b = Buffer.from(hash.subarray(0, 16));
  b[6] = (b[6]! & 0x0f) | 0x50;
  b[8] = (b[8]! & 0x3f) | 0x80;
  const h = b.toString("hex");
  return `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20, 32)}`;
}

/** The id a legacy row gets in the new database: stable per (legacy table, legacy id). */
export function newId(legacyTable: string, legacyId: string): string {
  return uuidv5(`${legacyTable}:${legacyId}`);
}
