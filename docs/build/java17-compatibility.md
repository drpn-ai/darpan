# Superseded: Java 17 / Atomikos Notes

This page is retired. It described the old JDK 17 baseline and the rationale for moving to
Atomikos, both of which are no longer true:

- The runtime baseline is now **JDK 21** (Gradle toolchain enforced in `build.gradle`).
- The framework baseline is **Moqui 4**, whose default JTA transaction manager is the embedded
  **Bitronix** (`TransactionInternalBitronix`). `moqui-atomikos` is retired and removed from the
  build graph and Dockerfiles; no Atomikos switch is planned.
- The single `--add-exports=java.base/sun.nio.ch=ALL-UNNAMED` flag described here was replaced by
  the full Spark 3.5.x `--add-opens` set for JDK 21.

See [runtime-baseline](../runtime-baseline.md) for the current JDK 21 / Moqui 4 / Bitronix / Spark
JVM-flags baseline and where each flag is wired.
