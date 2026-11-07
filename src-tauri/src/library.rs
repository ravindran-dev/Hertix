//! Local playback boundary. Only complete, nonempty audio files are exposed.
use crate::{engine, AppState};
use serde::Serialize;
use std::path::{Path, PathBuf};
use tauri::{Manager, State};

const WHISPER_MODEL_URL: &str =
    "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small-q5_1.bin?download=true";
const WHISPER_MODEL_SHA256: &str =
    "f4a8c6ba84184c3af18f9f63ca9f777e27d39858c1435d27b8b9239092c16c15";
const WHISPER_MODEL_BYTES: u64 = 190_085_487;

/// Generate timestamped English or Tamil lyrics locally for an audio track.
#[tauri::command]
pub async fn transcribe_lyrics(
    app: tauri::AppHandle,
    state: State<'_, AppState>,
    path: String,
    language: String,
) -> Result<String, String> {
    let language = match language.as_str() {
        "auto" | "en" | "ta" => language,
        _ => return Err("Choose Auto, English or Tamil for transcription.".into()),
    };
    let file = tokio::task::spawn_blocking(move || audio_path(Path::new(&path)))
        .await
        .map_err(|e| e.to_string())??;
    let model_dir = app
        .path()
        .app_data_dir()
        .map_err(|e| e.to_string())?
        .join("models");
    tokio::fs::create_dir_all(&model_dir)
        .await
        .map_err(|e| e.to_string())?;
    let model = model_dir.join("ggml-small-q5_1.bin");
    if !model.is_file()
        || tokio::fs::metadata(&model)
            .await
            .map_err(|e| e.to_string())?
            .len()
            != WHISPER_MODEL_BYTES
    {
        let partial = model.with_extension("bin.part");
        let response = reqwest::Client::builder()
            .build()
            .map_err(|e| e.to_string())?
            .get(WHISPER_MODEL_URL)
            .send()
            .await
            .map_err(|e| format!("Could not download the speech model: {e}"))?
            .error_for_status()
            .map_err(|e| format!("Could not download the speech model: {e}"))?;
        if response
            .content_length()
            .is_some_and(|n| n != WHISPER_MODEL_BYTES)
        {
            return Err("The speech model download has an unexpected size.".into());
        }
        use futures_util::StreamExt;
        use sha2::{Digest, Sha256};
        use tokio::io::AsyncWriteExt;
        let mut stream = response.bytes_stream();
        let mut output = tokio::fs::File::create(&partial)
            .await
            .map_err(|e| e.to_string())?;
        let mut hasher = Sha256::new();
        let mut received = 0u64;
        while let Some(chunk) = stream.next().await {
            let chunk = chunk.map_err(|e| format!("Speech model download failed: {e}"))?;
            received = received
                .checked_add(chunk.len() as u64)
                .ok_or("Speech model is too large")?;
            if received > WHISPER_MODEL_BYTES {
                let _ = tokio::fs::remove_file(&partial).await;
                return Err("The speech model download is larger than expected.".into());
            }
            hasher.update(&chunk);
            output.write_all(&chunk).await.map_err(|e| e.to_string())?;
        }
        output.flush().await.map_err(|e| e.to_string())?;
        drop(output);
        if received != WHISPER_MODEL_BYTES
            || format!("{:x}", hasher.finalize()) != WHISPER_MODEL_SHA256
        {
            let _ = tokio::fs::remove_file(&partial).await;
            return Err("The speech model download failed its integrity check. Try again.".into());
        }
        tokio::fs::rename(&partial, &model)
            .await
            .map_err(|e| e.to_string())?;
    }
    let settings = state.store.db.lock().unwrap().settings.clone();
    let ffmpeg = engine::resolve(&app, &settings, "ffmpeg")?;
    let resources = app
        .path()
        .resource_dir()
        .map_err(|e| e.to_string())?
        .join("binaries");
    let whisper = resources.join("whisper-cli.exe");
    if !whisper.is_file() {
        return Err(
            "The local transcription engine is missing. Reinstall YTHub to repair it.".into(),
        );
    }
    let work = std::env::temp_dir().join(format!("ythub-transcribe-{}", uuid::Uuid::new_v4()));
    tokio::fs::create_dir_all(&work)
        .await
        .map_err(|e| e.to_string())?;
    let result = async {
        let wav = work.join("audio.wav");
        let converted = tokio::time::timeout(
            std::time::Duration::from_secs(300),
            engine::output_guarded(
                engine::command(ffmpeg)
                    .args(["-nostdin", "-hide_banner", "-loglevel", "error", "-y", "-i"])
                    .arg(&file)
                    .args(["-vn", "-ac", "1", "-ar", "16000", "-c:a", "pcm_s16le"])
                    .arg(&wav),
            ),
        )
        .await
        .map_err(|_| "Audio preparation timed out")?
        .map_err(|e| e.to_string())?;
        if !converted.status.success() {
            return Err("Could not prepare this audio for transcription.".into());
        }
        let prefix = work.join("lyrics");
        let generated = tokio::time::timeout(
            std::time::Duration::from_secs(3 * 60 * 60),
            engine::output_guarded(
                engine::command(&whisper)
                    .args(["-m"])
                    .arg(&model)
                    .args(["-f"])
                    .arg(&wav)
                    .args(["-l", &language, "-of"])
                    .arg(&prefix)
                    .args(["-olrc", "-np", "-nt"]),
            ),
        )
        .await
        .map_err(|_| "Transcription timed out")?
        .map_err(|e| e.to_string())?;
        if !generated.status.success() {
            return Err(
                "Transcription failed. Try a different language or a clearer audio file.".into(),
            );
        }
        let text = tokio::fs::read_to_string(prefix.with_extension("lrc"))
            .await
            .map_err(|_| {
                "No lyrics were recognized in this audio. Try English or Tamil explicitly."
            })?;
        if text.trim().is_empty() || text.len() > 2_000_000 {
            return Err("No usable lyrics were recognized.".into());
        }
        Ok(text)
    }
    .await;
    let _ = tokio::fs::remove_dir_all(&work).await;
    result
}

