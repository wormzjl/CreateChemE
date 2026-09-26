# Non-full block occlusion review

## Scope

Correct the world renderer culling adjacent block faces behind CreateChemE fluid-device models that occupy only part of their block space.

## Diagnosis

`ModBlocks.fluid` created every fluid device with the default occluding block properties. Minecraft therefore treated the block-space boundary as opaque while building a chunk, even for the partial generator, pipe, pump, reservoir, valve and void models. Faces of adjacent blocks were omitted before those models were drawn.

The inline filter uses the full-cube `minecraft:block/orientable` model, so it should keep normal occlusion.

## Change

The common fluid-device registration now applies `BlockBehaviour.Properties.noOcclusion()` to every fluid kind except `FILTER`. This makes the renderer retain neighbouring faces for each partial model without changing collision, placement, connection state or fluid-network behaviour.

## Verification

- `./gradlew.bat compileJava --offline --no-daemon --console=plain --rerun-tasks` completed successfully on 2026-09-25 using JDK 21.
- The first `runClient` attempt loaded CreateChemE 0.5.0 and reached integrated-server startup, but correctly rejected its automatically selected `New World` because its checkpoint was format 1 while this build reads format 4 only.
- The requested rerun replaced that development world with a fresh format-4 `New World`; the integrated server loaded with zero islands, the process solver started, and the Dev player joined. The client is ready for the visual check.

## Status

In progress: the source change is present and compiled in the `main` working tree but has not yet been committed.
