// Run: deno test --allow-read supabase/functions/lookup
// The fixtures are recorded responses (BNP: the "Tinta e sangue" record); nothing here touches the network.
import { assertEquals } from "jsr:@std/assert@1";
import { bnpPersonName, bnpTitle, parseBnp } from "./bnp.ts";
import { mapGoogle } from "./google.ts";
import { type Deps, handle } from "./handler.ts";

const fixture = (name: string) => Deno.readTextFileSync(new URL(`./fixtures/${name}`, import.meta.url));

const PAGES = "https://marioruidacosta.github.io";
const FUNCTION_URL = "https://project.supabase.co/functions/v1/lookup";

/** Deps whose fetch replays a fixture and records the URL it was asked for. */
function deps(body: string, init: ResponseInit = {}, valid = true) {
  const calls: string[] = [];
  const d: Deps = {
    fetch: (input) => {
      calls.push(String(input));
      return Promise.resolve(new Response(body, init));
    },
    env: (name) => (name === "GOOGLE_BOOKS_API_KEY" ? "test-key" : undefined),
    verifyJwt: (token) => Promise.resolve(valid && token === "good-token"),
  };
  return { d, calls };
}

function request(query: string, headers: Record<string, string> = { Authorization: "Bearer good-token" }, method = "GET") {
  return new Request(`${FUNCTION_URL}?${query}`, { method, headers });
}

Deno.test("bnpRecordParses", () => {
  const book = parseBnp(fixture("bnp_tinta_e_sangue.xml"), "9789722089876");
  assertEquals(book, {
    isbn: "9789722089876",
    title: "Tinta e sangue",
    authors: ["Kang Han"],
    publisher: "Dom Quixote",
    year: 2026,
    pages: 367,
    coverUrl: "https://rnod.bnportugal.gov.pt/ImagesBN/winlibimg.aspx?skey=&doc=2293592&img=227634&save=true",
  });
});

Deno.test("bnpRecordsMatchTheApp", () => {
  const found = parseBnp(fixture("bnp_found.xml"), "9789726084884")!;
  assertEquals(found.title, "A violência e o escárnio");
  assertEquals(found.authors, ["Albert Cossery"]);
  assertEquals(found.publisher, "Antígona");
  assertEquals(found.year, 2026);
  assertEquals(found.pages, 181);
  const middlemarch = parseBnp(fixture("bnp_middlemarch.xml"), "9789896412630")!;
  assertEquals(middlemarch.title, "Middlemarch: um estudo da vida de província");
  assertEquals(middlemarch.authors, ["George Eliot"]);
  assertEquals(middlemarch.publisher, "Relógio d'Água");
  assertEquals(middlemarch.year, 2011);
  assertEquals(middlemarch.pages, 809);
});

Deno.test("bnpNotFound", async () => {
  assertEquals(parseBnp(fixture("bnp_not_found.xml"), "9780000000002"), null);
  const { d } = deps(fixture("bnp_not_found.xml"), { status: 200 });
  const res = await handle(request("source=bnp&isbn=9780000000002"), d);
  assertEquals(res.status, 200);
  assertEquals(await res.json(), { found: false });
});

Deno.test("bnpPersonNameInvertsAndDropsLifeDates", () => {
  assertEquals(bnpPersonName("Cossery, Albert, 1913-2008"), "Albert Cossery");
  assertEquals(bnpPersonName("Eliot, George"), "George Eliot");
  assertEquals(bnpPersonName("Pereira, Miguel Serras, 1949-"), "Miguel Serras Pereira");
  assertEquals(bnpPersonName("Platão, 427-347 a.C."), "Platão");
  assertEquals(bnpPersonName("Camões"), "Camões");
});

Deno.test("bnpTitleDropsNonSortMarkersOnly", () => {
  assertEquals(bnpTitle("?A ?violência e o escárnio"), "A violência e o escárnio");
  assertEquals(bnpTitle("Porquê?"), "Porquê?");
  assertEquals(bnpTitle("Quem? Eu?"), "Quem? Eu?");
});

Deno.test("bnpRecordWithoutImageHasNoCover", () => {
  const xml = fixture("bnp_tinta_e_sangue.xml").replace(/<dc:identifier>http[^<]*<\/dc:identifier>/, "");
  assertEquals(parseBnp(xml, "9789722089876")!.coverUrl, null);
});

Deno.test("bnpMalformedXmlThrows", () => {
  let threw = false;
  try {
    parseBnp("<html><body>maintenance", "9789722089876");
  } catch {
    threw = true;
  }
  assertEquals(threw, true);
});

Deno.test("bnpHandlerAsksPorbaseForTheIsbn", async () => {
  const { d, calls } = deps(fixture("bnp_tinta_e_sangue.xml"), { status: 200 });
  const res = await handle(request("source=bnp&isbn=9789722089876"), d);
  assertEquals(calls, ["https://urn.porbase.org/isbn/dc/xml?id=9789722089876"]);
  const body = await res.json();
  assertEquals(body.found, true);
  assertEquals(body.book.title, "Tinta e sangue");
});

Deno.test("googleResultsMap", () => {
  assertEquals(mapGoogle(fixture("google_dune.json")), [{
    isbn: "9780441013593",
    title: "Dune: Deluxe Edition",
    authors: ["Frank Herbert"],
    publisher: "Ace",
    year: 1990,
    pages: 535,
    coverUrl: "https://books.google.com/books/content?id=B1hSG45JCX4C&zoom=1",
    description: "Set on the desert planet Arrakis.",
  }]);
  assertEquals(mapGoogle(fixture("google_partial.json")), [{
    isbn: "",
    title: "Anonymous Pamphlet",
    authors: [],
    publisher: null,
    year: 2005,
    pages: null,
    coverUrl: null,
    description: null,
  }]);
  assertEquals(mapGoogle(fixture("google_empty.json")), []);
});

