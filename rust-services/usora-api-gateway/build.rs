fn main() -> Result<(), Box<dyn std::error::Error>> {
    let shared = std::path::PathBuf::from("../../shared/proto");
    tonic_build::configure()
        .extern_path(".google.protobuf.Timestamp", "::prost_types::Timestamp")
        .extern_path(".google.protobuf.Struct", "::prost_types::Struct")
        .extern_path(".google.protobuf.Value", "::prost_types::Value")
        .extern_path(".google.protobuf.ListValue", "::prost_types::ListValue")
        .build_server(true)
        .build_client(true)
        .compile_protos(
            &[
                "gateway_service.proto",
                "identity.proto",
                "document.proto",
                "tenant.proto",
                "audit.proto",
                "compliance.proto",
                "notification.proto",
            ],
            &[shared],
        )?;

    println!("cargo:rerun-if-changed=../../shared/proto/gateway_service.proto");
    println!("cargo:rerun-if-changed=../../shared/proto/identity.proto");
    println!("cargo:rerun-if-changed=../../shared/proto/document.proto");
    println!("cargo:rerun-if-changed=../../shared/proto/tenant.proto");
    println!("cargo:rerun-if-changed=../../shared/proto/audit.proto");
    println!("cargo:rerun-if-changed=../../shared/proto/compliance.proto");
    println!("cargo:rerun-if-changed=../../shared/proto/notification.proto");
    Ok(())
}
