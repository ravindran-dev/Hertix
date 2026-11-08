use crate::models::*;
use serde_json::Value;
#[cfg(test)]
use std::path::Path;
use std::{path::PathBuf, process::Stdio};
use tauri::{AppHandle, Manager};
use tokio::process::Command;

pub fn command(path: impl AsRef<std::ffi::OsStr>) -> Command {
    let mut c = Command::new(path);
    c.kill_on_drop(true);
    #[cfg(windows)]
    c.creation_flags(0x08000000);
    c
}
pub async fn output_guarded(command: &mut Command) -> Result<std::process::Output, std::io::Error> {
    command.stdout(Stdio::piped()).stderr(Stdio::piped());
    let (mut child, _tree) =
        crate::process::spawn_managed(command).map_err(std::io::Error::other)?;
    async fn bounded(
        reader: impl tokio::io::AsyncRead + Unpin,
        max: usize,
    ) -> Result<Vec<u8>, std::io::Error> {
        use tokio::io::AsyncReadExt;
        let mut bytes = Vec::new();
        reader.take(max as u64 + 1).read_to_end(&mut bytes).await?;
        if bytes.len() > max {
            return Err(std::io::Error::other(
                "Engine output exceeded metadata safety limit",
            ));
        }
        Ok(bytes)
    }
    let (stdout, stderr) = tokio::try_join!(
        bounded(
            child
                .stdout
                .take()
                .ok_or_else(|| std::io::Error::other("No stdout"))?,
            16 * 1024 * 1024
        ),
        bounded(
            child
                .stderr
                .take()
                .ok_or_else(|| std::io::Error::other("No stderr"))?,
            64 * 1024
        )
    )?;
    let status = child.wait().await?;
    Ok(std::process::Output {
        status,
        stdout,
        stderr,
    })
}
pub fn resolve(app: &AppHandle, s: &Settings, name: &str) -> Result<PathBuf, String> {
    let custom = match name {
        "yt-dlp" => s.yt_dlp_path.as_str(),
        "ffmpeg" => s.ffmpeg_path.as_str(),
        "ffprobe" => s.ffprobe_path.as_str(),
        _ => "",
    };
    if !custom.is_empty() {
        let p = PathBuf::from(custom);
        if !p.is_absolute() || !p.is_file() || p.extension().and_then(|v| v.to_str()) != Some("exe")
        {
            return Err(format!(
                "{name} path must refer to an existing absolute .exe file."
            ));
        }
        return Ok(p);
    }
    let updated = app
        .path()
        .app_data_dir()
        .map_err(|e| e.to_string())?
        .join("engines")
        .join(format!("{name}.exe"));
    if name == "yt-dlp" && updated.is_file() {
        return Ok(updated);
    }
    let bundled = app
        .path()
        .resource_dir()
        .map_err(|e| e.to_string())?
        .join("binaries")
        .join(format!("{name}.exe"));
    if bundled.is_file() {
        return Ok(bundled);
    }
    let development = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("binaries")
        .join(format!("{name}.exe"));
    if cfg!(debug_assertions) && development.is_file() {
        return Ok(development);
    }
    Err(format!(
        "{name} not found. Install the bundled engines or select a custom executable in Settings."
    ))
}
pub fn validate_url(input: &str) -> Result<String, String> {
    let url = url::Url::parse(input.trim()).map_err(|_| "Enter a valid YouTube URL.")?;
    if !["https", "http"].contains(&url.scheme())
        || !url.username().is_empty()
        || url.password().is_some()
    {
        return Err("Only http(s) YouTube URLs without credentials are supported.".into());
    }
    let host = url.host_str().unwrap_or("");
    if !(host == "youtube.com"
        || host.ends_with(".youtube.com")
        || host == "youtu.be"
        || host == "youtube-nocookie.com"
        || host.ends_with(".youtube-nocookie.com"))
    {
        return Err("This app supports YouTube video, playlist, and channel URLs.".into());
    }
    if url.path() == "/" && url.query().is_none() {
        return Err(
            "Paste a video, playlist, or channel URL, rather than the YouTube homepage.".into(),
        );
    }
    Ok(url.to_string())
}
pub fn validate_quality(kind: &str, q: &str) -> Result<(), String> {
    let valid = match kind {
        "audio" => ["128", "192", "256", "320", "best"].contains(&q),
        "video" => ["best", "2160", "1440", "1080", "720", "480"].contains(&q),
        _ => false,
    };
    if valid {
        Ok(())
    } else {
        Err("Invalid media type or quality.".into())
    }
}
pub fn network_args(s: &Settings) -> Vec<String> {
    let mut a = vec![
        "--ignore-config".into(),
        "--no-colors".into(),
        "--socket-timeout".into(),
        s.socket_timeout.to_string(),
        "--retries".into(),
        s.retries.to_string(),
    ];
    if !s.proxy.is_empty() {
        a.extend(["--proxy".into(), s.proxy.clone()]);
    }
    if !s.cookies_file.is_empty() {
        a.extend(["--cookies".into(), s.cookies_file.clone()]);
    }
    a
}
pub fn output_template(job: &Job) -> String {
    let mut template = if job.playlist_id.is_some() {
        job.settings.playlist_template.clone()
    } else {
        job.settings.filename_template.clone()
    };
    for width in ["02", "03", ""] {
        let marker = format!("%(playlist_index){width}d");
        if job.settings.preserve_order {
            if let Some(index) = job.playlist_index {
                template = template.replace(&marker, &format!("{index:03}"));
            }
        } else {
            template = template
                .replace(&format!("{marker} - "), "")
                .replace(&marker, "");
        }
    }
    if job.settings.duplicate == "rename" {
        template = template.replace(
            ".%(ext)s",
            &format!("-{}.%(ext)s", job.id.get(..8).unwrap_or(&job.id)),
        );
    }
    template
}
pub fn redact(text: &str, s: &Settings) -> String {
    let mut result = text.to_string();
    for secret in [&s.proxy, &s.cookies_file] {
        if !secret.is_empty() {
            result = result.replace(secret, "[configured value]");
        }
    }
    result.chars().take(2000).collect()
}
pub async fn versions(app: &AppHandle, s: &Settings) -> Vec<EngineVersion> {
    let mut result = vec![];
    for name in ["yt-dlp", "ffmpeg", "ffprobe", "deno"] {
        let mut v = EngineVersion {
            name: name.into(),
            path: String::new(),
            version: None,
            error: None,
        };
        match resolve(app, s, name) {
            Ok(path) => {
                v.path = path.to_string_lossy().into();
                match tokio::time::timeout(
                    std::time::Duration::from_secs(15),
                    output_guarded(command(&path).arg(if name == "yt-dlp" || name == "deno" {
                        "--version"
                    } else {
                        "-version"
                    })),
                )
                .await
                {
                    Ok(Ok(output)) if output.status.success() => {
                        v.version = Some(
                            String::from_utf8_lossy(&output.stdout)
                                .lines()
                                .next()
                                .unwrap_or("Unknown version")
                                .to_string(),
                        );
                    }
                    _ => {
                        v.error = Some(
                            "Could not execute engine. Check its path and antivirus permissions."
                                .into(),
                        );
                    }
                }
            }
            Err(e) => {
                v.error = Some(e);
            }
        }
        result.push(v);
    }
    result
}
fn media(v: &Value) -> Media {
    let playlist = v["_type"]
        .as_str()
        .map(|t| t == "playlist" || t == "multi_video")
        .unwrap_or(false)
        || v["entries"].is_array();
    let id = v["id"].as_str().unwrap_or("").to_string();
    let raw_url = v["webpage_url"]
        .as_str()
        .or_else(|| v["url"].as_str())
        .unwrap_or("");
    let url = if raw_url.starts_with("http") {
        raw_url.to_string()
    } else {
        format!("https://www.youtube.com/watch?v={id}")
    };
    let thumbnail = v["thumbnail"]
        .as_str()
        .or_else(|| {
            v["thumbnails"]
                .as_array()
                .and_then(|a| a.last())
                .and_then(|t| t["url"].as_str())
        })
        .map(String::from);
    let mut qualities: Vec<u32> = v["formats"]
        .as_array()
        .map(|a| {
            a.iter()
                .filter_map(|f| f["height"].as_u64().map(|n| n as u32))
                .collect()
        })
        .unwrap_or_default();
    qualities.sort_unstable();
    qualities.dedup();
    Media {
        id,
        url,
        title: v["title"].as_str().unwrap_or("Unavailable item").into(),
        uploader: v["uploader"]
            .as_str()
            .or_else(|| v["channel"].as_str())
            .unwrap_or("Unknown channel")
            .into(),
        thumbnail,
        duration: v["duration"].as_f64(),
        entries: v["entries"]
            .as_array()
            .map(|a| a.iter().filter(|e| !e.is_null()).map(media).collect())
            .unwrap_or_default(),
        qualities,
        is_playlist: playlist,
    }
}
pub async fn analyze(
    app: &AppHandle,
    s: &Settings,
    url: &str,
    playlist: bool,
) -> Result<Media, String> {
    let url = validate_url(url)?;
    let path = resolve(app, s, "yt-dlp")?;
    let mut args = network_args(s);
    if let Ok(deno) = resolve(app, s, "deno") {
        args.extend([
            "--js-runtimes".into(),
            format!("deno:{}", deno.to_string_lossy()),
        ]);
    }
    args.extend([
        "--dump-single-json".into(),
        "--skip-download".into(),
        "--flat-playlist".into(),
        if playlist {
            "--yes-playlist"
        } else {
            "--no-playlist"
        }
        .into(),
        "--playlist-end".into(),
        "100".into(),
        "--".into(),
        url,
    ]);
    let output = tokio::time::timeout(
        std::time::Duration::from_secs(180),
        output_guarded(
            command(path)
                .args(args)
                .stdout(Stdio::piped())
                .stderr(Stdio::piped()),
        ),
    )
    .await
    .map_err(|_| "Analysis timed out. Check the network, cookies, and engine version.")?
    .map_err(|e| format!("Cannot start yt-dlp: {e}"))?;
    if !output.status.success() {
        return Err(redact(&String::from_utf8_lossy(&output.stderr), s));
    }
    let value: Value = serde_json::from_slice(&output.stdout)
        .map_err(|e| format!("Invalid metadata returned by yt-dlp: {e}"))?;
    let result = media(&value);
    if result.entries.len() > 10000 {
        return Err(
            "This playlist exceeds the 10,000 item safety limit. Use a smaller playlist.".into(),
        );
    }
    if result.is_playlist && result.entries.is_empty() {
        return Err(
            "Playlist is empty, private, or unavailable. Configure cookies if you have access."
                .into(),
        );
    }
    Ok(result)
}

