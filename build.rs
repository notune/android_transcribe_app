use std::env;
use std::fs;
use std::path::PathBuf;

fn main() {
    // transcribe-cpp-sys reconstructs its link line from a generic Unix
    // manifest that lists `pthread` and the C++ runtime. Bionic has neither a
    // separate libpthread (pthreads live in libc) nor a full libstdc++ (the
    // real C++ runtime is libc++_shared, which the app already bundles), so
    // satisfy the former with an empty archive and link the latter explicitly.
    let target_os = env::var("CARGO_CFG_TARGET_OS").unwrap_or_default();
    if target_os == "android" {
        let model_spec = env::var("BUILTIN_MODEL_SPEC_RS")
            .expect("BUILTIN_MODEL_SPEC_RS must point to Gradle-generated verified metadata");
        let model_spec_path = PathBuf::from(&model_spec);
        assert!(
            model_spec_path.is_file(),
            "verified built-in model metadata is missing: {}",
            model_spec_path.display()
        );
        println!("cargo:rerun-if-env-changed=BUILTIN_MODEL_SPEC_RS");
        println!("cargo:rerun-if-changed={}", model_spec_path.display());

        let out = PathBuf::from(env::var("OUT_DIR").unwrap());
        fs::write(out.join("libpthread.a"), b"!<arch>\n").unwrap();
        println!("cargo:rustc-link-search=native={}", out.display());
        println!("cargo:rustc-link-lib=dylib=c++_shared");
    }
}