Deno.test("googleCapsAtTwentyAndSendsTheKey", async () => {
  const many = JSON.stringify({ items: Array.from({ length: 25 }, (_, i) => ({ volumeInfo: { title: `Book ${i}` } })) });
  const { d, calls } = deps(many, { status: 200 });
  const res = await handle(request("source=google&q=dune%20herbert"), d);
  assertEquals((await res.json()).results.length, 20);
  const url = new URL(calls[0]);
  assertEquals(url.origin + url.pathname, "https://www.googleapis.com/books/v1/volumes");
  assertEquals(url.searchParams.get("q"), "dune herbert");
  assertEquals(url.searchParams.get("maxResults"), "20");
  assertEquals(url.searchParams.get("key"), "test-key");
});

Deno.test("upstreamFailureIs502", async () => {
  const { d } = deps("busy", { status: 503 });
  assertEquals((await handle(request("source=bnp&isbn=9789722089876"), d)).status, 502);
  assertEquals((await handle(request("source=google&q=dune"), d)).status, 502);
});

Deno.test("noTokenIs401", async () => {
  const { d, calls } = deps("{}");
  const res = await handle(request("source=bnp&isbn=9789722089876", {}), d);
  assertEquals(res.status, 401);
  assertEquals(calls, []);
});

Deno.test("badTokenIs401", async () => {
  const { d, calls } = deps("{}");
  const res = await handle(request("source=bnp&isbn=9789722089876", { Authorization: "Bearer nope" }), d);
  assertEquals(res.status, 401);
  assertEquals(calls, []);
});

Deno.test("badRequestsAre400", async () => {
  const { d } = deps("{}");
  assertEquals((await handle(request("source=bnp"), d)).status, 400);
  assertEquals((await handle(request("source=bnp&isbn=abc"), d)).status, 400);
  assertEquals((await handle(request("source=google"), d)).status, 400);
  assertEquals((await handle(request("source=other&q=x"), d)).status, 400);
});

Deno.test("corsEchoesAllowedOriginsOnly", async () => {
  const { d } = deps(fixture("bnp_not_found.xml"));
  for (const origin of [PAGES, "http://localhost:5173"]) {
    const res = await handle(request("source=bnp&isbn=9780000000002", { Authorization: "Bearer good-token", Origin: origin }), d);
    assertEquals(res.headers.get("Access-Control-Allow-Origin"), origin);
    await res.body?.cancel();
  }
  const evil = await handle(request("source=bnp&isbn=9780000000002", { Authorization: "Bearer good-token", Origin: "https://evil.example" }), d);
  assertEquals(evil.headers.get("Access-Control-Allow-Origin"), null);
  await evil.body?.cancel();
});

Deno.test("preflightIsAnsweredWithoutAToken", async () => {
  const { d, calls } = deps("{}");
  const res = await handle(request("source=bnp&isbn=1", { Origin: PAGES, "Access-Control-Request-Method": "GET" }, "OPTIONS"), d);
  assertEquals(res.status, 204);
  assertEquals(res.headers.get("Access-Control-Allow-Origin"), PAGES);
  const allowed = res.headers.get("Access-Control-Allow-Headers")!.toLowerCase();
  assertEquals(allowed.includes("authorization") && allowed.includes("apikey") && allowed.includes("content-type"), true);
  assertEquals(calls, []);
});

Deno.test("unauthorizedAnswersStillCarryCors", async () => {
  const { d } = deps("{}");
  const res = await handle(request("source=bnp&isbn=1", { Origin: PAGES }), d);
  assertEquals(res.status, 401);
  assertEquals(res.headers.get("Access-Control-Allow-Origin"), PAGES);
  await res.body?.cancel();
});

Deno.test("aThrowingAuthCheckIs401WithCors", async () => {
  const { d, calls } = deps("{}");
  d.verifyJwt = () => Promise.reject(new Error("auth is down"));
  const res = await handle(request("source=bnp&isbn=9789722089876", { Authorization: "Bearer good-token", Origin: PAGES }), d);
  assertEquals(res.status, 401);
  assertEquals(res.headers.get("access-control-allow-origin"), PAGES);
  assertEquals(calls, []);
  await res.body?.cancel();
});

Deno.test("aSlowUpstreamIs504", async () => {
  const { d } = deps("{}");
  d.timeoutMs = 5;
  d.fetch = (_input, signal) =>
    new Promise<Response>((_resolve, reject) => {
      signal?.addEventListener("abort", () => reject(signal.reason));
    });
  for (const query of ["source=bnp&isbn=9789722089876", "source=google&q=dune"]) {
    const res = await handle(request(query, { Authorization: "Bearer good-token", Origin: PAGES }), d);
    assertEquals(res.status, 504);
    assertEquals(res.headers.get("access-control-allow-origin"), PAGES);
    await res.body?.cancel();
  }
});

Deno.test("isbnTenWithLowerXIsNormalised", async () => {
  const xml = fixture("bnp_tinta_e_sangue.xml");
  const { d, calls } = deps(xml, { status: 200 });
  const res = await handle(request("source=bnp&isbn=0-306-40615-x"), d);
  assertEquals(calls, ["https://urn.porbase.org/isbn/dc/xml?id=030640615X"]);
  assertEquals((await res.json()).book.isbn, "030640615X");
});
