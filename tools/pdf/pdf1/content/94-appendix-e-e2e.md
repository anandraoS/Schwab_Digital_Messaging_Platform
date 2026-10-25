# Appendix E. End-to-End Run Output (Executed)

This appendix reproduces `docs/validation/e2e-run-output.txt` verbatim: the output of a real HTTP end-to-end run against the packaged backend jar on the `local` profile, produced by `node tests/end-to-end/e2e.mjs`. It is the strongest executed evidence in the repository and also shows what it is not: it ran on H2, the in-memory bus and in-memory Mongo with simulated providers, so it proves behaviour and contracts, not PostgreSQL, RabbitMQ or Redis capacity or operations. Docker, Kubernetes, Cloud Foundry, k6 and CI were not part of it.

{{file:docs/validation/e2e-run-output.txt}}
