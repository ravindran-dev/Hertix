use crate::{
    archive,
    download::{
        self,
        queue::FairQueue,
        resource_manager::{ResourceManager, Resources},
        scheduler::Adaptive,
    },
    engine,
    errors::ErrorKind,
    models::*,
    playlist,
    storage::{self, Store},
};
use futures_util::FutureExt;
use std::{
    collections::{HashMap, HashSet},
    path::PathBuf,
    sync::{
        atomic::{AtomicBool, Ordering},
        Arc, Mutex,
    },
    time::{Duration, Instant},
};
use tauri::{AppHandle, Emitter};
use tauri_plugin_notification::NotificationExt;
use tokio::{
    sync::{mpsc, Notify, OwnedSemaphorePermit, Semaphore},
    task::JoinHandle,
};
use tokio_util::sync::CancellationToken;
#[path = "download/manager_archive.rs"]
mod archives;
#[path = "download/manager_control.rs"]
mod control;
#[path = "download/manager_workers.rs"]
mod workers;
#[derive(Clone, Copy, PartialEq)]
enum Phase {
    Download,
    Process,
    Archive,
}
struct Running {
    phase: Phase,
    cancel: CancellationToken,
    archive_cancel: Arc<AtomicBool>,
    pid: Option<u32>,
    started: String,
}
struct Work {
    job: Job,
    phase: Phase,
    cancel: CancellationToken,
    _permits: Vec<OwnedSemaphorePermit>,
}
struct ArchiveWork {
    batch: Batch,
    cancel: Arc<AtomicBool>,
    _permit: OwnedSemaphorePermit,
}
enum ResultStage {
    Media(StagedMedia),
    Finished(String, bool),
}
#[derive(Clone, Default, serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Diagnostics {
    pub active_workers: usize,
    pub processing_workers: usize,
    pub zip_jobs: usize,
    pub queued_tasks: usize,
    pub configured_concurrency: usize,
    pub current_concurrency: usize,
    pub active_yt_dlp: usize,
    pub active_ffmpeg: usize,
    pub throughput: f64,
    pub peak_speed: f64,
    pub retry_count: u64,
    pub throttled_hosts: usize,
    pub total_tasks: usize,
    pub completed_tasks: usize,
    pub failed_tasks: usize,
    pub cancelled_tasks: usize,
    pub bytes_downloaded: u64,
    pub resources: Resources,
    pub disk_write_throughput: Option<f64>,
    pub processes: Vec<ProcessInfo>,
}
#[derive(Clone, serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ProcessInfo {
    pub task_id: String,
    pub pid: Option<u32>,
    pub command: String,
    pub started_at: String,
}
struct Channels {
    download: mpsc::Sender<Work>,
    processing: mpsc::Sender<Work>,
    archive: mpsc::Sender<ArchiveWork>,
}
pub struct Manager {
    pub store: Arc<Store>,
    running: Mutex<HashMap<String, Running>>,
    pub shutting_down: AtomicBool,
    notify: Notify,
    stop: CancellationToken,
    admission: Arc<Semaphore>,
    media_slots: Arc<Semaphore>,
    ffmpeg_slots: Arc<Semaphore>,
    archive_slots: Arc<Semaphore>,
    fragments: Arc<Semaphore>,
    hosts: Mutex<HashMap<String, Arc<Semaphore>>>,
    resources: Arc<ResourceManager>,
    fair: Mutex<FairQueue>,
    adaptive: Mutex<Adaptive>,
    channels: Mutex<Option<Channels>>,
    handles: Mutex<Vec<JoinHandle<()>>>,
    metrics: Mutex<Diagnostics>,
    persist_gate: tokio::sync::Mutex<()>,
}
fn timestamp() -> i64 {
    chrono::Utc::now().timestamp_millis()
}
fn host(_job: &Job) -> &'static str {
    "youtube"
}
impl Manager {
    pub fn new(store: Store) -> Arc<Self> {
        Arc::new(Self {
            store: Arc::new(store),
            running: Mutex::new(HashMap::new()),
            shutting_down: AtomicBool::new(false),
            notify: Notify::new(),
            stop: CancellationToken::new(),
            admission: Arc::new(Semaphore::new(1)),
            media_slots: Arc::new(Semaphore::new(12)),
            ffmpeg_slots: Arc::new(Semaphore::new(4)),
            archive_slots: Arc::new(Semaphore::new(1)),
            fragments: Arc::new(Semaphore::new(64)),
            hosts: Mutex::new(HashMap::new()),
            resources: Arc::new(ResourceManager::default()),
            fair: Mutex::new(FairQueue::default()),
            adaptive: Mutex::new(Adaptive::default()),
            channels: Mutex::new(None),
            handles: Mutex::new(vec![]),
            metrics: Mutex::new(Diagnostics::default()),
            persist_gate: tokio::sync::Mutex::new(()),
        })
    }
    pub fn wake(&self) {
        self.notify.notify_one();
    }
    pub async fn persist(&self) -> Result<(), String> {
        let _gate = self.persist_gate.lock().await;
        let store = self.store.clone();
        tokio::task::spawn_blocking(move || store.save())
            .await
            .map_err(|e| e.to_string())?
    }
    pub async fn analyze(
        &self,
        app: &AppHandle,
        url: &str,
        playlist: bool,
    ) -> Result<Media, String> {
        let _permit =
            self.admission.clone().try_acquire_owned().map_err(|_| {
                "An analysis is already running. Wait before submitting another URL."
            })?;
        let settings = self.store.db.lock().unwrap().settings.clone();
        engine::analyze(app, &settings, url, playlist).await
    }
    pub fn start(self: &Arc<Self>, app: AppHandle) {
        let manager = self.clone();
        tauri::async_runtime::spawn(async move {
            let m = manager.clone();
            let a = app.clone();
            let (d, mut handles) =
                download::worker::pool(12, 12, manager.stop.clone(), move |work| {
                    let (m, a) = (m.clone(), a.clone());
                    async move {
                        m.run_work(a, work).await;
                    }
                });
            let m = manager.clone();
            let a = app.clone();
            let (p, h) = download::worker::pool(4, 4, manager.stop.clone(), move |work| {
                let (m, a) = (m.clone(), a.clone());
                async move {
                    m.run_work(a, work).await;
                }
            });
            handles.extend(h);
            let m = manager.clone();
            let a = app.clone();
            let (z, h) = download::worker::pool(1, 1, manager.stop.clone(), move |work| {
                let (m, a) = (m.clone(), a.clone());
                async move {
                    m.run_archive(a, work).await;
                }
            });
            handles.extend(h);
            manager.handles.lock().unwrap().extend(handles);
            *manager.channels.lock().unwrap() = Some(Channels {
                download: d,
                processing: p,
                archive: z,
            });
            let mut health = tokio::time::interval(Duration::from_secs(2));
            health.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
            let mut checkpoint = Instant::now();
            let mut metrics_at = Instant::now() - Duration::from_secs(2);
            let mut system = sysinfo::System::new();
            loop {
                tokio::select! {biased;_=manager.stop.cancelled()=>break,_=manager.notify.notified()=>{},_=health.tick()=>{let resources=manager.resources.clone();system=tokio::task::spawn_blocking(move||{resources.sample(&mut system);system}).await.unwrap_or_default();}}
                if manager.shutting_down.load(Ordering::SeqCst) {
                    break;
                }
                if let Err(e) = manager.schedule(&app).await {
                    manager.store.log(&format!("Scheduler error: {e}"));
                    let _ = app.emit("engine:error", e);
                }
                if checkpoint.elapsed() >= Duration::from_secs(10) {
                    if let Err(e) = manager.persist().await {
                        let _ = app.emit("engine:error", format!("Checkpoint failed: {e}"));
                    }
                    checkpoint = Instant::now();
                }
                if metrics_at.elapsed() >= Duration::from_millis(500) {
                    manager.refresh_metrics(&app).await;
                    metrics_at = Instant::now();
                }
            }
        });
    }
    pub async fn enqueue(
        self: &Arc<Self>,
        app: &AppHandle,
        r: EnqueueRequest,
    ) -> Result<Vec<String>, String> {
        if self.shutting_down.load(Ordering::SeqCst) {
            return Err("Hertix is shutting down".into());
        }
        let _permit = self
            .admission
            .clone()
            .try_acquire_owned()
            .map_err(|_| "An analysis is already running. Please wait.")?;
        engine::validate_quality(&r.media_type, &r.quality)?;
        let url = engine::validate_url(&r.url)?;
        let mut settings = self.store.db.lock().unwrap().settings.clone();
        settings.preserve_order = r.preserve_order;
        {
            let db = self.store.db.lock().unwrap();
            if db.jobs.iter().filter(|j| !j.status.terminal()).count() >= 128
                || db.batches.iter().filter(|b| !b.status.terminal()).count() >= 50
            {
                return Err(
                    "Queue capacity reached. Finish or cancel pending downloads first.".into(),
                );
            }
        }
        let s = settings.clone();
        tokio::task::spawn_blocking(move || storage::validate_settings(&s))
            .await
            .map_err(|e| e.to_string())??;
        engine::resolve(app, &settings, "yt-dlp")?;
        engine::resolve(app, &settings, "ffmpeg")?;
        let id = uuid::Uuid::new_v4().to_string();
        let manifests = self.store.root.join("Playlists");
        tokio::fs::create_dir_all(&manifests)
            .await
            .map_err(|e| e.to_string())?;
        let manifest = manifests.join(format!("{id}.jsonl"));
        let discovery = if r.playlist {
            match playlist::discover(app, &settings, &url, &manifest, &self.stop).await {
                Ok(v) => v,
                Err(e) => {
                    let _ = tokio::fs::remove_file(&manifest).await;
                    return Err(e);
                }
            }
        } else {
            let media = engine::analyze(app, &settings, &url, false).await?;
            let item = PlaylistItem {
                url: media.url,
                title: media.title.clone(),
                duration: media.duration,
                index: 1,
                source_id: media.id,
            };
            tokio::fs::write(
                &manifest,
                format!("{}\n", serde_json::to_string(&item).unwrap()),
            )
            .await
            .map_err(|e| e.to_string())?;
            playlist::Discovery {
                title: media.title,
                count: 1,
                playlist: false,
                duration: media.duration.unwrap_or(0.0),
            }
        };
        let folder = PathBuf::from(&settings.download_dir).join(if discovery.playlist {
            "Playlists"
        } else if r.media_type == "audio" {
            "Audio"
        } else {
            "Video"
        });
        let folder = if discovery.playlist {
            folder.join(format!(
                "{} [{}]",
                storage::safe_name(&discovery.title),
                &id[..8]
            ))
        } else {
            folder
        };
        let f = folder.clone();
        let safety = settings.disk_safety_mb * 1024 * 1024;
        tokio::task::spawn_blocking(move || {
            let free = storage::writable(&f.to_string_lossy())?;
            if free < safety {
                return Err::<(), String>("Insufficient disk space to admit downloads".into());
            }
            Ok(())
        })
        .await
        .map_err(|e| e.to_string())??;
        if !discovery.playlist {
            let (entries, _) = playlist::read_window(manifest.clone(), 0, 1).await?;
            let job = self.make_job(
                &entries[0],
                None,
                &settings,
                &r.media_type,
                &r.quality,
                &folder,
            );
            let key = job.identity.clone();
            let store = self.store.clone();
            let exists = tokio::task::spawn_blocking(move || store.exists_identity(&key))
                .await
                .map_err(|e| e.to_string())?;
            if exists && ["skip", "resume"].contains(&settings.duplicate.as_str()) {
                let _ = tokio::fs::remove_file(manifest).await;
                return Err("This media and quality are already queued or saved. Resume its history entry, or choose Rename to request a separate copy.".into());
            }
            self.store.db.lock().unwrap().jobs.push(job.clone());
            self.persist().await?;
            let _ = tokio::fs::remove_file(manifest).await;
            self.store.emit(app, "state:changed");
            self.wake();
            return Ok(vec![job.id]);
        }
        let batch = Batch {
            id: id.clone(),
            title: discovery.title,
            job_ids: vec![],
            create_zip: r.create_zip,
            status: if settings.auto_start {
                Status::Queued
            } else {
                Status::Paused
            },
            progress: 0.0,
            output_path: None,
            error: None,
            folder: folder.to_string_lossy().into(),
            settings,
            total_items: discovery.count,
            discovered: 0,
            completed_items: 0,
            failed_items: 0,
            cancelled_items: 0,
            spool: manifest.to_string_lossy().into(),
            cursor: 0,
            media_type: r.media_type,
            quality: r.quality,
            throughput: 0.0,
            eta: None,
        };
        if discovery.duration > 0.0 && batch.media_type == "audio" {
            let path = folder.clone();
            let needed = (discovery.duration * 40_000.0) as u64;
            let free = tokio::task::spawn_blocking(move || fs2::available_space(path))
                .await
                .map_err(|e| e.to_string())?
                .map_err(|e| e.to_string())?;
            if needed.saturating_add(safety) > free {
                return Err("Estimated playlist audio exceeds free disk space".into());
            }
        }
        self.store.db.lock().unwrap().batches.push(batch);
        self.persist().await?;
        self.store.emit(app, "state:changed");
        self.wake();
        Ok(vec![id])
    }
    fn make_job(
        &self,
        item: &PlaylistItem,
        batch: Option<&Batch>,
        s: &Settings,
        kind: &str,
        quality: &str,
        folder: &std::path::Path,
    ) -> Job {
        let id = if let Some(b) = batch {
            format!("{}-{:05}", b.id, item.index)
        } else {
            uuid::Uuid::new_v4().to_string()
        };
        Job {
            id: id.clone(),
            url: item.url.clone(),
            media_type: kind.into(),
            title: item.title.clone(),
            playlist_id: batch.map(|b| b.id.clone()),
            playlist_index: batch.map(|_| item.index),
            playlist_title: batch.map(|b| b.title.clone()),
            status: if s.auto_start {
                Status::Queued
            } else {
                Status::Paused
            },
            progress: 0.0,
            speed: None,
            eta: None,
            downloaded_bytes: 0,
            total_bytes: None,
            output_path: None,
            error: None,
            created_at: now(),
            started_at: None,
            completed_at: None,
            duration: item.duration,
            quality: quality.into(),
            settings: s.clone(),
            output_dir: folder.to_string_lossy().into(),
            temp_dir: PathBuf::from(&s.temp_dir)
                .join(&id)
                .to_string_lossy()
                .into(),
            identity: download::queue::identity(&item.url, kind, quality, &s.container),
            priority: Priority::Normal,
            attempts: 0,
            retry_at: None,
            error_code: None,
            instantaneous_speed: None,
            staging: None,
        }
    }
    async fn stage(&self, app: &AppHandle) -> Result<(), String> {
        let (store, limit) = (
            self.store.clone(),
            self.store.db.lock().unwrap().settings.prefetch,
        );
        let pending = tokio::task::spawn_blocking(move || store.pending(limit))
            .await
            .map_err(|e| e.to_string())??;
        let mut restored = vec![];
        {
            let mut db = self.store.db.lock().unwrap();
            let mut live = db
                .jobs
                .iter()
                .filter(|j| {
                    !j.status.terminal()
                        && !matches!(j.status, Status::Paused | Status::Interrupted)
                })
                .count();
            for j in pending {
                if live >= limit {
                    break;
                }
                if !db.jobs.iter().any(|v| v.id == j.id) {
                    restored.push(j.clone());
                    db.jobs.push(j);
                    live += 1;
                }
            }
        }
        if !restored.is_empty() {
            let _ = app.emit("state:jobs-added", restored);
        }
        let batches = {
            let db = self.store.db.lock().unwrap();
            let live = db
                .jobs
                .iter()
                .filter(|j| {
                    !j.status.terminal()
                        && !matches!(j.status, Status::Paused | Status::Interrupted)
                })
                .count();
            if live >= limit {
                return Ok(());
            }
            let mut batches = db
                .batches
                .iter()
                .filter(|b| {
                    b.status == Status::Queued
                        && !b.spool.is_empty()
                        && b.discovered < b.total_items
                })
                .cloned()
                .collect::<Vec<_>>();
            batches.sort_by_key(|b| {
                db.jobs
                    .iter()
                    .filter(|j| j.playlist_id.as_deref() == Some(&b.id) && !j.status.terminal())
                    .count()
            });
            batches
        };
        for batch in batches {
            let live = self
                .store
                .db
                .lock()
                .unwrap()
                .jobs
                .iter()
                .filter(|j| {
                    !j.status.terminal()
                        && !matches!(j.status, Status::Paused | Status::Interrupted)
                })
                .count();
            if live >= limit {
                break;
            }
            let (entries, cursor) = playlist::read_window(
                batch.spool.clone().into(),
                batch.cursor,
                (limit - live).min(4),
            )
            .await?;
            if entries.is_empty() {
                return Err("Playlist manifest ended before its recorded count".into());
            }
            let mut jobs = vec![];
            for item in &entries {
                let mut job = self.make_job(
                    item,
                    Some(&batch),
                    &batch.settings,
                    &batch.media_type,
                    &batch.quality,
                    &PathBuf::from(&batch.folder),
                );
                job.status = Status::Queued;
                let key = job.identity.clone();
                let store = self.store.clone();
                let duplicate = tokio::task::spawn_blocking(move || store.exists_identity(&key))
                    .await
                    .map_err(|e| e.to_string())?;
                if (duplicate || jobs.iter().any(|j: &Job| j.identity == job.identity))
                    && batch.settings.duplicate != "rename"
                {
                    job.status = Status::Cancelled;
                    job.error=Some("Duplicate item already queued or saved. Use Rename to request another copy.".into());
                    job.error_code = Some("duplicate".into());
                    job.completed_at = Some(now());
                }
                jobs.push(job);
            }
            let additions = jobs.clone();
            {
                let mut db = self.store.db.lock().unwrap();
                let Some(current) = db.batches.iter_mut().find(|b| b.id == batch.id) else {
                    continue;
                };
                if current.status != Status::Queued {
                    continue;
                }
                current.cursor = cursor;
                current.discovered += entries.len();
                db.jobs.extend(jobs);
            }
            self.persist().await?;
            let _ = app.emit("state:jobs-added", additions);
        }
        Ok(())
    }
    async fn schedule(self: &Arc<Self>, app: &AppHandle) -> Result<(), String> {
        self.stage(app).await?;
        let s = self.store.db.lock().unwrap().settings.clone();
        let pressure = self.resources.metrics.lock().unwrap().pressure;
        let limit = self.adaptive.lock().unwrap().update(
            download::rate_limit::slots(&s),
            s.adaptive_concurrency,
            pressure,
            timestamp(),
        );
        for phase in [Phase::Process, Phase::Download] {
            loop {
                let (running, busy, host_active) = {
                    let running = self.running.lock().unwrap();
                    (
                        running.values().filter(|r| r.phase == phase).count(),
                        running.keys().cloned().collect::<HashSet<_>>(),
                        running
                            .values()
                            .filter(|r| r.phase == Phase::Download)
                            .count(),
                    )
                };
                if running
                    >= if phase == Phase::Download {
                        limit
                    } else {
                        s.ffmpeg_concurrency
                    }
                {
                    break;
                }
                let candidate = {
                    let db = self.store.db.lock().unwrap();
                    let now = timestamp();
                    let mut fair = self.fair.lock().unwrap();
                    let selected = fair.choose(&db.jobs, &busy, now, |j| {
                        let processing = j.staging.is_some();
                        (phase == Phase::Process) == processing
                            && j.playlist_id.as_ref().is_none_or(|id| {
                                db.batches
                                    .iter()
                                    .find(|b| &b.id == id)
                                    .is_none_or(|b| b.status == Status::Queued)
                            })
                            && (phase != Phase::Download
                                || (host_active < s.host_concurrency
                                    && self.adaptive.lock().unwrap().ready(host(j), now)))
                            && !db.jobs.iter().any(|other| {
                                other.id != j.id
                                    && other.identity == j.identity
                                    && busy.contains(&other.id)
                            })
                    });
                    selected.and_then(|id| db.jobs.iter().find(|j| j.id == id).cloned())
                };
                let Some(mut job) = candidate else {
                    break;
                };
                let slots = if phase == Phase::Download {
                    self.media_slots.clone()
                } else {
                    self.ffmpeg_slots.clone()
                };
                let Ok(permit) = slots.try_acquire_owned() else {
                    break;
                };
                let mut permits = vec![permit];
                if phase == Phase::Download {
                    let host_slot = self
                        .hosts
                        .lock()
                        .unwrap()
                        .entry(host(&job).into())
                        .or_insert_with(|| Arc::new(Semaphore::new(12)))
                        .clone();
                    let Ok(permit) = host_slot.try_acquire_owned() else {
                        break;
                    };
                    permits.push(permit);
                    let fragments = download::rate_limit::fragments(&s);
                    let Ok(permit) = self.fragments.clone().try_acquire_many_owned(fragments)
                    else {
                        break;
                    };
                    permits.push(permit);
                    job.settings.fragments = fragments;
                    job.settings.bandwidth_limit = s.bandwidth_limit;
                    job.settings.concurrency = s.concurrency;
                    job.settings.advanced_concurrency = s.advanced_concurrency;
                }
                let cancel = self.stop.child_token();
                {
                    let mut running = self.running.lock().unwrap();
                    let mut db = self.store.db.lock().unwrap();
                    let Some(current) = db.jobs.iter_mut().find(|j| j.id == job.id) else {
                        continue;
                    };
                    if matches!(
                        current.status,
                        Status::Paused | Status::Interrupted | Status::Cancelled
                    ) {
                        continue;
                    }
                    current.status = current.status.advance(if phase == Phase::Download {
                        Status::WaitingForWorker
                    } else {
                        Status::WaitingForProcessor
                    })?;
                    current.retry_at = None;
                    running.insert(
                        job.id.clone(),
                        Running {
                            phase,
                            cancel: cancel.clone(),
                            archive_cancel: Arc::new(AtomicBool::new(false)),
                            pid: None,
                            started: now(),
                        },
                    );
                }
                let work = Work {
                    job: job.clone(),
                    phase,
                    cancel,
                    _permits: permits,
                };
                let result = {
                    let channels = self.channels.lock().unwrap();
                    let channels = channels.as_ref().ok_or("Worker pool not started")?;
                    if phase == Phase::Download {
                        channels.download.try_send(work)
                    } else {
                        channels.processing.try_send(work)
                    }
                };
                if result.is_err() {
                    self.running.lock().unwrap().remove(&job.id);
                    self.update(&job.id, |j| j.status = Status::Queued);
                    break;
                }
                self.store.emit_job(app, &job.id, "state:job");
            }
        }
        self.schedule_archives(app).await?;
        Ok(())
    }
    fn update(&self, id: &str, f: impl FnOnce(&mut Job)) {
        let mut db = self.store.db.lock().unwrap();
        if let Some(j) = db.jobs.iter_mut().find(|j| j.id == id) {
            if !j.status.terminal()
                && !matches!(
                    j.status,
                    Status::Paused | Status::Interrupted | Status::Cancelling
                )
            {
                f(j);
            }
        }
    }
    fn trim(&self) {
        let mut db = self.store.db.lock().unwrap();
        let mut terminal = 0;
        db.jobs.reverse();
        db.jobs.retain(|j| {
            if j.status.terminal() {
                terminal += 1;
                terminal <= 200
            } else {
                true
            }
        });
        db.jobs.reverse();
    }
    pub fn diagnostics(&self) -> Diagnostics {
        self.metrics.lock().unwrap().clone()
    }
}
