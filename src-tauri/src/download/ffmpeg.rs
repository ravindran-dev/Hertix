use crate::{
    engine,
    models::{Job, StagedMedia},
};
use std::{
    path::{Path, PathBuf},
    time::Duration,
};
use tokio_util::sync::CancellationToken;
pub fn args(job: &Job, stage: &StagedMedia, target: &Path) -> Result<Vec<String>, String> {
    if stage.files.is_empty() || stage.files.len() > 2 {
        return Err("Invalid staged media inputs".into());
    }
    let mut a = vec![
        "-hide_banner".into(),
        "-nostdin".into(),
        "-y".into(),
        "-threads".into(),
        "2".into(),
        "-progress".into(),
        "pipe:1".into(),
        "-nostats".into(),
    ];
    for p in &stage.files {
        a.extend(["-threads".into(), "2".into(), "-i".into(), p.clone()]);
    }
    if job.media_type == "audio" {
        if let Some(thumbnail) = &stage.thumbnail {
            a.extend(["-i".into(), thumbnail.clone()]);
        }
    }
    if job.media_type == "audio" {
        a.extend([
            "-map".into(),
            "0:a:0".into(),
            "-c:a".into(),
            "libmp3lame".into(),
        ]);
        if job.quality == "best" {
            a.extend(["-q:a".into(), "0".into()]);
        } else {
            a.extend(["-b:a".into(), format!("{}k", job.quality)]);
        }
        if stage.thumbnail.is_some() {
            let index = stage.files.len();
            a.extend([
                "-map".into(),
                format!("{index}:v:0"),
                "-c:v".into(),
                "mjpeg".into(),
                "-disposition:v".into(),
                "attached_pic".into(),
            ]);
        }
        a.extend([
            "-id3v2_version".into(),
            "3".into(),
            "-f".into(),
            "mp3".into(),
        ]);
    } else {
        a.extend([
            "-map".into(),
            "0:v:0".into(),
            "-map".into(),
            if stage.files.len() > 1 {
                "1:a:0".into()
            } else {
                "0:a:0?".into()
            },
            "-c".into(),
            "copy".into(),
        ]);
        if job.settings.container == "mp4" {
            a.extend(["-movflags".into(), "+faststart".into()]);
        }
        a.extend([
            "-f".into(),
            if job.settings.container == "mkv" {
                "matroska".into()
            } else {
                "mp4".into()
            },
        ]);
    }
    if job.settings.embed_metadata {
        a.extend([
            "-metadata".into(),
            format!("title={}", stage.title),
            "-metadata".into(),
            format!("artist={}", stage.artist),
        ]);
        if let Some(album) = &job.playlist_title {
            a.extend(["-metadata".into(), format!("album={album}")]);
        }
    }
    a.extend([
        "-threads".into(),
        "2".into(),
        "-filter_threads".into(),
        "2".into(),
    ]);
    a.push(target.to_string_lossy().into());
    Ok(a)
}
pub async fn process(
    job: &Job,
    stage: &StagedMedia,
    ff: &Path,
    probe: &Path,
    cancel: &CancellationToken,
    line: impl FnMut(&str) + Send,
) -> Result<(String, bool), String> {
    if Path::new(&stage.final_name).components().count() != 1 {
        return Err("Invalid output filename".into());
    }
    let target = PathBuf::from(&job.output_dir).join(&stage.final_name);
    let root = std::fs::canonicalize(&job.output_dir).map_err(|e| e.to_string())?;
    if target.exists()
        && ["skip", "resume"].contains(&job.settings.duplicate.as_str())
        && std::fs::metadata(&target).map_err(|e| e.to_string())?.len() > 0
    {
        validate(&target, probe, job, cancel).await?;
        return Ok((target.to_string_lossy().into(), true));
    }
    let partial = target.with_extension(format!(
        "{}.{}.part",
        target.extension().unwrap_or_default().to_string_lossy(),
        job.id
    ));
    let mut command = engine::command(ff);
    command.args(args(job, stage, &partial)?);
    let result = super::supervisor::run(
        command,
        cancel,
        vec![job.output_dir.clone().into(), job.temp_dir.clone().into()],
        job.settings.disk_safety_mb * 1024 * 1024,
        Duration::from_secs(12 * 3600),
        line,
    )
    .await;
    if let Err(e) = result {
        let _ = std::fs::remove_file(&partial);
        return Err(format!("FFmpeg: {e}"));
    }
    if cancel.is_cancelled() {
        let _ = std::fs::remove_file(&partial);
        return Err("Cancelled".into());
    }
    let actual = std::fs::canonicalize(&partial).map_err(|e| format!("Corrupt output: {e}"))?;
    if !actual.starts_with(&root)
        || std::fs::metadata(&actual).map_err(|e| e.to_string())?.len() == 0
    {
        return Err("Corrupt output: empty or invalid final media".into());
    }
    if let Err(e) = validate(&partial, probe, job, cancel).await {
        let _ = tokio::fs::remove_file(&partial).await;
        return Err(e);
    }
    if cancel.is_cancelled() {
        let _ = tokio::fs::remove_file(&partial).await;
        return Err("Cancelled".into());
    }
    crate::filesystem::replace(&partial, &target)?;
    Ok((target.to_string_lossy().into(), false))
}
async fn validate(
    path: &Path,
    probe: &Path,
    job: &Job,
    cancel: &CancellationToken,
) -> Result<(), String> {
    let mut command = engine::command(probe);
    command
        .args([
            "-v",
            "error",
            "-show_entries",
            "stream=codec_type:format=duration",
            "-of",
            "json",
        ])
        .arg(path);
    let mut json = String::new();
    super::supervisor::run(
        command,
        cancel,
        vec![job.output_dir.clone().into()],
        job.settings.disk_safety_mb * 1024 * 1024,
        Duration::from_secs(30),
        |line| {
            if !line.starts_with("YTHUB_") && json.len() < 256 * 1024 {
                json.push_str(line);
                json.push('\n');
            }
        },
    )
    .await
    .map_err(|e| format!("Corrupt output: FFprobe validation failed: {e}"))?;
    let value: serde_json::Value =
        serde_json::from_str(&json).map_err(|e| format!("Corrupt output metadata: {e}"))?;
    let streams = value["streams"]
        .as_array()
        .ok_or("Corrupt output: no streams")?;
    // ID3 cover art is represented as a video stream, and malformed MP3s can
    // contain a few milliseconds of silent audio. Neither is a playable song.
    let audio = streams.iter().any(|s| {
        s["codec_type"] == "audio" && s["disposition"]["attached_pic"].as_u64() != Some(1)
    });
    let video = streams.iter().any(|s| s["codec_type"] == "video");
    let duration = value["format"]["duration"]
        .as_str()
        .and_then(|s| s.parse::<f64>().ok())
        .unwrap_or(0.0);
    if !duration.is_finite() || duration <= 0.0 {
        return Err("Corrupt output: media duration is missing".into());
    }
    if job.media_type == "audio" && !audio {
        return Err(
            "Corrupt output: no playable audio stream was found (thumbnail-only file)".into(),
        );
    }
    if job.media_type == "audio" && duration < 0.5 {
        return Err("Corrupt output: audio is shorter than half a second".into());
    }
    if job.media_type == "video" && !video {
        return Err("Corrupt output: no playable video stream was found".into());
    }
    Ok(())
}
#[cfg(test)]
mod tests {
    use super::*;
    use crate::models::sample_job;
    #[tokio::test]
    #[ignore = "requires provisioned FFmpeg and FFprobe; uses only local generated media"]
    async fn real_offline_audio_video_metadata_and_corrupt_output() {
        let d = tempfile::tempdir().unwrap();
        let folder = d.path().join("தமிழ் [local media]");
        std::fs::create_dir_all(&folder).unwrap();
        let bins = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("binaries");
        let ff = bins.join("ffmpeg.exe");
        let probe = bins.join("ffprobe.exe");
        let audio = folder.join("source.m4a");
        let video = folder.join("source.mp4");
        let cover = folder.join("cover.jpg");
        for (args, out) in [
            (
                vec![
                    "-f",
                    "lavfi",
                    "-i",
                    "sine=frequency=1000:duration=2",
                    "-c:a",
                    "aac",
                ],
                &audio,
            ),
            (
                vec![
                    "-f",
                    "lavfi",
                    "-i",
                    "color=c=red:s=128x72:r=10:d=2",
                    "-c:v",
                    "libx264",
                    "-pix_fmt",
                    "yuv420p",
                    "-threads",
                    "2",
                ],
                &video,
            ),
            (
                vec![
                    "-f",
                    "lavfi",
                    "-i",
                    "color=c=red:s=64x64",
                    "-frames:v",
                    "1",
                    "-threads",
                    "1",
                ],
                &cover,
            ),
        ] {
            let mut c = engine::command(&ff);
            c.args(["-hide_banner", "-loglevel", "error", "-y"])
                .args(args)
                .arg(out);
            super::super::supervisor::run(
                c,
                &CancellationToken::new(),
                vec![folder.clone()],
                0,
                Duration::from_secs(30),
                |_| {},
            )
            .await
            .unwrap();
        }
        let mut j = sample_job();
        j.output_dir = folder.to_string_lossy().into();
        j.temp_dir = j.output_dir.clone();
        j.settings.duplicate = "overwrite".into();
        j.settings.disk_safety_mb = 0;
        j.playlist_title = Some("Test album".into());
        let mut stage = StagedMedia {
            files: vec![audio.to_string_lossy().into()],
            title: "Tamil & title".into(),
            artist: "Local artist".into(),
            thumbnail: Some(cover.to_string_lossy().into()),
            final_name: String::new(),
        };
        for quality in ["128", "192", "256", "320", "best"] {
            j.quality = quality.into();
            stage.final_name = format!("audio-{quality}.mp3");
            let (path, skipped) =
                process(&j, &stage, &ff, &probe, &CancellationToken::new(), |_| {})
                    .await
                    .unwrap();
            assert!(!skipped);
            assert!(std::fs::metadata(&path).unwrap().len() > 1000);
            let output = engine::command(&probe)
                .args([
                    "-v",
                    "error",
                    "-show_entries",
                    "format_tags=title,artist,album:stream=codec_type,disposition",
                    "-of",
                    "json",
                ])
                .arg(&path)
                .output()
                .await
                .unwrap();
            let v: serde_json::Value = serde_json::from_slice(&output.stdout).unwrap();
            assert_eq!(v["format"]["tags"]["album"], "Test album");
            assert_eq!(v["format"]["tags"]["title"], "Tamil & title");
            assert!(v["streams"]
                .as_array()
                .unwrap()
                .iter()
                .any(|s| s["codec_type"] == "video"));
        }
        stage.files = vec![
            video.to_string_lossy().into(),
            audio.to_string_lossy().into(),
        ];
        stage.thumbnail = None;
        j.media_type = "video".into();
        for container in ["mp4", "mkv"] {
            j.settings.container = container.into();
            stage.final_name = format!("video.{container}");
            process(&j, &stage, &ff, &probe, &CancellationToken::new(), |_| {})
                .await
                .unwrap();
        }
        let broken = folder.join("broken.bin");
        std::fs::write(&broken, b"invalid media").unwrap();
        stage.files = vec![broken.to_string_lossy().into()];
        stage.final_name = "should-not-exist.mp4".into();
        assert!(
            process(&j, &stage, &ff, &probe, &CancellationToken::new(), |_| {})
                .await
                .is_err()
        );
        assert!(!folder.join(&stage.final_name).exists());
    }
    #[test]
    fn explicit_audio_processing_uses_arrays_and_ordered_metadata() {
        let mut j = sample_job();
        j.playlist_title = Some("My album & notes".into());
        let s = StagedMedia {
            files: vec!["C:/Tamil folder/source.webm".into()],
            title: "A & B".into(),
            artist: "Artist".into(),
            thumbnail: Some("C:/cover.webp".into()),
            ..Default::default()
        };
        let a = args(&j, &s, Path::new("C:/output.mp3.part")).unwrap();
        assert!(a.windows(2).any(|v| v == ["-b:a", "320k"]));
        assert!(a
            .windows(2)
            .any(|v| v == ["-metadata", "album=My album & notes"]));
        assert!(a
            .windows(2)
            .any(|v| v == ["-disposition:v", "attached_pic"]));
        assert!(!a.iter().any(|v| v == "cmd.exe"));
    }
}
