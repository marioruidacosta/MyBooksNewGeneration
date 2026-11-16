// Supabase Edge Function `lookup`: BNP and Google Books for the web app. The logic is in handler.ts.
import { createClient } from "npm:@supabase/supabase-js@2";
import { handle } from "./handler.ts";

Deno.serve((req) =>
  handle(req, {
    fetch: (input) => fetch(input),
    env: (name) => Deno.env.get(name),
    // SUPABASE_URL and SUPABASE_ANON_KEY are provided to every Edge Function.
    verifyJwt: async (token) => {
      const supabase = createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_ANON_KEY")!);
      const { data, error } = await supabase.auth.getUser(token);
      return !error && !!data.user;
    },
  })
);
