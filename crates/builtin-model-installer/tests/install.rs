use builtin_model_installer::{
    ensure_installed, ensure_installed_with_writer, FailureCategory, InstallOptions, InstallState,
    ModelSpec,
};
use fs2::FileExt;
use sha2::{Digest, Sha256};
use std::fs::{self, OpenOptions};
use std::io::{self, Cursor, Read};
use std::sync::{Arc, Barrier, Mutex};
use std::time::Duration;
use tempfile::TempDir;

fn spec(bytes: &[u8]) -> ModelSpec {
    ModelSpec {
        file_name: "model.gguf",
        byte_len: bytes.len() as u64,
        sha256: format!("{:x}", Sha256::digest(bytes)),
    }
}

fn options() -> InstallOptions {
    InstallOptions {
        lock_timeout: Duration::from_millis(250),
        poll_interval: Duration::from_millis(5),
    }
}

fn source(bytes: &'static [u8]) -> impl FnMut() -> io::Result<Box<dyn Read + Send>> {
    move || Ok(Box::new(Cursor::new(bytes)))
}

#[test]
fn clean_first_start_installs_verified_model_atomically() {
    let dir = TempDir::new().unwrap();
    let states = Arc::new(Mutex::new(Vec::new()));
    let seen = Arc::clone(&states);
    let path = ensure_installed(
        dir.path(),
        &spec(b"model"),
        source(b"model"),
        options(),
        move |s| seen.lock().unwrap().push(s),
    )
    .unwrap();

    assert_eq!(fs::read(path).unwrap(), b"model");
    assert_eq!(
        *states.lock().unwrap(),
        vec![
            InstallState::Absent,
            InstallState::Installing,
            InstallState::Ready
        ]
    );
    assert!(!dir
        .path()
        .join("builtin-model/.model.gguf.installing")
        .exists());
}

#[test]
fn writer_callback_installs_without_returning_a_borrowed_reader() {
    let dir = TempDir::new().unwrap();
    let bytes = b"borrowed source";

    let path = ensure_installed_with_writer(
        dir.path(),
        &spec(bytes),
        |destination| {
            destination.write_all(bytes)?;
            Ok(())
        },
        options(),
        |_| {},
    )
    .unwrap();

    assert_eq!(fs::read(path).unwrap(), bytes);
}

#[test]
fn valid_existing_file_is_reused_without_opening_asset() {
    let dir = TempDir::new().unwrap();
    let model_dir = dir.path().join("builtin-model");
    fs::create_dir_all(&model_dir).unwrap();
    fs::write(model_dir.join("model.gguf"), b"model").unwrap();
    let mut opens = 0;

    ensure_installed(
        dir.path(),
        &spec(b"model"),
        || {
            opens += 1;
            Err::<std::io::Cursor<Vec<u8>>, _>(io::Error::other("must not open"))
        },
        options(),
        |_| {},
    )
    .unwrap();
    assert_eq!(opens, 0);
}

#[test]
fn truncated_and_bad_hash_finals_are_replaced() {
    for old in [b"mo".as_slice(), b"modem".as_slice()] {
        let dir = TempDir::new().unwrap();
        let model_dir = dir.path().join("builtin-model");
        fs::create_dir_all(&model_dir).unwrap();
        fs::write(model_dir.join("model.gguf"), old).unwrap();
        ensure_installed(
            dir.path(),
            &spec(b"model"),
            source(b"model"),
            options(),
            |_| {},
        )
        .unwrap();
        assert_eq!(fs::read(model_dir.join("model.gguf")).unwrap(), b"model");
    }
}

