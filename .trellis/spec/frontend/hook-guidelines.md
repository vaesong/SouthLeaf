# Native Page Lifecycle Guidelines

- Use native onLoad/onShow/onHide/onUnload/onPullDownRefresh and explicit setData, not Vue hooks or proxy mutation.
- Initialize request generation before starting a read. Invalidate generations on hide/unload; clear media and server-derived display state. Resume rereads; late responses cannot restore stale content.
- `presentation/page-state.js` centralizes setData commits and prevents writes after unload; each page owns query-specific generation and reset behavior.
- Bind native events through dataset adapters. Do not pass an event object into a boolean reset argument or reuse conflicting dataset attributes.
- Do not register duplicate polling, pretend a Node test made a phone call, or persist authorization decisions in storage. Formal device/system behavior belongs to stage-two validation.
- Pure presentation helpers may derive initial marks and view labels but cannot perform network access or decide visibility.
