fn main() -> Result<(), Box<dyn std::error::Error>> {
    let shared = std::path::PathBuf::from("../../shared/proto");
    println!("cargo:rerun-if-changed=../../shared/proto/risk_scoring.proto");
    std::fs::create_dir_all("src/grpc")?;
    tonic_build::configure()
        .build_server(true)
        .build_client(true)
        .out_dir("src/grpc")
        .compile(&[shared.join("risk_scoring.proto")], &[shared])?;
    Ok(())
}
