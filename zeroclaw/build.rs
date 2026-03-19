// Copyright (c) 2026 @Natfii. All rights reserved.

fn main() {
    // Use the vendored protoc binary so builds work without a system protoc installation.
    let protoc = protoc_bin_vendored::protoc_bin_path().expect("vendored protoc not found");
    std::env::set_var("PROTOC", protoc);

    let protos = &[
        "src/messages_bridge/proto/authentication.proto",
        "src/messages_bridge/proto/client.proto",
        "src/messages_bridge/proto/conversations.proto",
        "src/messages_bridge/proto/events.proto",
        "src/messages_bridge/proto/rpc.proto",
        "src/messages_bridge/proto/settings.proto",
        "src/messages_bridge/proto/config.proto",
        "src/messages_bridge/proto/util.proto",
    ];
    prost_build::Config::new()
        .compile_protos(protos, &["src/messages_bridge/proto/"])
        .expect("Failed to compile protobuf schemas");
}
