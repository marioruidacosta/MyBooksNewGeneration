// Biblioteca Nacional de Portugal (PORBASE) URN resolver, ported from the app's Kotlin BnpClient.
// A missing record is HTTP 200 with <urn-response><error>Registo inexistente</error></urn-response>.

export interface BnpBook {
  isbn: string;
  title: string;
  authors: string[];
  publisher: string | null;
  year: number | null;
  pages: number | null;
  coverUrl: string | null;
}

const ENTITIES: Record<string, string> = { amp: "&", lt: "<", gt: ">", quot: '"', apos: "'" };

function decodeEntities(text: string): string {
  return text.replace(/&(#x[0-9a-fA-F]+|#\d+|[a-zA-Z]+);/g, (whole, name: string) => {
    if (name.startsWith("#x")) return String.fromCodePoint(parseInt(name.slice(2), 16));
    if (name.startsWith("#")) return String.fromCodePoint(parseInt(name.slice(1), 10));
    return ENTITIES[name] ?? whole;
  });
}

/** Control characters out, whitespace collapsed (the app's cleanText). */
function cleanText(text: string): string {
  // deno-lint-ignore no-control-regex
  return text.replace(/[\u0000-\u001f\u007f-\u009f]/g, "").replace(/\s+/g, " ").trim();
}

/** Removes the "?" PORBASE puts before words to skip when sorting: "?A ?violência" -> "A violência". */
export function bnpTitle(title: string): string {
  return title.replace(/(^|\s)\?(?=\S)/g, "$1").trim();
}

/** "Cossery, Albert, 1913-2008" -> "Albert Cossery"; life dates are dropped. */
export function bnpPersonName(heading: string): string {
  const parts = heading.split(",").map((p) => p.trim()).filter((p) => p !== "" && !/\d/.test(p));
  if (parts.length === 0) return heading.trim();
  if (parts.length === 1) return parts[0];
  return `${parts[1]} ${parts[0]}`;
}

/** "181, [3] p., 21 cm" -> 181; "[200] p." -> 200. */
function pagesFrom(format: string): number | null {
  const at = format.indexOf(" p.");
  if (at <= 0) return null;
  const digits = /\d+/.exec(format.slice(0, at));
  return digits ? parseInt(digits[0], 10) : null;
}

function yearFrom(date: string | undefined): number | null {
  const digits = date ? /\d{4}/.exec(date) : null;
  return digits ? parseInt(digits[0], 10) : null;
}

function httpsUrl(url: string | undefined): string | null {
  return url ? url.replace(/^http:\/\//, "https://") : null;
}

/** The <dc:*> element texts by local name; null when the root isn't <dc> (an error record). Throws on anything else. */
function parseDublinCore(xml: string): Record<string, string[]> | null {
  const body = xml.replace(/^﻿/, "").replace(/<\?xml[^>]*\?>/, "").trim();
  const root = /^<([A-Za-z][\w:.-]*)/.exec(body)?.[1];
  if (root === "urn-response") return null;
  if (root !== "dc" || !body.endsWith("</dc>")) throw new Error("Not a Dublin Core record");
  const values: Record<string, string[]> = {};
  for (const m of body.matchAll(/<dc:([\w-]+)(?:\s[^>]*)?(?:\/>|>([\s\S]*?)<\/dc:\1>)/g)) {
    (values[m[1]] ??= []).push(decodeEntities(m[2] ?? ""));
  }
  return values;
}

/** The book in a BNP record, or null when BNP has no such record. Throws when the response isn't BNP XML. */
export function parseBnp(xml: string, isbn: string): BnpBook | null {
  const record = parseDublinCore(xml);
  if (!record) return null;
  const values = (name: string) => (record[name] ?? []).map(cleanText).filter((v) => v !== "");
  const title = bnpTitle(values("title")[0] ?? "");
  if (title === "") return null;
  return {
    isbn,
    title,
    authors: values("creator").map(bnpPersonName),
    // dc:publisher lists the publisher first, then the place of publication.
    publisher: values("publisher")[0] ?? null,
    year: yearFrom(values("date")[0]),
    pages: values("format").map(pagesFrom).find((p) => p !== null) ?? null,
    coverUrl: httpsUrl(values("identifier").find((i) => i.includes("/ImagesBN/"))),
  };
}
