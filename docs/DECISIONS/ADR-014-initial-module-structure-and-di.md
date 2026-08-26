# ADR-014 — Pragmatic initial module structure, and manual DI

**Status:** Accepted · 26 Aug 2026

## Context

The baseline architecture proposed roughly twenty Gradle modules for the Android app
(`core:model`, `core:protocol`, `core:session-fsm`, `core:sync`, `core:queue`, `core:manifest`,
`core:hashing`, `core:logging`, `data:database`, `data:library`, `data:settings`,
`net:discovery`, `net:control`, `net:security`, `net:transfer`, `net:voice`, `audio:player`,
`audio:route`, `audio:policy`, four `feature:*` modules, plus `app` and `build-logic`) and four
SPM packages with seventeen targets on iOS.

The *boundaries* those modules describe are right and this ADR does not touch them. The question
is whether each boundary needs to be a separate build unit on day one.

For this project it does not. Twenty modules means twenty build files, convention plugins to stop
them diverging, a dependency-declaration surface larger than the code it separates, and — in a
codebase written largely by an AI agent — twenty opportunities for generated boilerplate to
accumulate faster than the logic it wraps. None of it is load-bearing for two devices and one
user. Gradle configuration time and the cognitive cost of "which module does this go in" are paid
every single build, from the first one.

The property that genuinely matters is different, and it is achievable with **one** module: the
pure domain must be compilable and testable with no platform SDK on its classpath.

## Decision

### 1. Module structure

**Android — five Gradle modules.**

```
android/
├── app/          [Android app]  Compose UI, navigation, SessionCoordinator,
│                                manual DI graph, foreground service, notification
├── core/         [pure JVM]     model · protocol · sessionfsm · sync · queue ·
│                                manifest · hashing · logging · audiopolicy
├── network/      [Android lib]  discovery · control · security · transfer · voice
├── audio/        [Android lib]  player · route
└── data/         [Android lib]  database · library · settings · trustedpeers
```

**iOS — two local packages plus the app target.**

```
ios/
├── RideLink/                   app target: @main, SwiftUI views, SessionCoordinator, DI wiring
└── Packages/
    ├── RideLinkCore/           one target: Model · Protocol · SessionFSM · Sync · Queue ·
    │                           Manifest · Hashing · Logging · AudioPolicy
    └── RideLinkPlatform/       one target: Discovery · Control · Security · Transfer ·
                                Voice · Player · Route · Library
```

Every former module name survives as a **package/directory** inside its module. Nothing about the
layering of ARCHITECTURE §2 changes; only the number of build units does.

**Boundary enforcement is mechanical, not by convention.** This is the part that makes the
reduction safe:

| Rule | How it is enforced |
|---|---|
| `core` contains no Android types | `core` is a plain `kotlin("jvm")` library. The Android SDK is not on its compile classpath, so `import android.*` does not compile. Stronger than any lint rule |
| `RideLinkCore` contains no iOS types | Import allowlist is `Foundation` + `CryptoKit`. `RideLinkCore` builds and tests for **macOS** under `swift test`, so an iOS-only import fails on a laptop in seconds |
| Dependency direction | `app → {network, audio, data} → core`. `core` depends on nothing. `network`, `audio` and `data` never depend on each other — anything shared belongs in `core` |
| Feature UI owns no domain state | `SessionCoordinator` lives in `app/session/` (Android) and the app target (iOS), is the single owner of session state per ARCHITECTURE §3 rule 4, and wraps the *pure* FSM from `core`. View models observe it and hold no connection |

No `build-logic/` convention plugins initially: five build files do not contain enough
duplication to justify them. `gradle/libs.versions.toml` still pins every version in one place.

**Promotion path.** A package becomes a module when there is a concrete reason — build times, a
genuine reuse boundary, a team split — not in advance. Because the domain is already pure and
already isolated, promotion is a directory move plus a build file, not a redesign. The two
candidates worth watching: `app/session/` if the coordinator grows, and `network/voice/` if the
WebRTC wrapper does.

### 2. Dependency injection

**Manual constructor injection. No DI framework in V1.** Hilt is not prohibited; it is simply not
yet earned.

The object graph is small and almost entirely singletons with a session lifetime:
`SessionCoordinator`, `ControlChannel`, `Discovery`, `ClockSync`, `TrustStore`, `Database`,
`LibraryIndexer`, `Player`, `AudioRoute`, `TransferManager`, plus view models. That is one
composition-root function in `app/di/` wiring perhaps a dozen constructors — an hour of code,
readable top to bottom, with no annotation processor, no generated-code build step and no
compile-time cost.

Adding Hilt would bring an annotation processor to every module, `@HiltAndroidApp` /
`@AndroidEntryPoint` plumbing, scope annotations, and a class of build error whose message is
generated code. The stated selection rule is platform API first, then a well-established library
— and the platform API here is a constructor.

The same decision applies on iOS: initialiser injection from the app target, no Resolver/Factory
package.

**Revisit trigger, concrete rather than vague:** if the composition root exceeds roughly 150
lines, or if more than one distinct object scope beyond "app" and "session" appears, or if tests
start needing large hand-built graphs, introduce Hilt then. It is an additive change — every
constructor already takes its dependencies, which is exactly what a DI framework wants.

## Consequences

- Phase 1 scaffolding is materially smaller: five build files instead of twenty-odd plus convention plugins. The first `./gradlew assembleDebug` is reachable in one sitting.
- Gradle configuration and build times stay low for the whole project's life at this size.
- The valuable guarantee is *kept and strengthened*: the domain still cannot see the platform, and now the compiler says so rather than a convention plugin.
- Shared vectors still run on the JVM and under `swift test` with no device, which was the whole reason for `core:*` in the first place.
- Cost: `app` sees everything, so an undisciplined change could put domain logic in a view model. Mitigated by `SessionCoordinator` being the only session-state owner (a rule the FSM tests assert) and by `core` being where anything pure has to live to be testable at all.
- Cost: within `core`, package-level dependencies are not compiler-enforced — `sync` *could* import `queue`. detekt/SwiftLint import rules can enforce this if it ever happens; it is not worth a module each pre-emptively.
- Cost: a later promotion to more modules is real work, just bounded work. Accepted knowingly.
- Kotlin Multiplatform for the domain (ADR-001's deferred option) stays open: `core` being a single pure JVM module is, if anything, a *shorter* path to a KMP `commonMain` than eight of them.

## Alternatives considered

| Option | Rejected because |
|---|---|
| The original ~20 modules | Build and wiring complexity out of proportion to a two-device personal app; boilerplate would outweigh the logic it separates |
| One single module | Loses the guarantee that actually matters — a pure domain with no platform SDK on its classpath. That guarantee is the reason the difficult logic is testable on a laptop |
| Two modules (`app` + `core`) | Puts sockets, TLS, WebRTC, ExoPlayer, Room and the UI in one build unit. `network`/`audio`/`data` separation is cheap and keeps platform risk contained where ADR-003 and ADR-007 assume it is |
| Keep 20 modules but skip convention plugins | Worst of both: the duplication that convention plugins exist to remove, without the tool that removes it |
| Hilt from the start | An annotation processor and a scoping vocabulary for a dozen singletons. Add it when the graph earns it, and the revisit trigger above says exactly when |
| Koin instead of Hilt | Still a dependency and a DSL to learn, to replace a constructor call. Same answer |
| `RideLinkFeatures` as a separate SPM package | Isolating SwiftUI views from the app target that hosts them buys nothing; the app target already sees everything by necessity |
