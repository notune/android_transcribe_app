//! Verified installation of the bundled speech model from APK assets.

use builtin_model_installer::{
    ensure_installed_with_writer, FailureCategory, InstallOptions, ModelSpec,
};
use jni::objects::{JByteArray, JObject, JObjectArray};
use jni::JNIEnv;
use std::fmt;
use std::io::{self, Read};
use std::path::PathBuf;

const BUILTIN_MODEL_DIR: &str = "builtin-model";

mod generated {
    include!(env!("BUILTIN_MODEL_SPEC_RS"));
}

#[derive(Debug)]
pub struct ModelSetupError {
    category: &'static str,
    detail: String,
}

impl ModelSetupError {
    fn new(category: &'static str, detail: impl Into<String>) -> Self {
        Self {
            category,
            detail: detail.into(),
        }
    }

    pub fn category(&self) -> &'static str {
        self.category
    }
}

impl fmt::Display for ModelSetupError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(formatter, "{}", self.detail)
    }
}

impl std::error::Error for ModelSetupError {}

/// Resolves the app's `filesDir` via the given Context.
pub fn files_dir(env: &mut JNIEnv, context: &JObject) -> anyhow::Result<PathBuf> {
    let files_dir_obj = env
        .call_method(context, "getFilesDir", "()Ljava/io/File;", &[])?
        .l()?;
    let path_str_obj = env
        .call_method(
            &files_dir_obj,
            "getAbsolutePath",
            "()Ljava/lang/String;",
            &[],
        )?
        .l()?;
    let path_string: String = env.get_string(&path_str_obj.into())?.into();
    Ok(PathBuf::from(path_string))
}

/// Verifies or atomically installs the one pinned bundled model.
pub fn extract_builtin_model(
    env: &mut JNIEnv,
    context: &JObject,
) -> Result<PathBuf, ModelSetupError> {
    let base_path = files_dir(env, context)
        .map_err(|error| ModelSetupError::new("io", format!("filesDir: {error}")))?;
    let asset_manager = env
        .call_method(
            context,
            "getAssets",
            "()Landroid/content/res/AssetManager;",
            &[],
        )
        .and_then(|value| value.l())
        .map_err(|error| ModelSetupError::new("io", format!("AssetManager: {error}")))?;

    if !asset_is_listed(env, &asset_manager)? {
        return Err(ModelSetupError::new(
            "asset_missing",
            "this build does not contain the offline model",
        ));
    }

    let spec = ModelSpec {
        file_name: generated::BUILTIN_MODEL_FILE,
        byte_len: generated::BUILTIN_MODEL_LEN,
        sha256: generated::BUILTIN_MODEL_SHA256.to_owned(),
    };
    ensure_installed_with_writer(
        &base_path,
        &spec,
        |destination| {
            let mut source = open_asset(env, &asset_manager)?;
            io::copy(&mut source, destination)?;
            Ok(())
        },
        InstallOptions::default(),
        |_| {},
    )
    .map_err(|error| ModelSetupError::new(category_key(error.category()), error.to_string()))
}

fn category_key(category: FailureCategory) -> &'static str {
    match category {
        FailureCategory::AssetMissing => "asset_missing",
        FailureCategory::Integrity => "integrity",
        FailureCategory::Storage => "storage",
        FailureCategory::Permission => "permission",
        FailureCategory::LockTimeout => "lock_timeout",
        FailureCategory::Io => "io",
    }
}

fn asset_is_listed(env: &mut JNIEnv, asset_manager: &JObject) -> Result<bool, ModelSetupError> {
    let directory = env
        .new_string(BUILTIN_MODEL_DIR)
        .map_err(|error| ModelSetupError::new("io", error.to_string()))?;
    let array_object = env
        .call_method(
            asset_manager,
            "list",
            "(Ljava/lang/String;)[Ljava/lang/String;",
            &[(&directory).into()],
        )
        .and_then(|value| value.l())
        .map_err(|error| ModelSetupError::new("io", format!("listing APK assets: {error}")))?;
    let array: JObjectArray = array_object.into();
    let count = env
        .get_array_length(&array)
        .map_err(|error| ModelSetupError::new("io", error.to_string()))?;
    for index in 0..count {
        let item = env
            .get_object_array_element(&array, index)
            .map_err(|error| ModelSetupError::new("io", error.to_string()))?;
        let name: String = env
            .get_string(&item.into())
            .map_err(|error| ModelSetupError::new("io", error.to_string()))?
            .into();
        if name == generated::BUILTIN_MODEL_FILE {
            return Ok(true);
        }
    }
    Ok(false)
}

fn open_asset<'local, 'env>(
    env: &'env mut JNIEnv<'local>,
    asset_manager: &JObject<'local>,
) -> io::Result<AndroidAssetReader<'local, 'env>> {
    let asset_path = format!("{BUILTIN_MODEL_DIR}/{}", generated::BUILTIN_MODEL_FILE);
    let path = env
        .new_string(asset_path)
        .map_err(|error| io::Error::other(error.to_string()))?;
    let stream = match env.call_method(
        asset_manager,
        "open",
        "(Ljava/lang/String;)Ljava/io/InputStream;",
        &[(&path).into()],
    ) {
        Ok(value) => value
            .l()
            .map_err(|error| io::Error::other(error.to_string()))?,
        Err(error) => {
            let _ = env.exception_clear();
            return Err(io::Error::other(format!("opening bundled asset: {error}")));
        }
    };
    let buffer = env
        .new_byte_array(64 * 1024)
        .map_err(|error| io::Error::other(error.to_string()))?;
    Ok(AndroidAssetReader {
        env,
        stream,
        buffer,
    })
}

struct AndroidAssetReader<'local, 'env> {
    env: &'env mut JNIEnv<'local>,
    stream: JObject<'local>,
    buffer: JByteArray<'local>,
}

impl Read for AndroidAssetReader<'_, '_> {
    fn read(&mut self, output: &mut [u8]) -> io::Result<usize> {
        if output.is_empty() {
            return Ok(0);
        }
        let wanted = output.len().min(64 * 1024) as i32;
        let count = self
            .env
            .call_method(
                &self.stream,
                "read",
                "([BII)I",
                &[(&self.buffer).into(), 0_i32.into(), wanted.into()],
            )
            .and_then(|value| value.i())
            .map_err(|error| {
                let _ = self.env.exception_clear();
                io::Error::other(format!("reading bundled asset: {error}"))
            })?;
        if count < 0 {
            return Ok(0);
        }
        let mut signed = vec![0_i8; count as usize];
        self.env
            .get_byte_array_region(&self.buffer, 0, &mut signed)
            .map_err(|error| io::Error::other(error.to_string()))?;
        for (target, source) in output.iter_mut().zip(signed) {
            *target = source as u8;
        }
        Ok(count as usize)
    }
}

impl Drop for AndroidAssetReader<'_, '_> {
    fn drop(&mut self) {
        let _ = self.env.call_method(&self.stream, "close", "()V", &[]);
        let _ = self.env.exception_clear();
    }
}
