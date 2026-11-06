import { createClient } from 'npm:@supabase/supabase-js@2';

const cors = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': 'authorization, apikey, content-type, x-client-info',
  'Access-Control-Allow-Methods': 'POST, OPTIONS',
};
const respond = (status: number, value: unknown) => new Response(JSON.stringify(value), {
  status, headers: { ...cors, 'Content-Type': 'application/json', 'Cache-Control': 'private, max-age=60' },
});
const validTypes = new Set(['all', 'songs', 'videos', 'artists', 'channels', 'playlists']);
const searchTypes: Record<string, string> = {
  // YouTube's search.list `type` parameter accepts one resource kind per request.
  all: 'video', songs: 'video', videos: 'video',
  artists: 'channel', channels: 'channel', playlists: 'playlist',
};
const cache = new Map<string, { expires: number; value: unknown }>();
const requests = new Map<string, { start: number; count: number }>();

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

  let body: Record<string,string>;
  try { body = await request.json(); } catch { return respond(400, { error: 'Invalid JSON body.' }); }
  const query = (body.q || '').trim().replace(/\s+/g, ' ').slice(0, 100);
  const type = body.type || 'all';
  const pageToken = body.pageToken || '';
  if (query.length < 2) return respond(400, { error: 'Enter at least 2 characters.' });
  if (!validTypes.has(type)) return respond(400, { error: 'Unsupported search type.' });
  if (pageToken.length > 500) return respond(400, { error: 'Invalid page token.' });

  const now = Date.now();
  const window = requests.get(user.id);
  if (window && now - window.start < 60_000 && window.count >= 30) return respond(429, { error: 'Search limit reached. Wait a minute and retry.' });
  requests.set(user.id, !window || now - window.start >= 60_000 ? { start: now, count: 1 } : { ...window, count: window.count + 1 });

  const cacheKey = `${query.toLocaleLowerCase()}|${type}|${pageToken}`;
  const cached = cache.get(cacheKey);
  if (cached && cached.expires > now) return respond(200, cached.value);
  const apiKey = Deno.env.get('YOUTUBE_API_KEY');
  if (!apiKey) return respond(503, { error: 'YouTube search is not configured.' });
  try {
  const params = new URLSearchParams({ part: 'snippet', q: query, maxResults: '20', key: apiKey });
  // Omit type for the universal search. YouTube defaults to videos, and some
  // API key/project configurations reject even a single explicit type filter.
  if (type !== 'all') params.set('type', searchTypes[type]);
    if (pageToken) params.set('pageToken', pageToken);
    const response = await fetch(`https://www.googleapis.com/youtube/v3/search?${params}`, { signal: AbortSignal.timeout(10_000) });
    if (!response.ok) {
      const upstream = await response.json().catch(() => ({}));
      const reason = upstream?.error?.errors?.[0]?.reason || 'unknown';
      const message = String(upstream?.error?.message || 'No upstream detail').slice(0, 220);
      console.error('YouTube Data API request failed', response.status, reason, message);
      if (response.status === 403) return respond(429, { error: `YouTube API rejected the request (${reason}): ${message}` });
      return respond(502, { error: `YouTube API request failed (${response.status}, ${reason}): ${message}` });
    }
    const raw = await response.json();
    const items = (raw.items || []).flatMap((item: any) => {
      const id = item.id?.videoId || item.id?.playlistId || item.id?.channelId;
      if (!id) return [];
      const mediaType = item.id?.videoId ? 'video' : item.id?.playlistId ? 'playlist' : 'channel';
      return [{
        id, source: 'youtube', type: mediaType, title: item.snippet?.title || '',
        artist: mediaType === 'video' ? item.snippet?.channelTitle || '' : '',
        channel: item.snippet?.channelTitle || '', duration_seconds: null,
        thumbnail: item.snippet?.thumbnails?.medium?.url || item.snippet?.thumbnails?.default?.url || '',
        source_url: mediaType === 'video' ? `https://www.youtube.com/watch?v=${id}` : mediaType === 'playlist' ? `https://www.youtube.com/playlist?list=${id}` : `https://www.youtube.com/channel/${id}`,
        availability: 'metadata_available', download_capability: 'not_verified',
        playback_capability: 'youtube_link', added_to_library: false,
      }];
    });
    const result = { items, nextPageToken: raw.nextPageToken || null, searchType: type };
    cache.set(cacheKey, { expires: now + 60_000, value: result });
    if (cache.size > 300) cache.delete(cache.keys().next().value!);
    return respond(200, result);
  } catch {
    return respond(502, { error: 'YouTube search timed out. Try again.' });
  }
});
