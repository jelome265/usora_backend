fn main() -> Result<(), Box<dyn std::error::Error>> {
    tonic_build::compile_protos("proto/biometric.proto")?;
    println!("cargo:rerun-if-changed=proto/");
    Ok(())
}
