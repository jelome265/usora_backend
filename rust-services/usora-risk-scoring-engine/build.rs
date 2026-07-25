fn main() -> Result<(), Box<dyn std::error::Error>> {
    println!("cargo:rerun-if-changed=proto/");
    tonic_build::configure()
        .build_server(true)
        .build_client(true)
        .out_dir("src/grpc")
        .compile(&["proto/risk_scoring.proto"], &["proto"])?;
    Ok(())
}
