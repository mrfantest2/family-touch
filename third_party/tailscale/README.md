# Embedded native dependency

Upstream: https://github.com/tailscale/tailscale-android
Commit: 264102cc702fbd844e900227ee02e5cbb4d7f801 (unmodified)
Module: tailscale.com v1.105.0-pre.0.20261001110716-689a0c155f57
Go: 1.27.2; gomobile revision from upstream go.mod; NDK 27.2.12479018.
Architectures: arm64-v8a (phones), x86_64 (emulator).
Command: gomobile bind -target android/arm64,android/amd64 -androidapi 29 -tags ts_omit_cachenetmap -ldflags '-s -w' -o android/libs/libtailscale.aar ./libtailscale
AAR SHA256: 41ebd003c1923ce0f2290c709f35c811aa0ca39c45478d4fcc0b74fe0163acd6

The checked-in AAR lets standard Android CI build without downloading a Go toolchain.
Rebuild from the pinned clean upstream with tools/Build-Tailscale.ps1. NDK, SDK, Java,
Go and gomobile must be on PATH/configured as in the build environment.
Preserve upstream LICENSE and PATENTS in distributions. This app is not the official Tailscale app.
