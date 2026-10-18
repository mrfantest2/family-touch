# Family update execution ledger

Owner approved spec and inline plan, then added Back navigation: dismiss viewer/dialog first, return from a conversation/tab before leaving app. Preserve active calls on Back and require an explicit choice to end/minimize.

Base: bdc2251, feature/embedded-tailscale. Existing isolated worktree reused; original source untouched. No group/media/recording release has been claimed.

Task1 active: in-app viewer, fresh bounded location and Back navigation. Ruling: use Android platform playback and decoding without adding another media SDK; retain explicit external viewing. Android tests are required for runtime playback, while pure location validation is covered by unit tests.

Task1 implementation builds: LocationPolicyTest first failed with missing policy, then unit tests/assembleEmbedded/lintVital passed. Runtime permission, decoder, playback and Back checks remain for device validation. No release deployed yet. Proceeding to voice notes.
