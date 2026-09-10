use fs2::FileExt;
use sha2::{Digest, Sha256};
use std::fmt;
use std::fs::{self, File, OpenOptions};
use std::io::{self, Read, Write};
use std::path::{Path, PathBuf};
use std::thread;
use std::time::{Duration, Instant};

const MODEL_DIR: &str = "builtin-model";
const LOCK_FILE: &str = ".install.lock";

#[derive(Clone, Debug)]
pub struct ModelSpec {
    pub file_name: &'static str,
    pub byte_len: u64,
    pub sha256: String,
}

#[derive(Clone, Copy, Debug)]
pub struct InstallOptions {
    pub lock_timeout: Duration,
    pub poll_interval: Duration,
}

impl Default for InstallOptions {
    fn default() -> Self {
        Self {
            lock_timeout: Duration::from_secs(30),
            poll_interval: Duration::from_millis(50),
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum FailureCategory {
    AssetMissing,
    Integrity,
    Storage,
    Permission,
    LockTimeout,
    Io,
}

impl FailureCategory {
    pub fn label(self) -> &'static str {
        match self {
            Self::AssetMissing => "this build does not contain the offline model",
            Self::Integrity => "the offline model failed integrity verification",
            Self::Storage => "there is not enough storage for the offline model",
            Self::Permission => "the offline model storage is not writable",
            Self::LockTimeout => "offline model setup timed out waiting for another process",
            Self::Io => "offline model setup encountered a storage error",
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum InstallState {
    Absent,
    Installing,
    Ready,
    Failed(FailureCategory),
}

impl InstallState {
    pub fn user_message(self) -> String {
        match self {
            Self::Absent | Self::Installing => "Preparing offline model".to_owned(),
            Self::Ready => "Ready for offline speech".to_owned(),
            Self::Failed(category) => {
                format!("Offline model unavailable: {}", category.label())
            }
        }
    }
}

#[derive(Debug)]
pub struct InstallError {
    category: FailureCategory,
    detail: String,
}

impl InstallError {
    fn new(category: FailureCategory, detail: impl Into<String>) -> Self {
        Self {
            category,
            detail: detail.into(),
        }
    }

    pub fn category(&self) -> FailureCategory {
        self.category
    }

    pub fn retryable(&self) -> bool {
        !matches!(self.category, FailureCategory::AssetMissing)
    }
}

impl fmt::Display for InstallError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(formatter, "{}: {}", self.category.label(), self.detail)
    }
}

impl std::error::Error for InstallError {}

struct LockGuard(File);

impl Drop for LockGuard {
    fn drop(&mut self) {
        let _ = self.0.unlock();
    }
}

pub fn ensure_installed<S, R, O>(
    files_dir: &Path,
    spec: &ModelSpec,
    mut open_asset: S,
    options: InstallOptions,
    observe: O,
) -> Result<PathBuf, InstallError>
where
    S: FnMut() -> io::Result<R>,
    R: Read,
    O: FnMut(InstallState),
{
    ensure_installed_with_writer(
        files_dir,
        spec,
        |destination| {
            let mut source = open_asset()?;
            io::copy(&mut source, destination)?;
            Ok(())
        },
        options,
        observe,
    )
}

pub fn ensure_installed_with_writer<S, O>(
    files_dir: &Path,
    spec: &ModelSpec,
    mut copy_asset: S,
    options: InstallOptions,
    mut observe: O,
) -> Result<PathBuf, InstallError>
where
    S: FnMut(&mut dyn Write) -> io::Result<()>,
    O: FnMut(InstallState),
{
    validate_spec(spec)?;
    let model_dir = files_dir.join(MODEL_DIR);
    fs::create_dir_all(&model_dir).map_err(io_error)?;
    let _guard = acquire_lock(&model_dir, options).inspect_err(|error| {
        observe(InstallState::Failed(error.category()));
    })?;

    let final_path = model_dir.join(spec.file_name);
    match verify_regular_file(&final_path, spec) {
        Ok(true) => {
            observe(InstallState::Ready);
            return Ok(final_path);
        }
        Ok(false) => observe(InstallState::Absent),
        Err(error) => {
            if final_path
                .symlink_metadata()
                .map(|m| m.file_type().is_symlink())
                .unwrap_or(false)
            {
                observe(InstallState::Failed(error.category()));
                return Err(error);
            }
            remove_regular_if_present(&final_path)?;
        }
    }

    observe(InstallState::Installing);
    let temp_path = model_dir.join(format!(".{}.installing", spec.file_name));
    remove_regular_if_present(&temp_path)?;

    let mut last_error = None;
    for attempt in 0..2 {
        match install_once(&model_dir, &temp_path, &final_path, spec, &mut copy_asset) {
            Ok(()) => {
                observe(InstallState::Ready);
                return Ok(final_path);
            }
            Err(error) => {
                let permanent = error.category() == FailureCategory::AssetMissing;
                last_error = Some(error);
                let _ = remove_regular_if_present(&temp_path);
                if permanent || attempt == 1 {
                    break;
                }
            }
        }
    }

    let error = last_error.expect("installation attempts produce an error");
    observe(InstallState::Failed(error.category()));
    Err(error)
}

fn validate_spec(spec: &ModelSpec) -> Result<(), InstallError> {
    let path = Path::new(spec.file_name);
    if spec.byte_len == 0
        || path.components().count() != 1
        || spec.file_name.starts_with('.')
        || spec.sha256.len() != 64
        || !spec
            .sha256
            .bytes()
            .all(|byte| byte.is_ascii_hexdigit() && !byte.is_ascii_uppercase())
    {
        return Err(InstallError::new(
            FailureCategory::Integrity,
            "invalid bundled model specification",
        ));
    }
    Ok(())
}

fn acquire_lock(model_dir: &Path, options: InstallOptions) -> Result<LockGuard, InstallError> {
    let lock = OpenOptions::new()
        .create(true)
        .truncate(false)
        .read(true)
        .write(true)
        .open(model_dir.join(LOCK_FILE))
        .map_err(io_error)?;
    let deadline = Instant::now() + options.lock_timeout;
    loop {
        match lock.try_lock_exclusive() {
            Ok(()) => return Ok(LockGuard(lock)),
            Err(error) if error.kind() == io::ErrorKind::WouldBlock => {
                if Instant::now() >= deadline {
                    return Err(InstallError::new(
                        FailureCategory::LockTimeout,
                        "bounded lock wait expired",
                    ));
                }
                thread::sleep(options.poll_interval);
            }
            Err(error) => return Err(io_error(error)),
        }
    }
}

fn install_once<S>(
    model_dir: &Path,
    temp_path: &Path,
    final_path: &Path,
    spec: &ModelSpec,
    copy_asset: &mut S,
) -> Result<(), InstallError>
where
    S: FnMut(&mut dyn Write) -> io::Result<()>,
{
    let mut temp = OpenOptions::new()
        .create_new(true)
        .write(true)
        .open(temp_path)
        .map_err(io_error)?;
    copy_asset(&mut temp).map_err(asset_error)?;
    temp.flush().map_err(io_error)?;
    temp.sync_all().map_err(io_error)?;
    drop(temp);

    if !verify_regular_file(temp_path, spec)? {
        let actual_len = temp_path.metadata().map_err(io_error)?.len();
        return Err(InstallError::new(
            FailureCategory::Integrity,
            format!("asset bytes do not match pinned metadata (length {actual_len})"),
        ));
    }
    fs::rename(temp_path, final_path).map_err(io_error)?;
    File::open(model_dir)
        .and_then(|directory| directory.sync_all())
        .map_err(io_error)?;
    if !verify_regular_file(final_path, spec)? {
        return Err(InstallError::new(
            FailureCategory::Integrity,
            "committed model failed verification",
        ));
    }
    Ok(())
}

fn verify_regular_file(path: &Path, spec: &ModelSpec) -> Result<bool, InstallError> {
    let metadata = match fs::symlink_metadata(path) {
        Ok(metadata) => metadata,
        Err(error) if error.kind() == io::ErrorKind::NotFound => return Ok(false),
        Err(error) => return Err(io_error(error)),
    };
    if !metadata.file_type().is_file() {
        return Err(InstallError::new(
            FailureCategory::Integrity,
            "model destination is not a regular file",
        ));
    }
    if metadata.len() != spec.byte_len {
        return Err(InstallError::new(
            FailureCategory::Integrity,
            "model length differs from pinned metadata",
        ));
    }
    let mut file = File::open(path).map_err(io_error)?;
    let mut digest = Sha256::new();
    io::copy(&mut file, &mut digest).map_err(io_error)?;
    Ok(format!("{:x}", digest.finalize()) == spec.sha256)
}

fn remove_regular_if_present(path: &Path) -> Result<(), InstallError> {
    match fs::symlink_metadata(path) {
        Ok(metadata) if metadata.file_type().is_file() => fs::remove_file(path).map_err(io_error),
        Ok(_) => Err(InstallError::new(
            FailureCategory::Integrity,
            format!(
                "refusing to remove non-regular installer path {}",
                path.display()
            ),
        )),
        Err(error) if error.kind() == io::ErrorKind::NotFound => Ok(()),
        Err(error) => Err(io_error(error)),
    }
}

fn asset_error(error: io::Error) -> InstallError {
    if error.kind() == io::ErrorKind::NotFound {
        InstallError::new(FailureCategory::AssetMissing, error.to_string())
    } else {
        io_error(error)
    }
}

fn io_error(error: io::Error) -> InstallError {
    let category = match error.raw_os_error() {
        Some(28) => FailureCategory::Storage,
        _ if error.kind() == io::ErrorKind::PermissionDenied => FailureCategory::Permission,
        _ => FailureCategory::Io,
    };
    InstallError::new(category, error.to_string())
}
