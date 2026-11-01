/** Mirrors private.normalize_tag_value in the database (supabase/migrations/20261101090000_core_schema.sql). */
export type TagKind = "nfc" | "barcode";

export interface NormalizedTag {
  value: string;
  kind: TagKind;
  /** True when the legacy value looked like an NFC UID but could not be stored as one. */
  downgraded?: boolean;
}

const DASH_HEX = /^([0-9A-Fa-f]{2}[-:])+[0-9A-Fa-f]{2}$/;
const NFC_OK = /^([0-9A-F]{2}){4,}$/;

/**
 * Legacy ids are a mix of NFC UIDs written as dash-separated hex pairs (`04-46-88-0A-BF-2A-81`) and plain
 * numeric barcodes (`110164`). Old labels must keep working, so both stay scannable.
 */
export function normalizeLegacyTag(raw: string | null | undefined): NormalizedTag | null {
  const trimmed = (raw ?? "").trim();
  if (trimmed === "") return null;
  if (DASH_HEX.test(trimmed)) {
    const hex = trimmed.replace(/[-:]/g, "").toUpperCase();
    if (NFC_OK.test(hex)) return { value: hex, kind: "nfc" };
    // Too short for a real UID (fewer than 4 bytes): keep it as text so the database accepts it.
    return { value: trimmed, kind: "barcode", downgraded: true };
  }
  return { value: trimmed, kind: "barcode" };
}

/** Keys used to match references from other tables: the raw trimmed text and the normalised value. */
export function tagLookupKeys(raw: string | null | undefined): string[] {
  const t = normalizeLegacyTag(raw);
  if (!t) return [];
  const trimmed = (raw ?? "").trim();
  return t.value === trimmed ? [trimmed] : [trimmed, t.value];
}