pub async fn search(
    app: &AppHandle,
    s: &Settings,
    query: &str,
    kind: &str,
) -> Result<Value, String> {
    let query = query
        .trim()
        .split_whitespace()
        .collect::<Vec<_>>()
        .join(" ");
    if query.chars().count() < 2 || query.chars().count() > 100 {
        return Err("Enter a search between 2 and 100 characters.".into());
    }
    if kind == "playlists" {
        return music_playlist_search(s, &query).await;
    }
    let search_query = match kind {
        "all" | "videos" => query,
        "songs" => format!("{query} official audio"),
        "artists" | "channels" => format!("{query} artist"),
        _ => return Err("Unsupported search category.".into()),
    };
    let path = resolve(app, s, "yt-dlp")?;
    let mut args = network_args(s);
    if let Ok(deno) = resolve(app, s, "deno") {
        args.extend([
            "--js-runtimes".into(),
            format!("deno:{}", deno.to_string_lossy()),
        ]);
    }
    args.extend([
        "--dump-single-json".into(),
        "--skip-download".into(),
        "--flat-playlist".into(),
        "--playlist-end".into(),
        "20".into(),
        "--".into(),
        format!("ytsearch20:{search_query}"),
    ]);
    let output = tokio::time::timeout(
        std::time::Duration::from_secs(45),
        output_guarded(command(path).args(args)),
    )
    .await
    .map_err(|_| "YouTube search timed out. Check your network and try again.".to_string())?
    .map_err(|e| format!("Cannot start YouTube search: {e}"))?;
    if !output.status.success() {
        return Err(redact(&String::from_utf8_lossy(&output.stderr), s));
    }
    let value: Value = serde_json::from_slice(&output.stdout)
        .map_err(|e| format!("Invalid search results returned by YouTube: {e}"))?;
    let entries = value["entries"].as_array().cloned().unwrap_or_default();
    let items = entries
        .into_iter()
        .filter_map(|entry| {
            let id = entry["id"].as_str()?.to_owned();
            let title = entry["title"].as_str().unwrap_or("Untitled").to_owned();
            let channel = entry["channel"]
                .as_str()
                .or_else(|| entry["uploader"].as_str())
                .unwrap_or("")
                .to_owned();
            let thumbnail = entry["thumbnail"]
                .as_str()
                .or_else(|| entry["thumbnails"].as_array()?.last()?.get("url")?.as_str())
                .unwrap_or("")
                .to_owned();
            let duration_seconds = entry["duration"].as_f64();
            Some(serde_json::json!({
                "id": id,
                "type": "video",
                "title": title,
                "artist": channel,
                "channel": channel,
                "thumbnail": thumbnail,
                "source_url": format!("https://www.youtube.com/watch?v={id}"),
                "duration_seconds": duration_seconds,
            }))
        })
        .collect::<Vec<_>>();
    Ok(serde_json::json!({ "items": items, "nextPageToken": null }))
}