#[test]
fn interrupted_owned_temp_is_removed_but_imported_models_are_untouched() {
    let dir = TempDir::new().unwrap();
    let model_dir = dir.path().join("builtin-model");
    let imported = dir.path().join("models/user.gguf");
    fs::create_dir_all(imported.parent().unwrap()).unwrap();
    fs::create_dir_all(&model_dir).unwrap();
    fs::write(model_dir.join(".model.gguf.installing"), b"partial").unwrap();
    fs::write(&imported, b"user-model").unwrap();
    fs::write(dir.path().join("unrelated"), b"keep").unwrap();

    ensure_installed(
        dir.path(),
        &spec(b"model"),
        source(b"model"),
        options(),
        |_| {},
    )
    .unwrap();
    assert_eq!(fs::read(imported).unwrap(), b"user-model");
    assert_eq!(fs::read(dir.path().join("unrelated")).unwrap(), b"keep");
}

#[test]
fn concurrent_callers_converge_on_one_verified_file() {
    let dir = Arc::new(TempDir::new().unwrap());
    let barrier = Arc::new(Barrier::new(3));
    let opens = Arc::new(Mutex::new(0));
    let mut joins = Vec::new();
    for _ in 0..2 {
        let dir = Arc::clone(&dir);
        let barrier = Arc::clone(&barrier);
        let opens = Arc::clone(&opens);
        joins.push(std::thread::spawn(move || {
            barrier.wait();
            ensure_installed(
                dir.path(),
                &spec(b"model"),
                || {
                    *opens.lock().unwrap() += 1;
                    Ok(Box::new(Cursor::new(b"model")) as Box<dyn Read + Send>)
                },
                options(),
                |_| {},
            )
        }));
    }
    barrier.wait();
    for join in joins {
        join.join().unwrap().unwrap();
    }
    assert_eq!(*opens.lock().unwrap(), 1);
}

#[test]
fn recoverable_integrity_failure_retries_once() {
    let dir = TempDir::new().unwrap();
    let mut opens = 0;
    ensure_installed(
        dir.path(),
        &spec(b"model"),
        || {
            opens += 1;
            let bytes: &'static [u8] = if opens == 1 { b"bad!!" } else { b"model" };
            Ok(Box::new(Cursor::new(bytes)))
        },
        options(),
        |_| {},
    )
    .unwrap();
    assert_eq!(opens, 2);
}

#[test]
fn missing_asset_is_permanent_and_not_retried() {
    let dir = TempDir::new().unwrap();
    let mut opens = 0;
    let error = ensure_installed(
        dir.path(),
        &spec(b"model"),
        || {
            opens += 1;
            Err::<std::io::Cursor<Vec<u8>>, _>(io::Error::new(
                io::ErrorKind::NotFound,
                "asset absent",
            ))
        },
        options(),
        |_| {},
    )
    .unwrap_err();
    assert_eq!(opens, 1);
    assert_eq!(error.category(), FailureCategory::AssetMissing);
}

#[test]
fn lock_timeout_is_categorized_and_retryable() {
    let dir = TempDir::new().unwrap();
    let model_dir = dir.path().join("builtin-model");
    fs::create_dir_all(&model_dir).unwrap();
    let lock = OpenOptions::new()
        .create(true)
        .truncate(false)
        .read(true)
        .write(true)
        .open(model_dir.join(".install.lock"))
        .unwrap();
    lock.lock_exclusive().unwrap();

    let error = ensure_installed(
        dir.path(),
        &spec(b"model"),
        source(b"model"),
        InstallOptions {
            lock_timeout: Duration::from_millis(30),
            poll_interval: Duration::from_millis(2),
        },
        |_| {},
    )
    .unwrap_err();
    assert_eq!(error.category(), FailureCategory::LockTimeout);
    assert!(error.retryable());
    lock.unlock().unwrap();
}

#[test]
fn setup_states_are_distinct_from_recognition_states() {
    assert_ne!(InstallState::Absent, InstallState::Installing);
    assert_ne!(InstallState::Installing, InstallState::Ready);
    assert_ne!(
        InstallState::Ready,
        InstallState::Failed(FailureCategory::Storage)
    );
    assert_eq!(
        InstallState::Ready.user_message(),
        "Ready for offline speech"
    );
    assert!(InstallState::Failed(FailureCategory::LockTimeout)
        .user_message()
        .contains("Offline model unavailable"));
}
