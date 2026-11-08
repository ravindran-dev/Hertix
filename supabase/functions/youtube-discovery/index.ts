import { createClient } from 'npm:@supabase/supabase-js@2';

// Public YouTube Music Innertube search, following the WEB_REMIX client path
// used by BitChord. This avoids spending the project's YouTube Data API quota.
// Innertube is an undocumented upstream protocol and may change independently.
const cors = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': 'authorization, apikey, content-type, x-client-info',
  'Access-Control-Allow-Methods': 'POST, OPTIONS',
};
const respond = (status: number, value: unknown) => new Response(JSON.stringify(value), {
  status, headers: { ...cors, 'Content-Type': 'application/json', 'Cache-Control': 'private, max-age=60' },
});
const validTypes = new Set(['all', 'songs', 'videos', 'artists', 'channels', 'playlists']);
const filters: Record<string, string | undefined> = {
  all: undefined,
  songs: 'EgWKAQIIAWoKEAkQChAFEAMQBA==',
  videos: 'EgWKAQIQAWoKEAkQChAFEAMQBA==',
  artists: 'EgWKAQIgAWoKEAkQChAFEAMQBA==',
  channels: 'EgWKAQIgAWoKEAkQChAFEAMQBA==',
  playlists: 'EgWKAQIoAWoKEAkQChAFEAMQBA==',
};
const cache = new Map<string, { expires: number; value: unknown }>();
const textOf = (value: unknown): string => {
  if (typeof value === 'string') return value;
  if (Array.isArray(value)) return value.map(textOf).join('');
  if (value && typeof value === 'object') {
    const object = value as Record<string, unknown>;
    if (typeof object.text === 'string') return object.text;
    if (Array.isArray(object.runs)) return object.runs.map(textOf).join('');
    if (object.simpleText) return textOf(object.simpleText);
  }
  return '';
};
const collect = (root: unknown, key: string, out: Record<string, unknown>[] = []) => {
  if (Array.isArray(root)) { for (const item of root) collect(item, key, out); return out; }
  if (!root || typeof root !== 'object') return out;
  const object = root as Record<string, unknown>;
  if (object[key] && typeof object[key] === 'object') out.push(object[key] as Record<string, unknown>);
  for (const value of Object.values(object)) collect(value, key, out);
  return out;
};
const findString = (root: unknown, key: string): string => {
  if (Array.isArray(root)) { for (const item of root) { const found = findString(item, key); if (found) return found; } return ''; }
  if (!root || typeof root !== 'object') return '';
  const object = root as Record<string, unknown>;
  if (typeof object[key] === 'string') return object[key] as string;
  for (const value of Object.values(object)) { const found = findString(value, key); if (found) return found; }
  return '';
};
const findArray = (root: unknown, key: string): unknown[] => {
  if (Array.isArray(root)) { for (const item of root) { const found = findArray(item, key); if (found.length) return found; } return []; }
  if (!root || typeof root !== 'object') return [];
  const object = root as Record<string, unknown>;
  if (Array.isArray(object[key])) return object[key] as unknown[];
  for (const value of Object.values(object)) { const found = findArray(value, key); if (found.length) return found; }
  return [];
};
const continuationOf = (root: unknown): string | null => {
  if (Array.isArray(root)) { for (const item of root) { const found = continuationOf(item); if (found) return found; } return null; }
  if (!root || typeof root !== 'object') return null;
  const object = root as Record<string, unknown>;
  const direct = object.continuationCommand as Record<string, unknown> | undefined;
  if (typeof direct?.token === 'string') return direct.token;
  for (const value of Object.values(object)) { const found = continuationOf(value); if (found) return found; }
  return null;
};
const thumbnailOf = (value: unknown): string => {
  if (!Array.isArray(value)) return '';
  const urls = value.flatMap(item => item && typeof item === 'object' ? [String((item as Record<string, unknown>).url || '')] : []).filter(Boolean);
  return urls.at(-1) || urls[0] || '';
};
const parseItems = (root: unknown, requestedType: string) => {
  const rows = collect(root, 'musicResponsiveListItemRenderer');
  const seen = new Set<string>();
  return rows.flatMap(row => {
    const flex = Array.isArray(row.flexColumns) ? row.flexColumns as Record<string, unknown>[] : [];
    const texts = flex.map(column => textOf((column as any).musicResponsiveListItemFlexColumnRenderer?.text));
    const title = texts[0] || textOf((row as any).title) || '';
    const navigation = collect(row, 'watchEndpoint')[0] || {};
    const browse = collect(row, 'browseEndpoint')[0] || {};
    const videoId = String((navigation as any).videoId || findString(row, 'videoId'));
    const playlistId = findString(row, 'playlistId');
    const browseId = String((browse as any).browseId || '');
    const id = videoId || playlistId || browseId;
    if (!id || !title || seen.has(id)) return [];
    seen.add(id);
    const type = videoId ? 'video' : playlistId ? 'playlist' : 'channel';
    if (requestedType === 'playlists' && type !== 'playlist') return [];
    if ((requestedType === 'artists' || requestedType === 'channels') && type !== 'channel') return [];
    const thumbnail = thumbnailOf(findArray(row, 'thumbnails'));
    const channel = texts.slice(1).filter(Boolean).join(' · ');
    return [{
      id, source: 'youtube', type, title,
      artist: type === 'video' ? channel : '', channel, duration_seconds: null,
      thumbnail, source_url: type === 'playlist' ? `https://music.youtube.com/playlist?list=${id}` : type === 'channel' ? `https://music.youtube.com/channel/${id}` : `https://music.youtube.com/watch?v=${id}`,
      availability: 'metadata_available', download_capability: 'not_verified',
      playback_capability: 'youtube_link', added_to_library: false,
    }];
  });
};