async fn music_playlist_search(s: &Settings, query: &str) -> Result<Value, String> {
    const ENDPOINT: &str = "https://music.youtube.com/youtubei/v1/search?prettyPrint=false";
    const PLAYLIST_FILTER: &str = "EgWKAQIoAWoKEAkQChAFEAMQBA==";
    let mut builder = reqwest::Client::builder()
        .timeout(std::time::Duration::from_secs(20))
        .user_agent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131.0.0.0 Safari/537.36");
    if !s.proxy.trim().is_empty() {
        let proxy = reqwest::Proxy::all(s.proxy.trim())
            .map_err(|e| format!("Invalid proxy configuration: {e}"))?;
        builder = builder.proxy(proxy);
    }
    let client = builder
        .build()
        .map_err(|e| format!("Could not initialize music search: {e}"))?;
    let payload = serde_json::to_vec(&serde_json::json!({
        "context": { "client": {
            "clientName": "WEB_REMIX",
            "clientVersion": "1.20250101.01.00",
            "hl": "en",
            "gl": "US"
        }},
        "query": query,
        "params": PLAYLIST_FILTER
    })).map_err(|e| format!("Could not encode playlist search request: {e}"))?;
    let response = client
        .post(ENDPOINT)
        .header(reqwest::header::CONTENT_TYPE, "application/json")
        .header(reqwest::header::ORIGIN, "https://music.youtube.com")
        .header(reqwest::header::REFERER, "https://music.youtube.com/")
        .header("X-Origin", "https://music.youtube.com")
        .header("X-YouTube-Client-Name", "67")
        .header("X-YouTube-Client-Version", "1.20250101.01.00")
        .header(reqwest::header::ACCEPT_LANGUAGE, "en-US,en;q=0.9")
        .body(payload)
        .send()
        .await
        .map_err(|e| format!("Playlist search could not reach YouTube Music: {e}"))?
        .error_for_status()
        .map_err(|e| format!("YouTube Music playlist search failed: {e}"))?;
    let bytes = response.bytes().await
        .map_err(|e| format!("Could not read YouTube Music playlist response: {e}"))?;
    let body: Value = serde_json::from_slice(&bytes)
        .map_err(|e| format!("YouTube Music returned an invalid playlist response: {e}"))?;
    let mut candidates = Vec::new();
    collect_music_objects(&body, &mut candidates);
    let mut items = Vec::new();
    for object in candidates {
        let id = find_string(object, &["playlistId"])
            .or_else(|| find_string(object, &["browseId"]).filter(|id| id.starts_with("VL")))
            .or_else(|| find_string(object, &["videoId"]).filter(|_| false));
        let Some(raw_id) = id else { continue };
        let playlist_id = raw_id.strip_prefix("VL").unwrap_or(&raw_id);
        if playlist_id.is_empty() { continue; }
        let title = object
            .get("flexColumns")
            .and_then(Value::as_array)
            .and_then(|columns| columns.first())
            .and_then(|column| column.pointer("/musicResponsiveListItemFlexColumnRenderer/text"))
            .map(renderer_text)
            .filter(|title| !title.trim().is_empty())
            .or_else(|| object.pointer("/title").map(renderer_text))
            .filter(|title| !title.trim().is_empty())
            .unwrap_or_else(|| "YouTube Music playlist".to_owned());
        let thumbnail = find_thumbnail(object).unwrap_or_default();
        if items.iter().any(|item: &Value| item["id"].as_str() == Some(playlist_id)) { continue; }
        items.push(serde_json::json!({
            "id": playlist_id,
            "type": "playlist",
            "title": title,
            "artist": "YouTube Music",
            "channel": "YouTube Music",
            "thumbnail": thumbnail,
            "source_url": format!("https://music.youtube.com/playlist?list={playlist_id}"),
            "duration_seconds": null
        }));
        if items.len() >= 20 { break; }
    }
    Ok(serde_json::json!({ "items": items, "nextPageToken": null }))
}