#[tauri::command]
pub async fn load_music(state: State<'_, AppState>) -> Result<Option<String>, String> {
    let store = state.store.clone();
    tokio::task::spawn_blocking(move || store.music())
        .await
        .map_err(|e| e.to_string())?
}
#[tauri::command]
pub async fn save_music(state: State<'_, AppState>, data: String) -> Result<(), String> {
    if data.len() > 64 * 1024 * 1024 {
        return Err("Music library storage exceeds 64 MB".into());
    }
    let store = state.store.clone();
    tokio::task::spawn_blocking(move || {
        let value: serde_json::Value = serde_json::from_str(&data).map_err(|e| e.to_string())?;
        if !value["state"]["tracks"].is_array() || !value["state"]["playlists"].is_array() {
            return Err("Invalid music library data".into());
        }
        store.save_music(&data)
    })
    .await
    .map_err(|e| e.to_string())?
}

fn audio_path(path: &Path) -> Result<PathBuf, String> {
    let path = path
        .canonicalize()
        .map_err(|_| "The audio file is missing or inaccessible")?;
    let ext = path
        .extension()
        .and_then(|s| s.to_str())
        .unwrap_or("")
        .to_lowercase();
    if !["mp3", "m4a", "aac", "wav", "flac", "ogg", "opus"].contains(&ext.as_str()) {
        return Err(
            "Choose a supported audio file, rather than a video, archive or partial download."
                .into(),
        );
    }
    let info = path.metadata().map_err(|e| e.to_string())?;
    if !info.is_file() || info.len() == 0 {
        return Err("The audio file is empty or incomplete.".into());
    }
    Ok(path)
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AudioInfo {
    path: String,
    title: String,
    artist: String,
    album: String,
    duration: Option<f64>,
    lyrics: Option<String>,
    artwork: Option<String>,
}

#[tauri::command]
pub async fn prepare_audio(
    app: tauri::AppHandle,
    state: State<'_, AppState>,
    path: String,
    lyrics: Option<bool>,
) -> Result<AudioInfo, String> {
    let file = tokio::task::spawn_blocking(move || audio_path(Path::new(&path)))
        .await
        .map_err(|e| e.to_string())??;
    app.asset_protocol_scope()
        .allow_file(&file)
        .map_err(|e| e.to_string())?;
    let settings = state.store.db.lock().unwrap().settings.clone();
    let mut info = AudioInfo {
        path: file.to_string_lossy().into_owned(),
        title: file
            .file_stem()
            .unwrap_or_default()
            .to_string_lossy()
            .into_owned(),
        artist: "Unknown artist".into(),
        album: String::new(),
        duration: None,
        lyrics: None,
        artwork: None,
    };
    let probe = engine::resolve(&app, &settings, "ffprobe")?;
    let output = tokio::time::timeout(
        std::time::Duration::from_secs(10),
        engine::output_guarded(
            engine::command(probe)
                .args([
                    "-v",
                    "error",
                    "-show_entries",
                    "format=duration:format_tags=title,artist,album:stream=codec_type:stream_disposition=attached_pic",
                    "-of",
                    "json",
                ])
                .arg(&file),
        ),
    )
    .await
    .map_err(|_| "Audio inspection timed out")?
    .map_err(|e| e.to_string())?;
    if !output.status.success() {
        return Err(
            "This audio file is damaged or cannot be read by FFmpeg. Re-download it and try again."
                .into(),
        );
    }
    let value: serde_json::Value = serde_json::from_slice(&output.stdout).map_err(|_| {
        "FFmpeg could not read this audio file's metadata. Re-download it and try again."
    })?;
    let format = &value["format"];
    info.duration = format["duration"].as_str().and_then(|s| s.parse().ok());
    let playable_audio = value["streams"].as_array().is_some_and(|streams| {
        streams.iter().any(|s| {
            s["codec_type"] == "audio" && s["disposition"]["attached_pic"].as_u64() != Some(1)
        })
    });
    if !playable_audio
        || !info
            .duration
            .is_some_and(|duration| duration.is_finite() && duration >= 0.5)
    {
        return Err("This file has no playable song audio (it may contain only the thumbnail). Re-download the audio and try again.".into());
    }
    for (key, target) in [
        ("title", &mut info.title),
        ("artist", &mut info.artist),
        ("album", &mut info.album),
    ] {
        if let Some(value) = format["tags"][key].as_str() {
            *target = value.to_owned();
        }
    }
    let cover = value["streams"].as_array().is_some_and(|streams| {
        streams
            .iter()
            .any(|s| s["disposition"]["attached_pic"].as_u64() == Some(1))
    });
    if cover {
        use std::hash::{Hash, Hasher};
        let mut hash = std::collections::hash_map::DefaultHasher::new();
        file.hash(&mut hash);
        if let Ok(meta) = tokio::fs::metadata(&file).await {
            meta.len().hash(&mut hash);
            meta.modified().ok().hash(&mut hash);
        }
        let folder = state.store.root.join("Artwork");
        tokio::fs::create_dir_all(&folder)
            .await
            .map_err(|e| e.to_string())?;
        let target = folder.join(format!("{:x}.jpg", hash.finish()));
        if !target.is_file() {
            if let Ok(ffmpeg) = engine::resolve(&app, &settings, "ffmpeg") {
                let _ = tokio::time::timeout(
                    std::time::Duration::from_secs(10),
                    engine::output_guarded(
                        engine::command(ffmpeg)
                            .args(["-nostdin", "-v", "error", "-y", "-i"])
                            .arg(&file)
                            .args(["-map", "0:v:0", "-frames:v", "1", "-vf", "scale=600:-1"])
                            .arg(&target),
                    ),
                )
                .await;
            }
        }
        if target.is_file() {
            app.asset_protocol_scope()
                .allow_file(&target)
                .map_err(|e| e.to_string())?;
            info.artwork = Some(target.to_string_lossy().into_owned());
        }
    }
    let lrc = file.with_extension("lrc");
    if lyrics.unwrap_or(false)
        && tokio::fs::metadata(&lrc)
            .await
            .is_ok_and(|m| m.len() < 1_000_000)
    {
        info.lyrics = tokio::fs::read_to_string(lrc).await.ok();
    }
    Ok(info)
}

#[tauri::command]
pub async fn extract_audio_zip(
    state: State<'_, AppState>,
    path: String,
) -> Result<Vec<String>, String> {
    let root = state
        .store
        .root
        .join("Imported")
        .join(uuid::Uuid::new_v4().to_string());
    tokio::task::spawn_blocking(move || {
        let source = Path::new(&path);
        if !source
            .extension()
            .is_some_and(|e| e.eq_ignore_ascii_case("zip"))
        {
            return Err("Choose a ZIP archive.".into());
        }
        let file = std::fs::File::open(source).map_err(|e| e.to_string())?;
        let mut zip = zip::ZipArchive::new(file).map_err(|e| e.to_string())?;
        if zip.len() > 10_000 {
            return Err("Archive exceeds 10,000 entries.".into());
        }
        let mut total = 0u64;
        for i in 0..zip.len() {
            let entry = zip.by_index(i).map_err(|e| e.to_string())?;
            if entry.enclosed_name().is_none()
                || entry.unix_mode().is_some_and(|m| m & 0o170000 == 0o120000)
            {
                return Err("Archive contains unsafe paths or links.".into());
            }
            total = total
                .checked_add(entry.size())
                .ok_or("Archive is too large")?;
            if total > 20_000_000_000 {
                return Err("Archive exceeds the 20 GB extraction limit.".into());
            }
        }
        std::fs::create_dir_all(&root).map_err(|e| e.to_string())?;
        if fs2::available_space(&root).map_err(|e| e.to_string())?
            < total.saturating_add(256_000_000)
        {
            return Err("Not enough disk space to extract this archive.".into());
        }
        let mut paths = Vec::new();
        for i in 0..zip.len() {
            let mut entry = zip.by_index(i).map_err(|e| e.to_string())?;
            if entry.is_dir() {
                continue;
            }
            let relative = entry.enclosed_name().ok_or("Unsafe archive path")?;
            let ext = relative
                .extension()
                .and_then(|v| v.to_str())
                .unwrap_or("")
                .to_lowercase();
            if !["mp3", "m4a", "aac", "wav", "flac", "ogg", "opus", "lrc"].contains(&ext.as_str()) {
                continue;
            }
            let target = root.join(relative);
            std::fs::create_dir_all(target.parent().ok_or("Invalid archive path")?)
                .map_err(|e| e.to_string())?;
            let mut destination = std::fs::OpenOptions::new()
                .write(true)
                .create_new(true)
                .open(&target)
                .map_err(|e| e.to_string())?;
            std::io::copy(&mut entry, &mut destination).map_err(|e| e.to_string())?;
            if ext != "lrc" {
                paths.push(target.to_string_lossy().into_owned());
            }
        }
        paths.sort();
        if paths.is_empty() {
            return Err("This ZIP has no supported audio files.".into());
        }
        Ok(paths)
    })
    .await
    .map_err(|e| e.to_string())?
}

#[tauri::command]
pub async fn delete_audio(path: String) -> Result<(), String> {
    tokio::task::spawn_blocking(move || {
        let file = audio_path(Path::new(&path))?;
        std::fs::remove_file(file).map_err(|e| e.to_string())
    })
    .await
    .map_err(|e| e.to_string())?
}

#[tauri::command]
pub async fn open_audio_folder(app: tauri::AppHandle, path: String) -> Result<(), String> {
    let file = tokio::task::spawn_blocking(move || audio_path(Path::new(&path)))
        .await
        .map_err(|e| e.to_string())??;
    let folder = file
        .parent()
        .ok_or("Audio folder is unavailable")?
        .to_path_buf();
    let (send, receive) = tokio::sync::oneshot::channel();
    app.run_on_main_thread(move || {
        let _ = send.send(crate::desktop::open_path(&folder));
    })
    .map_err(|e| e.to_string())?;
    receive.await.map_err(|e| e.to_string())?
}

/// Measure integrated loudness and true peak without buffering audio in the renderer.
#[tauri::command]
pub async fn audio_gain(
    app: tauri::AppHandle,
    state: State<'_, AppState>,
    path: String,
) -> Result<f64, String> {
    use std::hash::{Hash, Hasher};
    let file = tokio::task::spawn_blocking(move || audio_path(Path::new(&path)))
        .await
        .map_err(|e| e.to_string())??;
    let mut hash = std::collections::hash_map::DefaultHasher::new();
    file.hash(&mut hash);
    let metadata = tokio::fs::metadata(&file)
        .await
        .map_err(|e| e.to_string())?;
    metadata.len().hash(&mut hash);
    metadata.modified().ok().hash(&mut hash);
    let folder = state.store.root.join("Loudness");
    tokio::fs::create_dir_all(&folder)
        .await
        .map_err(|e| e.to_string())?;
    let cache = folder.join(format!("{:x}.json", hash.finish()));
    if let Ok(bytes) = tokio::fs::read(&cache).await {
        if let Ok(gain) = serde_json::from_slice::<f64>(&bytes) {
            if gain.is_finite() {
                return Ok(gain);
            }
        }
    }
    let settings = state.store.db.lock().unwrap().settings.clone();
    let ffmpeg = engine::resolve(&app, &settings, "ffmpeg")?;
    let output = tokio::time::timeout(
        std::time::Duration::from_secs(120),
        engine::output_guarded(
            engine::command(ffmpeg)
                .args(["-nostdin", "-hide_banner", "-i"])
                .arg(&file)
                .args([
                    "-map",
                    "0:a:0",
                    "-vn",
                    "-af",
                    "loudnorm=I=-16:TP=-1:LRA=11:print_format=json",
                    "-f",
                    "null",
                    "-",
                ]),
        ),
    )
    .await
    .map_err(|_| "Loudness analysis timed out")?
    .map_err(|e| e.to_string())?;
    if !output.status.success() {
        return Err("FFmpeg could not measure this audio file".into());
    }
    let text = String::from_utf8_lossy(&output.stderr);
    let start = text
        .rfind('{')
        .ok_or("No loudness measurement was returned")?;
    let end = text[start..]
        .find('}')
        .ok_or("Invalid loudness measurement")?
        + start;
    let measurement: serde_json::Value =
        serde_json::from_str(&text[start..=end]).map_err(|e| e.to_string())?;
    let parse = |key: &str| {
        measurement[key]
            .as_str()
            .and_then(|s| s.parse::<f64>().ok())
            .filter(|v| v.is_finite())
            .ok_or("Audio is silent or its loudness cannot be measured")
    };
    let gain = (-16.0 - parse("input_i")?)
        .min(-1.0 - parse("input_tp")?)
        .clamp(-24.0, 12.0);
    tokio::fs::write(cache, serde_json::to_vec(&gain).map_err(|e| e.to_string())?)
        .await
        .map_err(|e| e.to_string())?;
    Ok(gain)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn rejects_partial_archive_empty_and_missing_files() {
        let dir = tempfile::tempdir().unwrap();
        for ext in ["part", "zip", "mp4", "mp3"] {
            let path = dir.path().join(format!("track.{ext}"));
            std::fs::write(
                &path,
                if ext == "mp3" {
                    b"".as_slice()
                } else {
                    b"data".as_slice()
                },
            )
            .unwrap();
            assert!(audio_path(&path).is_err());
        }
        assert!(audio_path(&dir.path().join("missing.mp3")).is_err());
        let path = dir.path().join("track.flac");
        std::fs::write(&path, b"audio").unwrap();
        assert!(audio_path(&path).is_ok());
    }
}
