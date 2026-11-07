//! Authentication secrets are stored by the operating system, never in settings.
fn entry(key: &str) -> Result<keyring::Entry, String> {
    if !key.starts_with("sb-")
        || key.len() > 200
        || !key.chars().all(|c| c.is_ascii_alphanumeric() || c == '-')
    {
        return Err("Invalid authentication storage key".into());
    }
    keyring::Entry::new("dev.ravindran.ythub.auth", key)
        .map_err(|_| "Secure credential storage unavailable".into())
}
#[tauri::command]
pub async fn account_secret_read(key: String) -> Result<Option<String>, String> {
    tauri::async_runtime::spawn_blocking(move || match entry(&key)?.get_password() {
        Ok(value) => Ok(Some(value)),
        Err(keyring::Error::NoEntry) => Ok(None),
        Err(_) => Err("Unable to read secure session".into()),
    })
    .await
    .map_err(|_| "Credential task failed".to_string())?
}
#[tauri::command]
pub async fn account_secret_write(key: String, value: Option<String>) -> Result<(), String> {
    tauri::async_runtime::spawn_blocking(move || {
        let item = entry(&key)?;
        match value {
            Some(value) if value.len() <= 65536 => item
                .set_password(&value)
                .map_err(|_| "Unable to save secure session".into()),
            Some(_) => Err("Session exceeds storage limit".into()),
            None => match item.delete_credential() {
                Ok(()) | Err(keyring::Error::NoEntry) => Ok(()),
                Err(_) => Err("Unable to remove secure session".into()),
            },
        }
    })
    .await
    .map_err(|_| "Credential task failed".to_string())?
}

fn media_path(owner: &str, object: &str) -> Result<std::path::PathBuf, String> {
    uuid::Uuid::parse_str(owner).map_err(|_| "Invalid account".to_string())?;
    uuid::Uuid::parse_str(object).map_err(|_| "Invalid object".to_string())?;
    Ok(dirs::data_local_dir()
        .ok_or("Application storage unavailable")?
        .join("YTHub/cloud-media")
        .join(owner)
        .join(object))
}
#[tauri::command]
pub async fn cloud_media_available(
    owner: String,
    object: String,
    checksum: String,
    bytes: u64,
) -> Result<bool, String> {
    tauri::async_runtime::spawn_blocking(move || {
        use sha2::{Digest, Sha256};
        let path = media_path(&owner, &object)?;
        let data = match std::fs::read(path) {
            Ok(data) => data,
            Err(_) => return Ok(false),
        };
        Ok(data.len() as u64 == bytes && format!("{:x}", Sha256::digest(&data)) == checksum)
    })
    .await
    .map_err(|_| "Verification task failed".to_string())?
}
#[tauri::command]
pub async fn cloud_media_save(
    owner: String,
    object: String,
    checksum: String,
    data: Vec<u8>,
) -> Result<(), String> {
    tauri::async_runtime::spawn_blocking(move || {
        use sha2::{Digest, Sha256};
        if data.is_empty()
            || data.len() > 26214400
            || format!("{:x}", Sha256::digest(&data)) != checksum
        {
            return Err("File integrity verification failed".into());
        }
        let path = media_path(&owner, &object)?;
        std::fs::create_dir_all(path.parent().unwrap()).map_err(|_| "Storage unavailable")?;
        let temporary = path.with_extension(uuid::Uuid::new_v4().to_string());
        let result = (|| {
            use std::io::Write;
            let mut file = std::fs::File::create(&temporary)?;
            file.write_all(&data)?;
            file.sync_all()?;
            std::fs::rename(&temporary, &path)
        })();
        if result.is_err() {
            let _ = std::fs::remove_file(&temporary);
        }
        result.map_err(|_| "Could not save verified media".into())
    })
    .await
    .map_err(|_| "Storage task failed".to_string())?
}
