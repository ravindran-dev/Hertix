use super::resource_manager::ResourceManager;
use futures_util::StreamExt;
use std::{
    path::PathBuf,
    process::Stdio,
    time::{Duration, Instant},
};
use tokio::{io::BufReader, process::Command};
use tokio_util::{
    codec::{FramedRead, LinesCodec},
    sync::CancellationToken,
};
pub struct Output {
    pub lines: Vec<String>,
    pub exit_code: Option<i32>,
}
pub async fn run(
    mut command: Command,
    cancel: &CancellationToken,
    paths: Vec<PathBuf>,
    safety: u64,
    timeout: Duration,
    mut line: impl FnMut(&str) + Send,
) -> Result<Output, String> {
    if cancel.is_cancelled() {
        return Err("Cancelled".into());
    }
    command.stdout(Stdio::piped()).stderr(Stdio::piped());
    let (mut child, _tree) = crate::process::spawn_managed(&mut command)
        .map_err(|e| format!("Cannot start process: {e}"))?;
    line(&format!("YTHUB_PID:{}", child.id().unwrap_or(0)));
    let mut out = FramedRead::new(
        BufReader::new(child.stdout.take().ok_or("No stdout")?),
        LinesCodec::new_with_max_length(256 * 1024),
    );
    let mut err = FramedRead::new(
        BufReader::new(child.stderr.take().ok_or("No stderr")?),
        LinesCodec::new_with_max_length(64 * 1024),
    );
    let (mut out_done, mut err_done) = (false, false);
    let mut errors = String::new();
    let mut lines = Vec::new();
    let start = Instant::now();
    let mut checks = tokio::time::interval(Duration::from_secs(2));
    checks.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
    while !out_done || !err_done {
        tokio::select! {biased;
            _=cancel.cancelled()=>{let _=child.kill().await;let _=child.wait().await;return Err("Cancelled; retained partial data.".into());}
            _=checks.tick()=>{if start.elapsed()>timeout {let _=child.kill().await;return Err("Process timed out; retained partial data.".into());} else if let Err(e)=ResourceManager::check(&paths,safety){let _=child.kill().await;return Err(e);}}
            item=out.next(),if !out_done=>match item{Some(Ok(value))=>{line(&value);if value.starts_with("YTHUB_FILE:") || value.starts_with("YTHUB_META:") {if lines.len()>=8{return Err("Engine output exceeded bounded result limit".into());}lines.push(value);}},Some(Err(e))=>return Err(format!("Invalid or excessive engine output: {e}")),None=>out_done=true},
            item=err.next(),if !err_done=>match item{Some(Ok(value))=>{line(&value);let remaining=8192usize.saturating_sub(errors.len());if remaining>0{errors.extend(value.chars().take(remaining.min(2000)));errors.push('\n');}},Some(Err(e))=>return Err(e.to_string()),None=>err_done=true},
        }
    }
    let status = tokio::select! {biased; _=cancel.cancelled()=>{let _=child.kill().await;return Err("Cancelled".into());},v=tokio::time::timeout(timeout.saturating_sub(start.elapsed()),child.wait())=>v.map_err(|_|"Process timed out")?.map_err(|e|e.to_string())?};
    if !status.success() {
        return Err(if errors.trim().is_empty() {
            format!("Process exited with {status}")
        } else {
            format!("Process exited with {status}: {errors}")
        });
    }
    line(&format!("YTHUB_EXIT:{}", status.code().unwrap_or(-1)));
    Ok(Output {
        lines,
        exit_code: status.code(),
    })
}
#[cfg(all(test, windows))]
mod tests {
    use super::*;
    #[tokio::test]
    async fn real_mock_process_progress_failure_and_bounded_output() {
        let script="Write-Output 'YTHUB_PROGRESS:{\"downloaded_bytes\":128,\"total_bytes\":256}'; Write-Output 'YTHUB_FILE:\"local file\"'; [Console]::Error.WriteLine('HTTP Error 429: Too Many Requests'); exit 1";
        let mut c = crate::engine::command("powershell.exe");
        c.args(["-NoProfile", "-NonInteractive", "-Command", script]);
        let mut measured = false;
        let error = run(
            c,
            &CancellationToken::new(),
            vec![],
            0,
            Duration::from_secs(10),
            |line| {
                measured |= crate::engine::parse_progress(line).is_some();
            },
        )
        .await
        .err()
        .unwrap();
        assert!(measured);
        assert_eq!(
            crate::errors::ErrorKind::classify(&error),
            crate::errors::ErrorKind::RateLimited
        );
        let mut c = crate::engine::command("powershell.exe");
        c.args([
            "-NoProfile",
            "-NonInteractive",
            "-Command",
            "[Console]::Out.WriteLine(('x' * 300000))",
        ]);
        assert!(run(
            c,
            &CancellationToken::new(),
            vec![],
            0,
            Duration::from_secs(10),
            |_| {}
        )
        .await
        .is_err());
    }
    #[tokio::test]
    async fn cancellation_kills_parent_and_inherited_child() {
        let mut c = crate::engine::command("powershell.exe");
        c.args(["-NoProfile","-NonInteractive","-Command","$fixtureChild = Start-Process powershell.exe -WindowStyle Hidden -ArgumentList '-NoProfile -NonInteractive -Command Start-Sleep 30' -PassThru; Write-Output ('CHILD:' + $fixtureChild.Id); Start-Sleep 30"]);
        let cancel = CancellationToken::new();
        let signal = cancel.clone();
        let mut pid = 0;
        // Cold PowerShell startup can exceed ten seconds on busy Windows hosts.
        // Keep the cancellation assertion unchanged; allow the fixture to start.
        let result = run(c, &cancel, vec![], 0, Duration::from_secs(60), |line| {
            if let Some(id) = line
                .strip_prefix("CHILD:")
                .and_then(|s| s.parse::<u32>().ok())
            {
                pid = id;
                signal.cancel();
            }
        })
        .await;
        assert!(result.is_err());
        assert!(pid > 0);
        for _ in 0..50 {
            if !alive(pid) {
                return;
            }
            tokio::time::sleep(Duration::from_millis(20)).await;
        }
        panic!("Managed descendant {pid} survived cancellation");
    }
    fn alive(pid: u32) -> bool {
        unsafe {
            use windows_sys::Win32::{
                Foundation::CloseHandle,
                System::Threading::{
                    GetExitCodeProcess, OpenProcess, PROCESS_QUERY_LIMITED_INFORMATION,
                },
            };
            let handle = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, 0, pid);
            if handle.is_null() {
                return false;
            }
            let mut code = 0;
            let ok = GetExitCodeProcess(handle, &mut code);
            CloseHandle(handle);
            ok != 0 && code == 259
        }
    }
}
