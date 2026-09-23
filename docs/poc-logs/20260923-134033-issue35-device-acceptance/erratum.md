# Erratum for the first exploratory pass

The initial 13:40 pass is retained as raw history, but is not the acceptance verdict.

- `POST_NOTIFICATIONS` was denied at preflight, so the first `POST_TEST` receiver logged its action but did not create the app notification. The initial “combined” label was therefore incorrect.
- The first two shell post commands used malformed positional arguments and reused the `acceptance` notification tag. The second command updated an existing key rather than creating a fresh notification; its missing `Posted` callback was not evidence of a new locked notification being ignored.
- The first recovery sequence was triggered by `miui.intent.action.SUB_SCREEN_ON`; it did not prove the delayed unexpected-detach handler. Later samples and logs are recorded separately.
- `owner-samples.txt` called the remaining shell-only state “Feishu-only”; that label was wrong. Only synthetic `com.android.shell` was active.
- The first-pass transcript and log are kept for traceability only. Use `acceptance-summary.md` and the corrected sample files for the verdict.
