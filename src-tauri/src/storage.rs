use crate::models::*;
use rusqlite::{params, Connection};
use std::{
    collections::{HashMap, HashSet},
    hash::{Hash, Hasher},
};
use std::{
    fs::{self, OpenOptions},
    io::Write,
    path::{Path, PathBuf},
    sync::Mutex,
};
use tauri::{AppHandle, Emitter};

pub struct Store {
    pub db: Mutex<Database>,
    pub root: PathBuf,
    sql: Mutex<Connection>,
    fingerprints: Mutex<HashMap<String, u64>>,
}
impl Store {
    pub fn music(&self) -> Result<Option<String>, String> {
        use rusqlite::OptionalExtension;
        self.sql
            .lock()
            .unwrap()
            .query_row("SELECT data FROM state WHERE name='music-v1'", [], |r| {
                r.get(0)
            })
            .optional()
            .map_err(|e| e.to_string())
    }
    pub fn save_music(&self, data: &str) -> Result<(), String> {
        self.sql.lock().unwrap().execute("INSERT INTO state(name,data) VALUES('music-v1',?1) ON CONFLICT(name) DO UPDATE SET data=excluded.data", [data]).map_err(|e|e.to_string())?;
        Ok(())
    }
    pub fn load(root: PathBuf) -> Result<Self, String> {
        fs::create_dir_all(&root).map_err(|e| e.to_string())?;
        let path = root.join("state.json");
        let sql_path = root.join("state.sqlite3");
        // A crash between schema creation and the first committed migration must
        // not hide the legacy JSON on the next launch.
        let migrate = !sql_path.exists()
            || Connection::open(&sql_path).ok().is_some_and(|c| {
                c.query_row("SELECT data FROM state WHERE name='settings'", [], |r| {
                    r.get::<_, String>(0)
                })
                .is_err()
            });
        let mut db: Database = if migrate
            && (path.exists() || root.join("state.backup.json").exists())
        {
            let bytes = fs::read(&path).unwrap_or_default();
            serde_json::from_slice(&bytes).or_else(|_| {
                fs::read(root.join("state.backup.json")).ok().and_then(|b| serde_json::from_slice(&b).ok()).ok_or_else(|| "Local state is damaged; preserve the files in the application data folder before recovery.".to_string())
            })?
        } else {
            Database::default()
        };
        for job in &mut db.jobs {
            if job.identity.is_empty() {
                job.identity = crate::download::queue::identity(
                    &job.url,
                    &job.media_type,
                    &job.quality,
                    &job.settings.container,
                );
            }
            if job.status.active() || matches!(job.status, Status::Queued | Status::RetryWaiting) {
                job.status = Status::Interrupted;
                job.error = Some("Interrupted by application shutdown. Resume to continue retained partial files.".into());
            }
        }
        for batch in &mut db.batches {
            if !batch.status.terminal() && batch.status != Status::Paused {
                batch.status = Status::Interrupted;
            }
        }
        let sql = Connection::open(&sql_path).map_err(|e| e.to_string())?;
        sql.busy_timeout(std::time::Duration::from_secs(5))
            .map_err(|e| e.to_string())?;
        sql.execute_batch("PRAGMA journal_mode=WAL; PRAGMA synchronous=FULL; CREATE TABLE IF NOT EXISTS state(name TEXT PRIMARY KEY,data TEXT NOT NULL); CREATE TABLE IF NOT EXISTS jobs(id TEXT PRIMARY KEY,playlist_id TEXT,status TEXT NOT NULL,created TEXT NOT NULL,identity TEXT NOT NULL,data TEXT NOT NULL); CREATE INDEX IF NOT EXISTS job_playlist ON jobs(playlist_id,status); CREATE INDEX IF NOT EXISTS job_status ON jobs(status,created); CREATE INDEX IF NOT EXISTS job_identity ON jobs(identity,status); CREATE TABLE IF NOT EXISTS batches(id TEXT PRIMARY KEY,data TEXT NOT NULL);").map_err(|e|e.to_string())?;
        if !migrate {
            if let Ok(data) =
                sql.query_row("SELECT data FROM state WHERE name='settings'", [], |r| {
                    r.get::<_, String>(0)
                })
            {
                db.settings = serde_json::from_str(&data).map_err(|e| e.to_string())?;
            }
            let mut query=sql.prepare("SELECT id,data FROM jobs WHERE status NOT IN ('completed','failed','cancelled','skipped','paused','interrupted')").map_err(|e|e.to_string())?;
            let records = query
                .query_map([], |r| Ok((r.get::<_, String>(0)?, r.get::<_, String>(1)?)))
                .map_err(|e| e.to_string())?;
            for row in records {
                let (id, data) = row.map_err(|e| e.to_string())?;
                let mut job: Job = serde_json::from_str(&data).map_err(|e| e.to_string())?;
                job.status = Status::Interrupted;
                job.speed = None;
                job.eta = None;
                job.error =
                    Some("Interrupted. Resume to reuse retained media and partial files.".into());
                sql.execute(
                    "UPDATE jobs SET status='interrupted',data=?2 WHERE id=?1",
                    params![id, serde_json::to_string(&job).unwrap()],
                )
                .map_err(|e| e.to_string())?;
            }
            let mut query = sql
                .prepare("SELECT data FROM jobs ORDER BY created DESC LIMIT 232")
                .map_err(|e| e.to_string())?;
            for row in query
                .query_map([], |r| r.get::<_, String>(0))
                .map_err(|e| e.to_string())?
            {
                db.jobs.push(
                    serde_json::from_str(&row.map_err(|e| e.to_string())?)
                        .map_err(|e| e.to_string())?,
                );
            }
            db.jobs.reverse();
            let mut query = sql
                .prepare("SELECT data FROM batches WHERE json_extract(data,'$.status') NOT IN ('completed','failed','cancelled','skipped') UNION ALL SELECT data FROM (SELECT data FROM batches WHERE json_extract(data,'$.status') IN ('completed','failed','cancelled','skipped') ORDER BY rowid DESC LIMIT 200)")
                .map_err(|e| e.to_string())?;
            for row in query
                .query_map([], |r| r.get::<_, String>(0))
                .map_err(|e| e.to_string())?
            {
                let mut b: Batch = serde_json::from_str(&row.map_err(|e| e.to_string())?)
                    .map_err(|e| e.to_string())?;
                if !b.status.terminal() && b.status != Status::Paused {
                    b.status = Status::Interrupted;
                }
                db.batches.push(b);
            }
        }
        for b in &mut db.batches {
            if b.total_items == 0 {
                b.total_items = b.job_ids.len();
                b.discovered = b.total_items;
            }
        }
        let store = Self {
            db: Mutex::new(db),
            root,
            sql: Mutex::new(sql),
            fingerprints: Mutex::new(HashMap::new()),
        };
        store.save()?;
        Ok(store)
    }
    pub fn save(&self) -> Result<(), String> {
        let snapshot = self.db.lock().map_err(|e| e.to_string())?.clone();
        let mut sql = self.sql.lock().map_err(|e| e.to_string())?;
        let mut cache = self.fingerprints.lock().map_err(|e| e.to_string())?;
        let tx = sql.transaction().map_err(|e| e.to_string())?;
        let mut changed = Vec::new();
        let mut hash = |key: String, data: &str| {
            let mut h = std::collections::hash_map::DefaultHasher::new();
            data.hash(&mut h);
            let n = h.finish();
            if cache.get(&key) == Some(&n) {
                false
            } else {
                changed.push((key, n));
                true
            }
        };
        let settings = serde_json::to_string(&snapshot.settings).map_err(|e| e.to_string())?;
        if hash("settings".into(), &settings) {
            tx.execute(
                "INSERT OR REPLACE INTO state VALUES('settings',?1)",
                [settings],
            )
            .map_err(|e| e.to_string())?;
        }
        for j in snapshot.jobs {
            let data = serde_json::to_string(&j).map_err(|e| e.to_string())?;
            if hash(format!("job:{}", j.id), &data) {
                let status = serde_json::to_value(&j.status)
                    .unwrap()
                    .as_str()
                    .unwrap()
                    .to_string();
                tx.execute(
                    "INSERT OR REPLACE INTO jobs VALUES(?1,?2,?3,?4,?5,?6)",
                    params![j.id, j.playlist_id, status, j.created_at, j.identity, data],
                )
                .map_err(|e| e.to_string())?;
            }
        }
        for b in snapshot.batches {
            let data = serde_json::to_string(&b).map_err(|e| e.to_string())?;
            if hash(format!("batch:{}", b.id), &data) {
                tx.execute(
                    "INSERT OR REPLACE INTO batches VALUES(?1,?2)",
                    params![b.id, data],
                )
                .map_err(|e| e.to_string())?;
            }
        }
        tx.commit().map_err(|e| e.to_string())?;
        for (k, v) in changed {
            cache.insert(k, v);
        }
        Ok(())
    }
    pub fn lookup(&self, id: &str) -> Result<Job, String> {
        let data = self
            .sql
            .lock()
            .map_err(|e| e.to_string())?
            .query_row("SELECT data FROM jobs WHERE id=?1", [id], |r| {
                r.get::<_, String>(0)
            })
            .map_err(|_| "Download not found".to_string())?;
        serde_json::from_str(&data).map_err(|e| e.to_string())
    }
    pub fn history(&self, offset: usize, limit: usize) -> Result<Vec<Job>, String> {
        let sql = self.sql.lock().map_err(|e| e.to_string())?;
        let mut q = sql
            .prepare("SELECT data FROM jobs ORDER BY created DESC LIMIT ?1 OFFSET ?2")
            .map_err(|e| e.to_string())?;
        let result = q
            .query_map(params![limit.min(200), offset], |r| r.get::<_, String>(0))
            .map_err(|e| e.to_string())?
            .map(|r| {
                serde_json::from_str(&r.map_err(|e| e.to_string())?).map_err(|e| e.to_string())
            })
            .collect();
        result
    }
    pub fn pending(&self, limit: usize) -> Result<Vec<Job>, String> {
        let sql = self.sql.lock().map_err(|e| e.to_string())?;
        let mut q=sql.prepare("SELECT data FROM (SELECT data,created,ROW_NUMBER() OVER (PARTITION BY COALESCE(playlist_id,id) ORDER BY created) AS position FROM jobs WHERE status IN ('queued','retrywaiting','waitingforworker','downloadcomplete','waitingforprocessor')) ORDER BY position,created LIMIT ?1").map_err(|e|e.to_string())?;
        let result = q
            .query_map([limit], |r| r.get::<_, String>(0))
            .map_err(|e| e.to_string())?
            .map(|r| {
                serde_json::from_str(&r.map_err(|e| e.to_string())?).map_err(|e| e.to_string())
            })
            .collect();
        result
    }
    pub fn batch_summary(&self, id: &str) -> Result<(usize, usize, usize, usize), String> {
        self.sql.lock().map_err(|e|e.to_string())?.query_row("SELECT COUNT(*),COALESCE(SUM(status IN ('completed','skipped')),0),COALESCE(SUM(status='failed'),0),COALESCE(SUM(status='cancelled'),0) FROM jobs WHERE playlist_id=?1",[id],|r|Ok((r.get(0)?,r.get(1)?,r.get(2)?,r.get(3)?))).map_err(|e|e.to_string())
    }
    pub fn batch_files(&self, id: &str) -> Result<Vec<(usize, PathBuf)>, String> {
        let sql = self.sql.lock().map_err(|e| e.to_string())?;
        let mut q = sql
            .prepare(
                "SELECT data FROM jobs WHERE playlist_id=?1 AND status IN ('completed','skipped')",
            )
            .map_err(|e| e.to_string())?;
        let mut files = vec![];
        for row in q
            .query_map([id], |r| r.get::<_, String>(0))
            .map_err(|e| e.to_string())?
        {
            let job: Job = serde_json::from_str(&row.map_err(|e| e.to_string())?)
                .map_err(|e| e.to_string())?;
            if let Some(p) = job.output_path {
                files.push((job.playlist_index.unwrap_or(0), p.into()));
            }
        }
        Ok(files)
    }
    pub fn transform_jobs(
        &self,
        batch: Option<&str>,
        from: &[Status],
        to: Status,
    ) -> Result<(), String> {
        let mut sql = self.sql.lock().map_err(|e| e.to_string())?;
        let tx = sql.transaction().map_err(|e| e.to_string())?;
        {
            let mut q = tx
                .prepare("SELECT id,data FROM jobs WHERE (?1 IS NULL OR playlist_id=?1)")
                .map_err(|e| e.to_string())?;
            for row in q
                .query_map([batch], |r| {
                    Ok((r.get::<_, String>(0)?, r.get::<_, String>(1)?))
                })
                .map_err(|e| e.to_string())?
            {
                let (id, data) = row.map_err(|e| e.to_string())?;
                let mut j: Job = serde_json::from_str(&data).map_err(|e| e.to_string())?;
                if from.contains(&j.status) {
                    j.status = to.clone();
                    j.retry_at = None;
                    j.error = None;
                    if to == Status::Queued {
                        j.attempts = 0;
                    }
                    tx.execute(
                        "UPDATE jobs SET status=?2,data=?3 WHERE id=?1",
                        params![
                            id,
                            serde_json::to_value(&to).unwrap().as_str().unwrap(),
                            serde_json::to_string(&j).unwrap()
                        ],
                    )
                    .map_err(|e| e.to_string())?;
                    self.fingerprints
                        .lock()
                        .unwrap()
                        .remove(&format!("job:{id}"));
                }
            }
        }
        tx.commit().map_err(|e| e.to_string())
    }
    pub fn totals(&self) -> Result<(usize, usize, usize, usize, u64), String> {
        self.sql.lock().map_err(|e|e.to_string())?.query_row("SELECT COUNT(*),COALESCE(SUM(status IN ('completed','skipped')),0),COALESCE(SUM(status='failed'),0),COALESCE(SUM(status='cancelled'),0),COALESCE(SUM(json_extract(data,'$.downloadedBytes')),0) FROM jobs",[],|r|Ok((r.get(0)?,r.get(1)?,r.get(2)?,r.get(3)?,r.get(4)?))).map_err(|e|e.to_string())
    }
    pub fn exists_identity(&self, key: &str) -> bool {
        self.sql.lock().ok().and_then(|s|s.query_row("SELECT 1 FROM jobs WHERE identity=?1 AND status NOT IN ('failed','cancelled') LIMIT 1",[key],|r|r.get::<_,u32>(0)).ok()).is_some()
    }
    pub fn delete_job(&self, id: &str) -> Result<(), String> {
        self.sql
            .lock()
            .map_err(|e| e.to_string())?
            .execute("DELETE FROM jobs WHERE id=?1", [id])
            .map_err(|e| e.to_string())?;
        self.fingerprints
            .lock()
            .unwrap()
            .remove(&format!("job:{id}"));
        Ok(())
    }
    pub fn clear_finished(&self) -> Result<(), String> {
        let keep = self
            .db
            .lock()
            .unwrap()
            .batches
            .iter()
            .map(|b| b.id.clone())
            .collect::<HashSet<_>>();
        let sql = self.sql.lock().map_err(|e| e.to_string())?;
        let mut q = sql
            .prepare("SELECT id FROM batches")
            .map_err(|e| e.to_string())?;
        let ids = q
            .query_map([], |r| r.get::<_, String>(0))
            .map_err(|e| e.to_string())?;
        for id in ids {
            let id = id.map_err(|e| e.to_string())?;
            if !keep.contains(&id) {
                sql.execute("DELETE FROM batches WHERE id=?1", [id])
                    .map_err(|e| e.to_string())?;
            }
        }
        drop(q);
        drop(sql);
        self.sql.lock().map_err(|e|e.to_string())?.execute("DELETE FROM jobs WHERE status IN ('completed','failed','cancelled','skipped') AND (playlist_id IS NULL OR playlist_id NOT IN (SELECT id FROM batches))",[]).map_err(|e|e.to_string())?;
        Ok(())
    }
    pub fn emit(&self, app: &AppHandle, event: &str) {
        let db = self.db.lock().unwrap().clone();
        let _ = event;
        let _ = app.emit("state:changed", &db);
    }
    pub fn emit_job(&self, app: &AppHandle, id: &str, event: &str) {
        if let Some(job) = self
            .db
            .lock()
            .unwrap()
            .jobs
            .iter()
            .find(|j| j.id == id)
            .cloned()
        {
            let _ = event;
            let _ = app.emit("state:job", serde_json::json!({"id":job.id,"title":job.title,"status":job.status,"progress":job.progress,"speed":job.speed,"instantaneousSpeed":job.instantaneous_speed,"eta":job.eta,"downloadedBytes":job.downloaded_bytes,"totalBytes":job.total_bytes,"outputPath":job.output_path,"error":job.error,"errorCode":job.error_code,"attempts":job.attempts,"retryAt":job.retry_at,"completedAt":job.completed_at,"startedAt":job.started_at,"priority":job.priority}));
        }
    }
    pub fn emit_batch(&self, app: &AppHandle, id: &str) {
        if let Some(batch) = self
            .db
            .lock()
            .unwrap()
            .batches
            .iter()
            .find(|b| b.id == id)
            .cloned()
        {
            let _ = app.emit("state:batch", &batch);
        }
    }
    pub fn log(&self, message: &str) {
        let dir = self.root.join("Logs");
        let _ = fs::create_dir_all(&dir);
        let path = dir.join("ythub.log");
        if fs::metadata(&path)
            .map(|m| m.len() > 5_000_000)
            .unwrap_or(false)
        {
            let _ = fs::rename(&path, dir.join("ythub.previous.log"));
        }
        if let Ok(mut file) = OpenOptions::new().create(true).append(true).open(path) {
            let _ = writeln!(file, "{} {message}", now());
        }
    }
}
pub fn writable(path: &str) -> Result<u64, String> {
    let p = Path::new(path);
    if !p.is_absolute() {
        return Err("Choose an absolute folder path.".into());
    }
    fs::create_dir_all(p).map_err(|e| format!("Cannot create folder: {e}"))?;
    let probe = p.join(format!(".ythub-write-{}", uuid::Uuid::new_v4()));
    fs::write(&probe, b"").map_err(|e| format!("Folder is not writable: {e}"))?;
    fs::remove_file(probe).map_err(|e| e.to_string())?;
    fs2::available_space(p).map_err(|e| format!("Cannot read free disk space: {e}"))
}
pub fn safe_name(name: &str) -> String {
    let mut clean: String = name
        .chars()
        .map(|c| {
            if c.is_control() || "<>:\"/\\|?*".contains(c) {
                '_'
            } else {
                c
            }
        })
        .take(100)
        .collect();
    clean = clean.trim().trim_end_matches(['.', ' ']).to_string();
    let base = clean.split('.').next().unwrap_or("").to_uppercase();
    if clean.is_empty() {
        clean = "Untitled".into();
    }
    if [
        "CON", "PRN", "AUX", "NUL", "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8",
        "COM9", "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9",
    ]
    .contains(&base.as_str())
    {
        clean.insert(0, '_');
    }
    clean
}
pub fn validate_settings(s: &Settings) -> Result<(), String> {
    if !(1..=if s.advanced_concurrency { 12 } else { 6 }).contains(&s.concurrency)
        || s.retries > 20
        || !(1..=8).contains(&s.fragments)
        || !(5..=300).contains(&s.socket_timeout)
        || !(1..=4).contains(&s.ffmpeg_concurrency)
        || !(1..=12).contains(&s.host_concurrency)
        || !(1..=10).contains(&s.progress_hz)
        || !(1..=60).contains(&s.retry_backoff)
        || !(64..=4096).contains(&s.disk_safety_mb)
        || !(12..=64).contains(&s.fragment_budget)
        || !(8..=64).contains(&s.prefetch)
        || (s.bandwidth_limit > 0 && s.bandwidth_limit < 65536)
    {
        return Err(
            "Concurrency, retry, fragment, or timeout setting is outside the supported range."
                .into(),
        );
    }
    if !["dark", "light", "system"].contains(&s.theme.as_str())
        || !["mp4", "mkv"].contains(&s.container.as_str())
        || !["compatible", "quality"].contains(&s.codec.as_str())
        || !["skip", "overwrite", "rename", "resume"].contains(&s.duplicate.as_str())
        || !["normal", "verbose"].contains(&s.logging.as_str())
    {
        return Err("Invalid settings option.".into());
    }
    for template in [&s.filename_template, &s.playlist_template] {
        if template.is_empty()
            || template.len() > 250
            || template.contains(['/', '\\', ':'])
            || template.contains("..")
            || !template.ends_with(".%(ext)s")
        {
            return Err("Filename templates must be a filename only, end in .%(ext)s, and contain no traversal or path separators.".into());
        }
    }
    for p in [&s.download_dir, &s.temp_dir, &s.zip_dir] {
        writable(p)?;
    }
    if !s.cookies_file.is_empty() && !Path::new(&s.cookies_file).is_file() {
        return Err("Cookies file does not exist.".into());
    }
    if !s.proxy.is_empty() {
        let p = url::Url::parse(&s.proxy).map_err(|_| "Invalid proxy URL")?;
        if !["http", "https", "socks4", "socks5", "socks5h"].contains(&p.scheme()) {
            return Err("Unsupported proxy scheme.".into());
        }
    }
    crate::engine::validate_quality("audio", &s.audio_quality)?;
    crate::engine::validate_quality("video", &s.video_quality)?;
    Ok(())
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn restart_of_1000_jobs_is_bounded_and_retains_staging() {
        let d = tempfile::tempdir().unwrap();
        let store = Store::load(d.path().into()).unwrap();
        {
            let mut db = store.db.lock().unwrap();
            for i in 0..1000 {
                let mut j = sample_job();
                j.id = format!("job-{i}");
                j.status = Status::Converting;
                j.staging = Some(StagedMedia {
                    files: vec!["retained.webm".into()],
                    ..Default::default()
                });
                db.jobs.push(j);
            }
        }
        store.save().unwrap();
        drop(store);
        let store = Store::load(d.path().into()).unwrap();
        assert_eq!(store.totals().unwrap().0, 1000);
        assert_eq!(store.db.lock().unwrap().jobs.len(), 232);
        let job = store.lookup("job-0").unwrap();
        assert_eq!(job.status, Status::Interrupted);
        assert!(job.staging.is_some());
        store
            .transform_jobs(None, &[Status::Interrupted], Status::Queued)
            .unwrap();
        assert_eq!(store.pending(32).unwrap().len(), 32);
        assert_eq!(store.lookup("job-0").unwrap().status, Status::Queued);
    }
    #[test]
    fn uncommitted_sql_transaction_rolls_back() {
        let d = tempfile::tempdir().unwrap();
        let store = Store::load(d.path().into()).unwrap();
        let j = sample_job();
        let id = j.id.clone();
        store.db.lock().unwrap().jobs.push(j);
        store.save().unwrap();
        drop(store);
        {
            let sql = Connection::open(d.path().join("state.sqlite3")).unwrap();
            sql.execute_batch("BEGIN IMMEDIATE; DELETE FROM jobs;")
                .unwrap();
        }
        let store = Store::load(d.path().into()).unwrap();
        assert_eq!(store.lookup(&id).unwrap().status, Status::Interrupted);
    }
    #[test]
    fn missing_primary_recovers_valid_backup() {
        let d = tempfile::tempdir().unwrap();
        let mut legacy = Database::default();
        legacy.settings.concurrency = 3;
        fs::write(
            d.path().join("state.backup.json"),
            serde_json::to_vec(&legacy).unwrap(),
        )
        .unwrap();
        assert_eq!(
            Store::load(d.path().into())
                .unwrap()
                .db
                .lock()
                .unwrap()
                .settings
                .concurrency,
            3
        );
    }
    #[test]
    fn windows_names() {
        assert_eq!(safe_name("CON.txt"), "_CON.txt");
        assert_eq!(safe_name("你好: song. "), "你好_ song");
        assert_eq!(safe_name(""), "Untitled");
        assert!(safe_name(&"a".repeat(300)).len() <= 100);
    }
    #[test]
    fn absolute_writable_path() {
        let d = tempfile::tempdir().unwrap();
        assert!(writable(d.path().to_str().unwrap()).unwrap() > 0);
        assert!(writable("../escape").is_err());
    }
    #[test]
    fn settings_roundtrip() {
        let s = Settings::default();
        let round: Settings = serde_json::from_str(&serde_json::to_string(&s).unwrap()).unwrap();
        assert_eq!(round.concurrency, 2);
        assert_eq!(round.audio_quality, "320");
    }
    #[test]
    fn interrupted_jobs_are_paused() {
        assert!(Status::Downloading.active());
        assert!(!Status::Paused.active());
        assert!(Status::Failed.terminal());
    }
    #[test]
    fn persistence_roundtrip() {
        let d = tempfile::tempdir().unwrap();
        let store = Store::load(d.path().into()).unwrap();
        store.db.lock().unwrap().settings.concurrency = 3;
        store.save().unwrap();
        assert_eq!(
            Store::load(d.path().into())
                .unwrap()
                .db
                .lock()
                .unwrap()
                .settings
                .concurrency,
            3
        );
    }
}