fn collect_music_objects<'a>(value: &'a Value, output: &mut Vec<&'a Value>) {
    match value {
        Value::Object(map) => {
            for key in ["musicResponsiveListItemRenderer", "musicTwoRowItemRenderer", "musicPlaylistShelfRenderer"] {
                if let Some(object) = map.get(key) { output.push(object); }
            }
            for child in map.values() { collect_music_objects(child, output); }
        }
        Value::Array(values) => for child in values { collect_music_objects(child, output); },
        _ => {}
    }
}

fn find_string(value: &Value, keys: &[&str]) -> Option<String> {
    match value {
        Value::Object(map) => {
            for key in keys {
                if let Some(found) = map.get(*key).and_then(Value::as_str) { return Some(found.to_owned()); }
            }
            for child in map.values() {
                if let Some(found) = find_string(child, keys) { return Some(found); }
            }
            None
        }
        Value::Array(values) => values.iter().find_map(|child| find_string(child, keys)),
        _ => None,
    }
}

fn find_thumbnail(value: &Value) -> Option<String> {
    match value {
        Value::Object(map) => {
            if let Some(thumbnails) = map.get("thumbnails").and_then(Value::as_array) {
                if let Some(url) = thumbnails.iter().rev().find_map(|image| image.get("url").and_then(Value::as_str)) {
                    return Some(url.to_owned());
                }
            }
            map.values().find_map(find_thumbnail)
        }
        Value::Array(values) => values.iter().find_map(find_thumbnail),
        _ => None,
    }
}

