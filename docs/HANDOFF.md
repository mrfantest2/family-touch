# Family Touch handoff — 2026-10-19

Active work: unified version15. Read docs/UNIFIED_RELEASE_ACTIVE_20261019.md for implementation, artifact hash, service configuration and exact blockers. Original source preserved; isolated worktree C:/Khalil/family-touch/embedded-tailscale, branch feature/embedded-tailscale.

Single app defaults to built-in VPN, optional external mode in Settings. New delivery/retry/read states, quoted replies/reactions, search/gallery. Recording clock and continuously renewed unanimous consent fixes passed review and regression checks. Full signed APK/AAB build, embedded/external units (48 embedded tests), lint and two emulator instrumentation tests passed. Windows/Linux broker tests passed. Published versioned APK HTTPS hash verified. Existing historical APKs retained.

AgentBox primary was deployed and verified, then went offline. Last encrypted Windows standby snapshot preserved; scheduled backup failed safely. Do not enable two registry writers. Client points only to primary endpoint; controlled standby promotion requires fencing primary and routing switch. User asked to restore AgentBox. Automatic review blocked Apache restart (blocked by policy, no detail); config Syntax OK, human Laragon reload pending, do not circumvent.

OP7 reachable and identity/power verified but no Family Touch main package found; QA install failed after its ADB route disappeared; no version15 main-app deployment occurred. S25 offline. No new physical update/runtime success claimed. Play internal draft exists, four requested testers previously verified; browser debugger unattached and owner asked to reconnect. New AAB has NOT been uploaded/submitted. Live multi-device calls/recording and physical UI regression remain acceptance gates.
