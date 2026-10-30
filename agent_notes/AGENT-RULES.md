# Agent ground rules — Secure Wallet Pro (read fully before writing a line)

Project root: `D:\sharyar\SMART VALUATE` — Android/Kotlin, Jetpack Compose + Material3, Hilt, Room.
The tree **compiles clean and all 311 unit tests pass right now**. Keep it that way.

## You must not do

- **Do not run Gradle.** The manager owns `./gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin`
  and will report every error back to you. Concurrent builds in this directory corrupt each other.
- **Do not edit files outside your ownership list.** Shared files are off limits:
  `presentation/navigation/Routes.kt`, `presentation/navigation/VaultApp.kt`, `data/local/VaultDatabase.kt`,
  `di/*.kt`, `AndroidManifest.xml`, `app/build.gradle.kts`, `presentation/components/**`, `presentation/theme/**`.
  If you need something in one of those, write the exact line you need into your handoff note instead.
- **Do not use a Material icon anywhere.** `Icons.Filled.*` / `androidx.compose.material.icons` is banned by
  `MaterialIconsAreBannedTest` and the dependency is deleted. Use `ThemedIcon(glyph = Glyph.x, ...)`.
- **Do not invent an API.** Before referencing a class, function or property, open the file that declares it
  and read the real signature. Every unresolved-symbol error you cause costs a full build cycle.
- **Do not ship a colour swap and call it a screen.** Real layouts, real state, real actions.
- **Do not add any auto-deletion, TTL, retention window or 30-day trash.** The owner's standing rule is
  verbatim: data must stay recoverable permanently until the user manually deletes it.
- **Do not touch core encryption, the `SecureVaultProMasterKey` Keystore alias, the `{v:3}` envelope,
  the OAuth client, `google-services.json`, or the signing config.**

## The house UI system (all verified in the current tree)

```kotlin
// presentation/components/Common.kt
@Composable fun GlassScaffold(
    title: String, subtitle: String? = null, onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}, fab: (@Composable () -> Unit)? = null,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    content: @Composable (PaddingValues) -> Unit
)
// also in Common.kt: GlassCard, GlassIconButton, VaultSearchField, EmptyState, NeonBackground,
// vaultGutter, formatShortDate, and the rest of the shared kit — read Common.kt, use what is there.

// presentation/components/ThemedIcons.kt + VaultGlyph.kt
@Composable fun ThemedIcon(
    glyph: Glyph, contentDescription: String?, modifier: Modifier = Modifier,
    tint: Color = VaultColor.textBody, size: Dp = 24.dp, animate: Boolean = true
)
```

`Glyph` entries that already exist (use only these, and ask in the handoff note if you need another):
`accountBalance add arrowBack arrowForward arrowUpward attachFile autoAwesome autoFixHigh backspace badge
bolt brokenImage calendarToday call cameraAlt chat check checkCircle chevronLeft chevronRight close cloud
cloudDone cloudDownload cloudOff cloudUpload contactless contacts contentCopy contentPaste creditCard
cropFree currencyBitcoin darkMode delete description directionsCar documentScanner doneAll download edit
email errorOutline expandLess expandMore fileDownload fileUpload fingerprint flashOn folder folderOpen
gridOn healthAndSafety home image info insertDriveFile insights key language lightMode link locationOn
lock memory message mic musicNote notes openInNew palette person phone photoLibrary pictureAsPdf pin
playArrow publicGlobe pushPin qrCode qrCodeScanner refresh replay search security send settings share
shield shuffle star starBorder stop sync systemUpdate textFields timer travelExplore upload uploadFile
vibration videocam visibility visibilityOff`

Colours, shapes and type come from `presentation/theme/` — `VaultColor`, `VaultShapes`, `VaultType`.
Never hardcode a hex colour or a `Color(0xFF...)` in a screen; every theme must be able to restyle you.
Layouts must survive a 360x640 dp phone and a 900 dp tablet without clipping or overlap.

## ViewModel pattern

`@HiltViewModel class XViewModel @Inject constructor(...) : ViewModel()` + `hiltViewModel()` at the call
site, exactly as `presentation/screens/notes/NotesScreen.kt` does. Collect with
`collectAsStateWithLifecycle()`. Never block the main thread; all IO goes through `Dispatchers.IO`.

## Entry-point contract (so the manager can wire you without reading your code)

Your feature exposes **one** public composable with this shape, and it owns its internal navigation itself
(a `when`-on-state switch or a nested `NavHost` — your call):

```kotlin
@Composable fun <Feature>Section(onBack: () -> Unit)   // EmailSection | StegoSection | RecoverySeedSection
```

The manager adds the route, the `composable(...)` entry and the Home/Settings tile. Anything you need from
a shared file goes in the handoff note as an exact, ready-to-paste line.

## Definition of done for your assignment

1. Code that compiles against APIs you actually read, not assumed.
2. Real behaviour end to end — if a step cannot work yet, say so in the note, do not fake it.
3. JUnit tests for the pure logic you wrote, under `app/src/test/java/...` mirroring your package.
   JVM-only: no Robolectric, no Android runtime, no network in tests.
4. `agent_notes/<your-task>.md` — what you built, what you deliberately did not build, every shared-file
   line you need, and anything you found broken in someone else's code (report it, do not fix it).
