// The lookup request handler. Everything it touches (fetch, env, the JWT check) is injected, so the tests run offline.
import { parseBnp } from "./bnp.ts";
import { googleUrl, mapGoogle } from "./google.ts";

export interface Deps {
  fetch: (input: string, signal?: AbortSignal) => Promise<Response>;
  env: (name: string) => string | undefined;
  /** True when the bearer token belongs to a signed-in user. */
  verifyJwt: (token: string) => Promise<boolean>;
  /** Upstream timeout in ms (default 8000). */
  timeoutMs?: number;
}

const DEFAULT_TIMEOUT_MS = 8000;

const ALLOWED_ORIGINS = ["https://marioruidacosta.github.io", "http://localhost:5173"];

function corsHeaders(req: Request): Record<string, string> {
  const origin = req.headers.get("Origin");
  const headers: Record<string, string> = { Vary: "Origin" };
  if (origin && ALLOWED_ORIGINS.includes(origin)) {
    headers["Access-Control-Allow-Origin"] = origin;
    headers["Access-Control-Allow-Headers"] = "authorization, apikey, x-client-info, content-type";
    headers["Access-Control-Allow-Methods"] = "GET, OPTIONS";
  }
  return headers;
}

export async function handle(req: Request, deps: Deps): Promise<Response> {
  const cors = corsHeaders(req);
  const json = (body: unknown, status = 200) =>
    new Response(JSON.stringify(body), { status, headers: { ...cors, "Content-Type": "application/json" } });

  if (req.method === "OPTIONS") return new Response(null, { status: 204, headers: cors });
  if (req.method !== "GET") return json({ error: "Method not allowed" }, 405);

  const token = /^Bearer\s+(.+)$/i.exec(req.headers.get("Authorization") ?? "")?.[1];
  let signedIn = false;
  try {
    signedIn = !!token && (await deps.verifyJwt(token));
  } catch {
    // Auth being down is answered like a bad token, with CORS so the browser can read it.
  }
  if (!signedIn) return json({ error: "Unauthorized" }, 401);

  const upstream = (url: string) => deps.fetch(url, AbortSignal.timeout(deps.timeoutMs ?? DEFAULT_TIMEOUT_MS));

  const params = new URL(req.url).searchParams;
  try {
    switch (params.get("source")) {
      case "bnp": {
        const isbn = (params.get("isbn") ?? "").replace(/[\s-]/g, "").toUpperCase();
        if (!/^\d{9}[\dX]$|^\d{13}$/.test(isbn)) return json({ error: "A valid isbn is required" }, 400);
        const res = await upstream(`https://urn.porbase.org/isbn/dc/xml?id=${isbn}`);
        if (!res.ok) {
          await res.body?.cancel();
          return json({ error: "BNP is unavailable" }, 502);
        }
        const book = parseBnp(await res.text(), isbn);
        return json(book ? { found: true, book } : { found: false });
      }
      case "google": {
        const q = (params.get("q") ?? "").trim();
        if (q === "") return json({ error: "q is required" }, 400);
        const res = await upstream(googleUrl(q, deps.env("GOOGLE_BOOKS_API_KEY")));
        if (!res.ok) {
          await res.body?.cancel();
          return json({ error: "Google Books is unavailable" }, 502);
        }
        return json({ results: mapGoogle(await res.text()) });
      }
      default:
        return json({ error: "source must be bnp or google" }, 400);
    }
  } catch (e) {
    if (e instanceof DOMException && (e.name === "TimeoutError" || e.name === "AbortError")) {
      return json({ error: "Upstream timed out" }, 504);
    }
    // An upstream answered with something that isn't what it should be (or the network failed).
    return json({ error: "Lookup failed" }, 502);
  }
}
