import { describe, expect, it } from "vitest";
import { newId, uuidv5 } from "../src/ids";
import { normalizeLegacyTag, tagLookupKeys } from "../src/tags";

describe("uuidv5", () => {
  it("matches the RFC 4122 reference vector", () => {
    // python: uuid.uuid5(uuid.NAMESPACE_DNS, "python.org")
    expect(uuidv5("python.org", "6ba7b810-9dad-11d1-80b4-00c04fd430c8")).toBe("886313e1-3b8a-5372-9b90-0c9aee199e5d");
  });

  it("is stable and distinct per legacy table and id", () => {
    expect(newId("inventory_assets", "42")).toBe(newId("inventory_assets", "42"));
    expect(newId("inventory_assets", "42")).not.toBe(newId("inventory_containers", "42"));
    expect(newId("inventory_assets", "42")).not.toBe(newId("inventory_assets", "43"));
    expect(newId("inventory_assets", "42")).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-5[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
  });
});

describe("normalizeLegacyTag", () => {
  it("turns dash-separated hex into an uppercase NFC uid", () => {
    expect(normalizeLegacyTag("04-46-88-0A-BF-2A-81")).toEqual({ value: "0446880ABF2A81", kind: "nfc" });
    expect(normalizeLegacyTag(" 04-46-88-0a-bf-2a-81 ")).toEqual({ value: "0446880ABF2A81", kind: "nfc" });
  });

  it("keeps numeric barcodes as barcodes, trimmed", () => {
    expect(normalizeLegacyTag(" 110164 ")).toEqual({ value: "110164", kind: "barcode" });
    expect(normalizeLegacyTag("SAV1")).toEqual({ value: "SAV1", kind: "barcode" });
  });

  it("does not mistake a short hex-looking value for an NFC uid", () => {
    expect(normalizeLegacyTag("12-34")).toEqual({ value: "12-34", kind: "barcode", downgraded: true });
  });

  it("returns null for blank input", () => {
    expect(normalizeLegacyTag("  ")).toBeNull();
    expect(normalizeLegacyTag(null)).toBeNull();
    expect(normalizeLegacyTag(undefined)).toBeNull();
  });

  it("offers both spellings when matching references", () => {
    expect(tagLookupKeys("04-46-88-0A-BF-2A-81")).toEqual(["04-46-88-0A-BF-2A-81", "0446880ABF2A81"]);
    expect(tagLookupKeys("110164")).toEqual(["110164"]);
    expect(tagLookupKeys("")).toEqual([]);
  });
});
