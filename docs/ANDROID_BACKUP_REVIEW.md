# Android library, backup and account review

The signed-in library was a projection over a frozen pre-login guest snapshot. MusicRepository saved that snapshot's playlists and favorites to disk; edits made while signed in were lost from local storage on restart or sign-out. Explicit playlist/favorite edits now update the local snapshot without copying untouched remote playlists. Regression coverage includes creating bgm while a remote playlist is visible and subsequently deleting it.

Enabling playlists, favorites or preferences after enabling sync now captures existing data. Previously the baseline advanced while these categories were disabled, so enabling them could skip unchanged existing records.

Account now offers local metadata backup and merge restore, including in guest mode. Backups exclude audio and credentials. Restore validates format and playlist identity and checks file availability; it cannot recover audio or URI grants from another phone. Cloud account export remains separate and now uses ordered pagination. Existing backups or cloud records may recover a missing playlist, but these fixes cannot reconstruct records that were already overwritten.

The app uses a separate compact mini-player above a labeled glass navigation capsule and square rounded player artwork. A phone validation at the time of review found two playlists locally; an absent playlist was not present in the library or AtomicFile backup on that device.

The app retains its Supabase email/password flow, encrypted Android Keystore session storage, owner-filtered row policies and server-side token validation. Failed sign-in preserves the previous in-memory account rather than publishing signed-out state while retaining its tokens. Unrestorable sessions are cleared from memory. Production Supabase settings, email delivery, deployed policies and end-to-end account flows require a configured environment; source inspection alone does not verify them.

Validation: 32 JVM tests passed, debug APK assembled and lint passed (existing warnings remain). Updated APK installed successfully with adb install -r; existing library preserved.