Deno.serve(async (request) => {
  if (request.method === 'OPTIONS') return new Response('', { headers: cors });
  if (request.method !== 'POST') return respond(405, { error: 'POST required.' });
  const authorization = request.headers.get('Authorization');
  if (!authorization?.startsWith('Bearer ')) return respond(401, { error: 'Sign in to search YouTube.' });
  const supabase = createClient(Deno.env.get('SUPABASE_URL')!, Deno.env.get('SUPABASE_ANON_KEY')!, {
    global: { headers: { Authorization: authorization } }, auth: { persistSession: false },
  });
  const { data: { user }, error: authError } = await supabase.auth.getUser();
  if (authError || !user) return respond(401, { error: 'Session expired. Sign in again.' });

  let body: Record<string, string>;
  try { body = await request.json(); } catch { return respond(400, { error: 'Invalid JSON body.' }); }
  const query = (body.q || '').trim().replace(/\s+/g, ' ').slice(0, 100);
  const type = body.type || 'all';
  const pageToken = body.pageToken || '';
  if (query.length < 2) return respond(400, { error: 'Enter at least 2 characters.' });
  if (!validTypes.has(type)) return respond(400, { error: 'Unsupported search type.' });
  if (pageToken.length > 500) return respond(400, { error: 'Invalid page token.' });

  const cacheKey = `${query.toLocaleLowerCase()}|${type}|${pageToken}`;
  const now = Date.now();
  const cached = cache.get(cacheKey);
  if (cached && cached.expires > now) return respond(200, cached.value);
  try {
    const endpoint = pageToken
      ? `https://music.youtube.com/youtubei/v1/search?prettyPrint=false&ctoken=${encodeURIComponent(pageToken)}&continuation=${encodeURIComponent(pageToken)}&type=next`
      : 'https://music.youtube.com/youtubei/v1/search?prettyPrint=false';
    const response = await fetch(endpoint, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Origin': 'https://music.youtube.com', 'Referer': 'https://music.youtube.com/', 'X-Origin': 'https://music.youtube.com', 'X-YouTube-Client-Name': '67', 'X-YouTube-Client-Version': '1.20250101.01.00', 'Accept-Language': 'en-US,en;q=0.9', 'User-Agent': 'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/131.0.0.0 Safari/537.36' },
      body: JSON.stringify({ context: { client: { clientName: 'WEB_REMIX', clientVersion: '1.20250101.01.00', hl: 'en', gl: 'US' }, user: { lockedSafetyMode: false }, request: { useSsl: true } }, ...(pageToken ? { continuation: pageToken } : { query, ...(filters[type] ? { params: filters[type] } : {}) }) }),
      signal: AbortSignal.timeout(10_000),
    });
    const raw = await response.json().catch(() => ({}));
    if (!response.ok) {
      console.error('YouTube Music Innertube search failed', response.status);
      return respond(502, { error: response.status === 429 ? 'YouTube Music search is temporarily busy. Try again shortly.' : 'YouTube Music search is temporarily unavailable.' });
    }
    const items = parseItems(raw, type);
    const result = { items, nextPageToken: continuationOf(raw), searchType: type };
    cache.set(cacheKey, { expires: now + 60_000, value: result });
    if (cache.size > 300) cache.delete(cache.keys().next().value!);
    return respond(200, result);
  } catch (error) {
    console.error('YouTube Music Innertube search failed', String(error).slice(0, 160));
    return respond(502, { error: 'YouTube Music search timed out. Try again.' });
  }
});
