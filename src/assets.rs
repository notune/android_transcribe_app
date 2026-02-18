use jni::objects::JObject;
use jni::JNIEnv;
use std::path::PathBuf;

/// Marker file written after a successful extraction. If this file is missing,
/// the directory is assumed to be incomplete (e.g. interrupted mid-extraction)
/// and the assets will be re-extracted.
const EXTRACTION_COMPLETE_MARKER: &str = ".extraction_complete";

pub fn extract_assets(env: &mut JNIEnv, context: &JObject) -> anyhow::Result<PathBuf> {
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

    let base_path = PathBuf::from(path_string);
    let model_dir = base_path.join("parakeet-tdt-0.6b-v3-int8");
    let marker_file = model_dir.join(EXTRACTION_COMPLETE_MARKER);

    // Only skip extraction if the marker file exists (proves prior extraction completed)
    if marker_file.exists() {
        return Ok(model_dir);
    }

    // Incomplete or missing — wipe and re-extract
    if model_dir.exists() {
        log::info!("Removing incomplete model directory for re-extraction");
        let _ = std::fs::remove_dir_all(&model_dir);
    }

    std::fs::create_dir_all(&model_dir)?;

    let asset_manager_obj = env
        .call_method(
            context,
            "getAssets",
            "()Landroid/content/res/AssetManager;",
            &[],
        )?
        .l()?;
    let asset_dir_name = "parakeet-tdt-0.6b-v3-int8";

    copy_assets_recursively(env, &asset_manager_obj, asset_dir_name, &base_path)?;

    // Write the marker file to indicate successful completion
    std::fs::write(&marker_file, "ok")?;
    log::info!("Asset extraction complete, marker written");

    Ok(model_dir)
}

fn copy_assets_recursively(
    env: &mut JNIEnv,
    asset_manager: &JObject,
    path: &str,
    target_root: &PathBuf,
) -> anyhow::Result<()> {
    use jni::objects::JObjectArray;

    let path_jstring = env.new_string(path)?;
    let list_array_obj = env
        .call_method(
            asset_manager,
            "list",
            "(Ljava/lang/String;)[Ljava/lang/String;",
            &[(&path_jstring).into()],
        )?
        .l()?;

    let list_array: JObjectArray = list_array_obj.into();
    let len = env.get_array_length(&list_array)?;

    if len == 0 {
        return copy_asset_file(env, asset_manager, path, target_root);
    }

    let target_dir = target_root.join(path);
    std::fs::create_dir_all(&target_dir)?;

    for i in 0..len {
        let file_name_obj = env.get_object_array_element(&list_array, i)?;
        let file_name: String = env.get_string(&file_name_obj.into())?.into();

        let child_path = if path.is_empty() {
            file_name
        } else {
            format!("{}/{}", path, file_name)
        };

        copy_assets_recursively(env, asset_manager, &child_path, target_root)?;
    }
    Ok(())
}

fn copy_asset_file(
    env: &mut JNIEnv,
    asset_manager: &JObject,
    asset_path: &str,
    target_root: &PathBuf,
) -> anyhow::Result<()> {
    let path_jstring = env.new_string(asset_path)?;
    let result = env.call_method(
        asset_manager,
        "open",
        "(Ljava/lang/String;)Ljava/io/InputStream;",
        &[(&path_jstring).into()],
    );

    match result {
        Ok(stream_val) => {
            let stream_obj = stream_val.l()?;
            let target_file_path = target_root.join(asset_path);

            let mut file = std::fs::File::create(&target_file_path)?;
            let mut buffer = [0u8; 8192];
            let buffer_j = env.new_byte_array(8192)?;

            loop {
                let bytes_read = env
                    .call_method(&stream_obj, "read", "([B)I", &[(&buffer_j).into()])?
                    .i()?;

                if bytes_read == -1 {
                    break;
                }

                let bytes_read_usize = bytes_read as usize;
                let buffer_slice = unsafe {
                    std::slice::from_raw_parts_mut(buffer.as_mut_ptr() as *mut i8, bytes_read_usize)
                };

                env.get_byte_array_region(&buffer_j, 0, buffer_slice)?;

                use std::io::Write;
                file.write_all(&buffer[0..bytes_read_usize])?;
            }

            env.call_method(&stream_obj, "close", "()V", &[])?;
            log::info!("Extracted: {:?}", target_file_path);
            Ok(())
        }
        Err(_) => Ok(()),
    }
}
