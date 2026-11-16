// Google Books Volumes API, mapped like the app's GoogleBooksClient.

export interface GoogleBook {
  isbn: string;
  title: string;
  authors: string[];
  publisher: string | null;
  year: number | null;
  pages: number | null;
  coverUrl: string | null;
  description: string | null;
}

export const MAX_RESULTS = 20;

interface Volumes {
  items?: {
    volumeInfo?: {
      title?: string;
      subtitle?: string;
      authors?: string[];
      publisher?: string;
      publishedDate?: string;
      description?: string;
      pageCount?: number;
      industryIdentifiers?: { type?: string; identifier?: string }[];
      imageLinks?: { smallThumbnail?: string; thumbnail?: string };
    };
  }[];
}

export function googleUrl(q: string, apiKey: string | undefined): string {
  const url = new URL("https://www.googleapis.com/books/v1/volumes");
  url.searchParams.set("q", q);
  url.searchParams.set("maxResults", String(MAX_RESULTS));
  if (apiKey?.trim()) url.searchParams.set("key", apiKey.trim());
  return url.toString();
}

function cleanText(text: string): string {
  // deno-lint-ignore no-control-regex
  return text.replace(/[\u0000-\u001f\u007f-\u009f]/g, "").replace(/\s+/g, " ").trim();
}

/** Up to 20 books from a Volumes response body; items without a title are dropped. */
export function mapGoogle(json: string): GoogleBook[] {
  const volumes = JSON.parse(json) as Volumes;
  const books: GoogleBook[] = [];
  for (const item of volumes.items ?? []) {
    const info = item.volumeInfo;
    if (!info) continue;
    const title = [info.title, info.subtitle].filter((t): t is string => !!t).map(cleanText).filter((t) => t !== "").join(": ");
    if (title === "") continue;
    const ids = info.industryIdentifiers ?? [];
    const isbn = (ids.find((i) => i.type === "ISBN_13") ?? ids.find((i) => i.type === "ISBN_10"))?.identifier ?? "";
    const cover = info.imageLinks?.thumbnail ?? info.imageLinks?.smallThumbnail;
    const year = info.publishedDate ? /^\d{4}/.exec(info.publishedDate) : null;
    books.push({
      isbn,
      title,
      authors: (info.authors ?? []).map(cleanText).filter((a) => a !== ""),
      publisher: info.publisher ? cleanText(info.publisher) || null : null,
      year: year ? parseInt(year[0], 10) : null,
      pages: info.pageCount ?? null,
      coverUrl: cover ? cover.replace(/^http:\/\//, "https://") : null,
      description: info.description ?? null,
    });
    if (books.length === MAX_RESULTS) break;
  }
  return books;
}
