#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]
mod account;
mod archive;
mod desktop;
mod download;
mod engine;
mod errors;
mod filesystem;
mod library;
mod manager;
mod models;
mod playlist;
mod process;
mod storage;
use errors::AppError;
use manager::Manager as DownloadManager;
use models::*;
use std::{path::PathBuf, sync::Arc};
use tauri::{Emitter, Manager, State};
use tauri_plugin_autostart::ManagerExt;
use tauri_plugin_clipboard_manager::ClipboardExt;

type AppState = Arc<DownloadManager>;
#[tauri::command]
fn get_state(state: State<'_, AppState>) -> Database {
    state.store.db.lock().unwrap().clone()
}
#[tauri::command]
fn default_settings() -> Settings {
    Settings::default()
}
#[tauri::command]
fn engine_diagnostics(state: State<'_, AppState>) -> manager::Diagnostics {
    state.diagnostics()
}
#[tauri::command]
async fn get_job(state: State<'_, AppState>, id: String) -> Result<Job, AppError> {
    let current = state
        .store
        .db
        .lock()
        .unwrap()
        .jobs
        .iter()
        .find(|j| j.id == id)
        .cloned();
    if let Some(job) = current {
        return Ok(job);
    }
    let store = state.store.clone();
    tokio::task::spawn_blocking(move || store.lookup(&id))
        .await
        .map_err(|e| e.to_string())?
        .map_err(Into::into)
}
#[tauri::command]
async fn control_playlist(
    app: tauri::AppHandle,
    state: State<'_, AppState>,
    id: String,
    action: String,
) -> Result<(), AppError> {
    state
        .control_batch(&app, Some(&id), &action)
        .await
        .map_err(Into::into)
}
#[tauri::command]
async fn get_history(state: State<'_, AppState>, offset: usize) -> Result<Vec<Job>, AppError> {
    if offset > 1_000_000 {
        return Err("Invalid history page".into());
    }
    let store = state.store.clone();
    tokio::task::spawn_blocking(move || store.history(offset, 200))
        .await
        .map_err(|e| e.to_string())?
        .map_err(Into::into)
}
#[tauri::command]
async fn analyze_url(
    app: tauri::AppHandle,
    state: State<'_, AppState>,
    url: String,
    playlist: bool,
) -> Result<Media, AppError> {
    state
        .analyze(&app, &url, playlist)
        .await
        .map_err(Into::into)
}
#[tauri::command]
async fn search_youtube(
    app: tauri::AppHandle,
    state: State<'_, AppState>,
    query: String,
    kind: String,
) -> Result<serde_json::Value, AppError> {
    let settings = state.store.db.lock().unwrap().settings.clone();
    engine::search(&app, &settings, &query, &kind)
        .await
        .map_err(Into::into)
}
#[tauri::command]
async fn enqueue(
    app: tauri::AppHandle,
    state: State<'_, AppState>,
    request: EnqueueRequest,
) -> Result<Vec<String>, AppError> {
    state
        .inner()
        .enqueue(&app, request)
        .await
        .map_err(Into::into)
}
#[tauri::command]
async fn control_job(
    app: tauri::AppHandle,
    state: State<'_, AppState>,
    id: String,
    action: String,
) -> Result<(), AppError> {
    state.control(&app, &id, &action).await.map_err(Into::into)
}
#[tauri::command]
async fn control_all(
    app: tauri::AppHandle,
    state: State<'_, AppState>,
    action: String,
) -> Result<(), AppError> {
    if !["pause", "resume", "cancel"].contains(&action.as_str()) {
        return Err("Unknown queue action.".into());
    }
    state.control_all(&app, &action).await.map_err(Into::into)
}
#[tauri::command]
async fn clear_history(app: tauri::AppHandle, state: State<'_, AppState>) -> Result<(), AppError> {
    {
        let mut db = state.store.db.lock().unwrap();
        let preserve: Vec<String> = db
            .batches
            .iter()
            .filter(|b| !b.status.terminal())
            .map(|b| b.id.clone())
            .collect();
        db.jobs.retain(|j| {
            !j.status.terminal()
                || j.playlist_id
                    .as_ref()
                    .is_some_and(|id| preserve.contains(id))
        });
        db.batches.retain(|b| !b.status.terminal());
    }
    state.persist().await?;
    let store = state.store.clone();
    tokio::task::spawn_blocking(move || store.clear_finished())
        .await
        .map_err(|e| e.to_string())??;
    state.store.emit(&app, "state:changed");
    Ok(())
}
#[tauri::command]
async fn retry_archive(
    app: tauri::AppHandle,
    state: State<'_, AppState>,
    id: String,
) -> Result<(), AppError> {
    {
        let mut db = state.store.db.lock().unwrap();
        let b = db
            .batches
            .iter_mut()
            .find(|b| b.id == id)
            .ok_or("Playlist not found")?;
        if !b.create_zip || !b.status.terminal() {
            return Err("Only a finished playlist ZIP can be retried.".into());
        }
        b.status = Status::Queued;
        b.error = None;
        b.progress = 0.0;
    }
    state.persist().await?;
    state.wake();
    state.store.emit(&app, "state:changed");
    Ok(())
}
#[tauri::command]
async fn save_settings(
    app: tauri::AppHandle,
    state: State<'_, AppState>,
    settings: Settings,
) -> Result<(), AppError> {
    let checked = settings.clone();
    tokio::task::spawn_blocking(move || storage::validate_settings(&checked))
        .await
        .map_err(|e| e.to_string())??;
    if state.active() {
        let old = state.store.db.lock().unwrap().settings.clone();
        if old.bandwidth_limit != settings.bandwidth_limit
            || old.concurrency != settings.concurrency
            || old.fragment_budget != settings.fragment_budget
        {
            return Err(
                "Pause active downloads before changing bandwidth or concurrency budgets.".into(),
            );
        }
    }
    if settings.start_with_windows {
        app.autolaunch().enable().map_err(|e| e.to_string())?;
    } else {
        app.autolaunch().disable().map_err(|e| e.to_string())?;
    }
    state.store.db.lock().unwrap().settings = settings;
    state.persist().await?;
    state.wake();
    state.store.emit(&app, "state:changed");
    Ok(())
}
#[tauri::command]
async fn engine_versions(
    app: tauri::AppHandle,
    state: State<'_, AppState>,
) -> Result<Vec<EngineVersion>, AppError> {
    let s = state.store.db.lock().unwrap().settings.clone();
    Ok(engine::versions(&app, &s).await)
}
#[tauri::command]
async fn update_engine(
    app: tauri::AppHandle,
    state: State<'_, AppState>,
) -> Result<String, AppError> {
    if state.active() {
        return Err("Pause the queue before updating yt-dlp.".into());
    }
    let settings = state.store.db.lock().unwrap().settings.clone();
    let source = engine::resolve(&app, &settings, "yt-dlp")?;
    let destination = if settings.yt_dlp_path.is_empty() {
        let folder = app
            .path()
            .app_data_dir()
            .map_err(|e| e.to_string())?
            .join("engines");
        std::fs::create_dir_all(&folder).map_err(|e| e.to_string())?;
        let p = folder.join("yt-dlp.exe");
        if source != p {
            std::fs::copy(source, &p).map_err(|e| e.to_string())?;
        }
        p
    } else {
        source
    };
    let output = tokio::time::timeout(
        std::time::Duration::from_secs(120),
        engine::output_guarded(engine::command(destination).args(["--ignore-config", "--update"])),
    )
    .await
    .map_err(|_| "Engine update timed out")?
    .map_err(|e| e.to_string())?;
    if !output.status.success() {
        return Err(engine::redact(&String::from_utf8_lossy(&output.stderr), &settings).into());
    }
    state.store.log("yt-dlp update checked");
    Ok(String::from_utf8_lossy(&output.stdout).into())
}
#[tauri::command]
fn disk_space(state: State<'_, AppState>) -> Result<serde_json::Value, AppError> {
    let s = state.store.db.lock().unwrap().settings.clone();
    Ok(
        serde_json::json!({"download": storage::writable(&s.download_dir)?, "temp": storage::writable(&s.temp_dir)?, "zip": storage::writable(&s.zip_dir)?}),
    )
}
#[tauri::command]
async fn open_output(
    app: tauri::AppHandle,
    state: State<'_, AppState>,
    id: Option<String>,
    kind: String,
) -> Result<(), AppError> {
    let path: PathBuf = {
        let db = state.store.db.lock().unwrap();
        match kind.as_str() {
            "logs" => {
                let p = state.store.root.join("Logs");
                std::fs::create_dir_all(&p).map_err(|e| e.to_string())?;
                p
            }
            "downloads" => PathBuf::from(&db.settings.download_dir),
            "file" | "folder" | "zip" | "playlist-folder" => {
                let id = id.as_ref().ok_or("Missing output ID")?;
                if kind == "playlist-folder" {
                    PathBuf::from(
                        &db.batches
                            .iter()
                            .find(|b| &b.id == id)
                            .ok_or("Playlist not found")?
                            .folder,
                    )
                } else if kind == "zip" {
                    PathBuf::from(
                        db.batches
                            .iter()
                            .find(|b| &b.id == id)
                            .and_then(|b| b.output_path.as_ref())
                            .ok_or("ZIP is not available")?,
                    )
                } else {
                    let job = db
                        .jobs
                        .iter()
                        .find(|j| &j.id == id)
                        .cloned()
                        .map(Ok)
                        .unwrap_or_else(|| state.store.lookup(id))?;
                    if kind == "folder" {
                        PathBuf::from(&job.output_dir)
                    } else {
                        PathBuf::from(
                            job.output_path
                                .as_ref()
                                .ok_or("No completed file is available")?,
                        )
                    }
                }
            }
            _ => return Err("Unknown output action".into()),
        }
    };
    if !path.exists() {
        return Err("The file or folder no longer exists. It may have been moved or removed after ZIP creation.".into());
    }
    // Dispatch through the Windows shell on the UI's COM-initialized thread.
    // Explorer's command-line parser treats yt-dlp's forward-slash paths as switches.
    let (send, receive) = tokio::sync::oneshot::channel();
    app.run_on_main_thread(move || {
        let _ = send.send(desktop::open_path(&path));
    })
    .map_err(|e| format!("Cannot open output: {e}"))?;
    receive
        .await
        .map_err(|e| format!("Cannot open output: {e}"))?
        .map_err(Into::into)
}
#[tauri::command]
async fn copy_url(
    app: tauri::AppHandle,
    state: State<'_, AppState>,
    id: String,
) -> Result<(), AppError> {
    let loaded = state
        .store
        .db
        .lock()
        .unwrap()
        .jobs
        .iter()
        .find(|job| job.id == id)
        .cloned();
    let job = if let Some(job) = loaded {
        job
    } else {
        let store = state.store.clone();
        tokio::task::spawn_blocking(move || store.lookup(&id))
            .await
            .map_err(|e| e.to_string())??
    };
    app.clipboard()
        .write_text(job.url)
        .map_err(|e| format!("Cannot copy URL: {e}").into())
}
#[tauri::command]
async fn clean_temporary(state: State<'_, AppState>) -> Result<(), AppError> {
    if state.active() {
        return Err("Pause active downloads before removing their partial data.".into());
    }
    let paths: Vec<(String, String, String)> = state
        .store
        .db
        .lock()
        .unwrap()
        .jobs
        .iter()
        .filter(|j| j.status.terminal())
        .map(|j| {
            (
                j.id.clone(),
                j.temp_dir.clone(),
                j.settings.temp_dir.clone(),
            )
        })
        .collect();
    for (id, path, root) in paths {
        let p = PathBuf::from(path);
        if !p.exists() {
            continue;
        }
        let managed = uuid::Uuid::parse_str(&id).is_ok()
            || (id
                .get(..36)
                .is_some_and(|s| uuid::Uuid::parse_str(s).is_ok())
                && id.get(36..).is_some_and(|s| {
                    s.starts_with('-') && s.len() == 6 && s[1..].bytes().all(|b| b.is_ascii_digit())
                }));
        let actual = std::fs::canonicalize(&p).map_err(|e| e.to_string())?;
        let expected = std::fs::canonicalize(root).map_err(|e| e.to_string())?;
        if managed
            && p.file_name().and_then(|s| s.to_str()) == Some(&id)
            && actual.parent() == Some(expected.as_path())
        {
            tokio::task::spawn_blocking(move || std::fs::remove_dir_all(actual))
                .await
                .map_err(|e| e.to_string())?
                .map_err(|e| e.to_string())?;
        }
    }
    state.store.log("terminal task temporary folders cleaned");
    Ok(())
}
#[tauri::command]
async fn window_action(
    app: tauri::AppHandle,
    state: State<'_, AppState>,
    action: String,
) -> Result<(), String> {
    let window = app.get_webview_window("main").ok_or("Window not found")?;
    match action.as_str() {
        "hide" => window.hide().map_err(|e| e.to_string()),
        "minimize" => window.minimize().map_err(|e| e.to_string()),
        "maximize" => {
            if window.is_maximized().map_err(|e| e.to_string())? {
                window.unmaximize().map_err(|e| e.to_string())
            } else {
                window.maximize().map_err(|e| e.to_string())
            }
        }
        "quit" => {
            state.shutdown(&app).await;
            app.exit(0);
            Ok(())
        }
        _ => Err("Unknown window action".into()),
    }
}
fn main() {
    tauri::Builder::default()
        .plugin(tauri_plugin_single_instance::init(|app, _, _| {
            if let Some(window) = app.get_webview_window("main") {
                let _ = window.show();
                let _ = window.set_focus();
            }
        }))
        .plugin(tauri_plugin_dialog::init())
        .plugin(tauri_plugin_clipboard_manager::init())
        .plugin(tauri_plugin_notification::init())
        .plugin(
            tauri_plugin_autostart::Builder::new()
                .args(["--startup"])
                .build(),
        )
        .setup(|app| {
            let isolated = std::env::var_os("YTHUB_DATA_DIR").map(PathBuf::from);
            if isolated.as_ref().is_some_and(|p| !p.is_absolute()) {
                return Err("YTHUB_DATA_DIR must be an absolute path".into());
            }
            let root = isolated.clone().unwrap_or(app.path().app_data_dir()?);
            let store = storage::Store::load(root).map_err(std::io::Error::other)?;
            let artwork = store.root.join("Artwork");
            std::fs::create_dir_all(&artwork)?;
            app.asset_protocol_scope()
                .allow_directory(&artwork, false)?;
            let config = app.config().app.windows[0].clone();
            let mut window = tauri::WebviewWindowBuilder::from_config(app, &config)?;
            if isolated.is_some() {
                window = window.data_directory(store.root.join("WebView"));
            }
            window.build()?;
            store.log("Hertix started");
            let s = store.db.lock().unwrap().settings.clone();
            for folder in [&s.download_dir, &s.temp_dir, &s.zip_dir] {
                if let Err(e) = storage::writable(folder) {
                    store.log(&format!("storage validation: {e}"));
                }
            }
            let manager = DownloadManager::new(store);
            app.manage(manager.clone());
            use tauri::{
                menu::{Menu, MenuItem},
                tray::TrayIconBuilder,
            };
            let menu = Menu::with_items(
                app,
                &[
                    &MenuItem::with_id(app, "show", "Show Hertix", true, None::<&str>)?,
                    &MenuItem::with_id(app, "hide", "Hide Hertix", true, None::<&str>)?,
                    &MenuItem::with_id(app, "pause", "Pause downloads", true, None::<&str>)?,
                    &MenuItem::with_id(app, "resume", "Resume downloads", true, None::<&str>)?,
                    &MenuItem::with_id(app, "quit", "Quit Hertix", true, None::<&str>)?,
                ],
            )?;
            let mut tray = TrayIconBuilder::new()
                .menu(&menu)
                .tooltip("Hertix — Music, Discovery & Playback")
                .on_menu_event(|app, event| match event.id.as_ref() {
                    "show" => {
                        if let Some(w) = app.get_webview_window("main") {
                            let _ = w.show();
                            let _ = w.set_focus();
                        }
                    }
                    "hide" => {
                        if let Some(w) = app.get_webview_window("main") {
                            let _ = w.hide();
                        }
                    }
                    "pause" | "resume" => {
                        let action = event.id.as_ref().to_string();
                        let app = app.clone();
                        let manager = app.state::<AppState>().inner().clone();
                        tauri::async_runtime::spawn(async move {
                            let _ = manager.control_all(&app, &action).await;
                        });
                    }
                    "quit" => {
                        let manager = app.state::<AppState>();
                        if manager.active() {
                            if let Some(w) = app.get_webview_window("main") {
                                let _ = w.show();
                            }
                            let _ = app.emit("app:close-requested", ());
                        } else {
                            app.exit(0);
                        }
                    }
                    _ => {}
                });
            if let Some(icon) = app.default_window_icon() {
                tray = tray.icon(icon.clone());
            }
            tray.build(app)?;
            if s.start_minimized && std::env::args().any(|a| a == "--startup") {
                if let Some(w) = app.get_webview_window("main") {
                    w.hide()?;
                }
            }
            manager.start(app.handle().clone());
            Ok(())
        })
        .on_window_event(|window, event| {
            if let tauri::WindowEvent::CloseRequested { api, .. } = event {
                api.prevent_close();
                let _ = window.app_handle().emit("app:close-requested", ());
            }
        })
        .invoke_handler(tauri::generate_handler![
            account::account_secret_read,
            account::account_secret_write,
            account::cloud_media_available,
            account::cloud_media_save,
            library::load_music,
            library::save_music,
            library::audio_gain,
            library::open_audio_folder,
            library::extract_audio_zip,
            library::prepare_audio,
            library::transcribe_lyrics,
            library::delete_audio,
            get_state,
            get_history,
            get_job,
            control_playlist,
            engine_diagnostics,
            default_settings,
            analyze_url,
            search_youtube,
            enqueue,
            control_job,
            control_all,
            clear_history,
            retry_archive,
            save_settings,
            engine_versions,
            update_engine,
            disk_space,
            open_output,
            copy_url,
            clean_temporary,
            window_action
        ])
        .build(tauri::generate_context!())
        .expect("Unable to initialize Hertix")
        .run(|app, event| {
            if let tauri::RunEvent::ExitRequested { api, .. } = event {
                let state = app.state::<AppState>();
                if state.active()
                    && !state
                        .shutting_down
                        .load(std::sync::atomic::Ordering::SeqCst)
                {
                    api.prevent_exit();
                    let _ = app.emit("app:close-requested", ());
                }
            }
        });
}
#[cfg(test)]
mod boundary_tests {
    use super::*;
    fn request(cmd: &str) -> tauri::webview::InvokeRequest {
        tauri::webview::InvokeRequest {
            cmd: cmd.into(),
            callback: tauri::ipc::CallbackFn(0),
            error: tauri::ipc::CallbackFn(1),
            url: "http://tauri.localhost".parse().unwrap(),
            body: tauri::ipc::InvokeBody::default(),
            headers: Default::default(),
            invoke_key: tauri::test::INVOKE_KEY.to_string(),
        }
    }
    #[test]
    fn actual_ipc_reads_settings_and_persistent_queue() {
        let folder = tempfile::tempdir().unwrap();
        let store = storage::Store::load(folder.path().into()).unwrap();
        {
            let mut db = store.db.lock().unwrap();
            let mut job = sample_job();
            job.status = Status::Paused;
            db.jobs.push(job);
        }
        store.save().unwrap();
        let manager = DownloadManager::new(store);
        let app = tauri::test::mock_builder()
            .manage(manager)
            .invoke_handler(tauri::generate_handler![get_state, default_settings])
            .build(tauri::test::mock_context(tauri::test::noop_assets()))
            .unwrap();
        let webview = tauri::WebviewWindowBuilder::new(&app, "main", Default::default())
            .build()
            .unwrap();
        let response = tauri::test::get_ipc_response(&webview, request("get_state"))
            .unwrap()
            .deserialize::<Database>()
            .unwrap();
        assert_eq!(response.jobs[0].status, Status::Paused);
        let response = tauri::test::get_ipc_response(&webview, request("default_settings"))
            .unwrap()
            .deserialize::<Settings>()
            .unwrap();
        assert_eq!(response.concurrency, 2);
        assert!(
            tauri::test::get_ipc_response(&webview, request("arbitrary_shell_command")).is_err()
        );
    }
}
