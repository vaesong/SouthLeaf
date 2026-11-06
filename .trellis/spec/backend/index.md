# Backend Development Guidelines

These guides describe implementation conventions, not a production acceptance certificate. The stage-two admin runtime, content, and booking modules are part of the current source tree.

- [Directory Structure](./directory-structure.md)
- [Database Guidelines](./database-guidelines.md)
- [Error Handling](./error-handling.md)
- [Quality Guidelines](./quality-guidelines.md)
- [Logging Guidelines](./logging-guidelines.md)

Stage-one delivery: [stage-one record](../../../docs/开发与演示/阶段一收敛交付记录.md). Stage-two acceptance and the still-open 2B gates: [stage-two tracker](../../../docs/开发与演示/阶段二实施跟踪.md).

## Credential increments

Read [credential guidelines](credential-guidelines.md) before touching login/crypto/event ports or runtime wiring; it defines executable C1 contracts and the C2/C3 boundaries.
