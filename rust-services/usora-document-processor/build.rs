fn main() -> Result<(), Box<dyn std::error::Error>> {
    let shared_proto = std::path::PathBuf::from("../../shared/proto");

    tonic_build::configure()
        .build_server(true)
        .build_client(true)
        .out_dir("src/generated")
        .compile_protos(
            &[shared_proto.join("document.proto")],
            std::slice::from_ref(&shared_proto),
        )?;

    for entry in std::fs::read_dir(&shared_proto)? {
        let entry = entry?;
        if entry
            .path()
            .extension()
            .map(|e| e == "proto")
            .unwrap_or(false)
        {
            println!("cargo:rerun-if-changed={}", entry.path().display());
        }
    }

    Ok(())
}