fn renderer_text(value: &Value) -> String {
    value
        .get("simpleText")
        .and_then(Value::as_str)
        .map(str::to_owned)
        .or_else(|| {
            value.get("runs").and_then(Value::as_array).map(|runs| {
                runs.iter()
                    .filter_map(|run| run.get("text").and_then(Value::as_str))
                    .collect::<String>()
            })
        })
        .unwrap_or_default()
}
pub fn parse_progress(line: &str) -> Option<Value> {
    line.strip_prefix("YTHUB_PROGRESS:")
        .and_then(|s| serde_json::from_str(s).ok())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[tokio::test]
    #[ignore = "requires provisioned engines and live YouTube access"]
    async fn live_engine_formats_metadata_and_streamed_archive() {
        let root = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("binaries");
        let yt = root.join("yt-dlp.exe");
        let ff = root.join("ffmpeg.exe");
        let probe = root.join("ffprobe.exe");
        let deno = root.join("deno.exe");
        let folder = tempfile::tempdir().unwrap();
        let mut files = vec![];
        for (index, (kind, q)) in [
            ("audio", "128"),
            ("audio", "192"),
            ("audio", "256"),
            ("audio", "320"),
            ("audio", "best"),
            ("video", "480"),
            ("video", "best"),
        ]
        .into_iter()
        .enumerate()
        {
            let mut job = sample_job();
            job.media_type = kind.into();
            job.quality = q.into();
            job.output_dir = folder
                .path()
                .join(format!("{kind}-{q}"))
                .to_string_lossy()
                .into();
            job.temp_dir = folder
                .path()
                .join(format!("partial-{index}"))
                .to_string_lossy()
                .into();
            job.playlist_id = Some("verification".into());
            job.playlist_index = Some(index + 1);
            job.playlist_title = Some("Hertix engine verification".into());
            std::fs::create_dir_all(&job.output_dir).unwrap();
            std::fs::create_dir_all(&job.temp_dir).unwrap();
            let cancel = tokio_util::sync::CancellationToken::new();
            let stage = crate::download::yt_dlp::download(&job, &yt, Some(&deno), &cancel, |_| {})
                .await
                .unwrap();
            let (path, _) =
                crate::download::ffmpeg::process(&job, &stage, &ff, &probe, &cancel, |_| {})
                    .await
                    .unwrap();
            let output = command(&probe).args(["-v","error","-show_entries","format=bit_rate,duration:stream=codec_type,codec_name:format_tags=album,artist","-of","json",&path]).output().await.unwrap();
            assert!(output.status.success());
            let info: Value = serde_json::from_slice(&output.stdout).unwrap();
            let streams = info["streams"].as_array().unwrap();
            assert!(streams.iter().any(|s| s["codec_type"] == "audio"));
            if kind == "video" {
                assert!(streams.iter().any(|s| s["codec_type"] == "video"));
            } else {
                assert!(streams.iter().any(|s| s["codec_name"] == "mp3"));
                assert_eq!(info["format"]["tags"]["album"], "Hertix engine verification");
                if q != "best" {
                    let bitrate = info["format"]["bit_rate"]
                        .as_str()
                        .unwrap()
                        .parse::<f64>()
                        .unwrap();
                    let target = q.parse::<f64>().unwrap() * 1000.0;
                    assert!(
                        (bitrate - target).abs() / target < 0.15,
                        "unexpected MP3 bitrate {bitrate}"
                    );
                }
            }
            files.push((index, PathBuf::from(path)));
        }
        let zip = folder.path().join("verified-playlist.zip");
        crate::archive::create_zip(
            &files,
            &zip,
            &std::sync::atomic::AtomicBool::new(false),
            |_| {},
        )
        .unwrap();
        let archive = zip::ZipArchive::new(std::fs::File::open(zip).unwrap()).unwrap();
        assert_eq!(archive.len(), 7);
    }
    #[test]
    fn audio_arguments_and_shell_boundary() {
        let j = sample_job();
        let args =
            crate::download::yt_dlp::args(&j, Some(Path::new("C:\\Engine folder\\deno.exe")));
        assert!(args.windows(2).any(|a| a == ["-f", "bestaudio/best"]));
        assert!(!args
            .iter()
            .any(|a| a == "--extract-audio" || a == "--remux-video"));
        assert_eq!(args[args.len() - 2], "--");
        assert_eq!(args.last().unwrap(), &j.url);
    }
    #[test]
    fn playlist_order_and_unique_rename() {
        let mut j = sample_job();
        j.playlist_id = Some("batch".into());
        j.playlist_index = Some(7);
        j.settings.duplicate = "rename".into();
        let a = crate::download::yt_dlp::args(&j, None);
        let template = &a[a.iter().position(|v| v == "-o").unwrap() + 1];
        assert!(template.starts_with("007 - "));
        assert!(template.contains("12345678"));
        j.settings.preserve_order = false;
        let a = crate::download::yt_dlp::args(&j, None);
        assert!(!a[a.iter().position(|v| v == "-o").unwrap() + 1].starts_with("007"));
    }
    #[test]
    fn video_resolution_selector_has_audio_fallback() {
        let mut j = sample_job();
        j.media_type = "video".into();
        j.quality = "1080".into();
        let a = crate::download::yt_dlp::args(&j, None);
        let selector = &a[a.iter().position(|v| v == "-f").unwrap() + 1];
        assert!(selector.contains("[height<=1080]"));
        assert!(selector.contains(",ba"));
        assert!(a.windows(2).any(|a| a == ["--fixup", "never"]));
    }
    #[test]
    fn url_boundary() {
        assert!(validate_url("https://youtu.be/abc").is_ok());
        assert!(validate_url("https://youtube.com.evil.test/watch?v=a").is_err());
        assert!(validate_url("file:///C:/test").is_err());
        assert!(validate_url("https://youtube.com/").is_err());
    }
    #[test]
    fn metadata_shape() {
        let m = media(
            &serde_json::json!({"_type":"playlist","title":"List","id":"PL1","entries":[{"id":"abc","title":"Track"}]}),
        );
        assert!(m.is_playlist);
        assert_eq!(m.entries[0].url, "https://www.youtube.com/watch?v=abc");
    }
    #[test]
    fn structured_progress() {
        assert_eq!(
            parse_progress("YTHUB_PROGRESS:{\"downloaded_bytes\":123}").unwrap()
                ["downloaded_bytes"],
            123
        );
        assert!(parse_progress("[download] random").is_none());
    }
    #[test]
    fn secrets_redacted() {
        let s = Settings {
            proxy: "http://user:password@proxy".into(),
            ..Default::default()
        };
        assert!(!redact(&s.proxy, &s).contains("password"));
    }
    #[test]
    fn quality_validation() {
        assert!(validate_quality("video", "1080").is_ok());
        assert!(validate_quality("audio", "1080").is_err());
    }
}
