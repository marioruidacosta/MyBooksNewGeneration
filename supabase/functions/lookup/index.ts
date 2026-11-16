// Supabase Edge Function `lookup`: BNP and Google Books for the web app. The logic is in handler.ts.
import { createClient } from "npm:@supabase/supabase-js@2";
import { handle } from "./handler.ts";

Deno.serve((req) =>
  handle(req, {
    fetch: (input, signal) => fetch(input, { signal }),
    env: (name) => Deno.env.get(name),
    // SUPABASE_URL and SUPABASE_ANON_KEY are provided to every Edge Function.
    verifyJwt: async (token) => {
      const url = Deno.env.get("SUPABASE_URL");
      const key = Deno.env.get("SUPABASE_ANON_KEY");
      if (!url || !key) return false;
      const supabase = createClient(url, key, { auth: { persistSession: false, autoRefreshToken: false } });
      const { data, error } = await supabase.auth.getUser(token);
      return !error && !!data.user;
    },
  })
);
