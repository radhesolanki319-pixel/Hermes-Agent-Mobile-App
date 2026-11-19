# Hermes Agent Fix Summary - Mobile Harness v32

## Identified Issues

### Issue 1: aiohttp dependency missing for warm server (HIGH PRIORITY)

**Problem:** The v32 commit mentions "aiohttp repair" but the Hermes 0.19.0 warm server requires aiohttp for the `hermes gateway run` command. If aiohttp is missing, the server fails to start with an import error.

**Location:** `RuntimeInstaller.kt` - `installHermesFromPyPi()` and `installHermesFromSource()` methods

**Current Code (line 801 in installHermesFromPyPi):**
```kotlin
$UV_GUEST_PATH pip install --python $HERMES_PREFIX_GUEST_PATH/venv/bin/python "hermes-agent[acp]==$version" "$HERMES_AIOHTTP_SPEC"
```

**Fix Required:** Verify `$HERMES_AIOHTTP_SPEC` is properly defined in constants and passed correctly.

### Issue 2: Warm server health check timeout too aggressive (MEDIUM)

**Problem:** `HEALTH_TIMEOUT_MS = 2_000` (2 seconds) may not be enough for the API server to start on slower devices.

**Location:** `HermesWarmServer.kt` line 211

**Fix:** Increase timeout or add retry logic.

### Issue 3: Fallback mechanism not robust enough (MEDIUM)

**Problem:** When warm server fails, the fallback to CLI should provide better diagnostics.

**Location:** `RuntimeBridge.kt` - `runWarmTurnIfPossible()` and `runCliTurn()`

### Issue 4: Process cleanup on error (LOW)

**Problem:** Error paths may not properly clean up resources.

## Detailed Fixes

### Fix 1: Ensure aiohttp installation

In `RuntimeInstaller.kt`, verify the constant exists:

```kotlin
// Add this to constants section (around line 100)
private const val HERMES_AIOHTTP_SPEC = "hermes-agent[aiohttp]"
```

And ensure it's installed in both PyPI and source routes.

### Fix 2: Increase health check timeout

In `HermesWarmServer.kt`, line 211:
```kotlin
// Change from 2_000 to 5_000
private const val HEALTH_TIMEOUT_MS = 5_000
```

### Fix 3: Enhanced fallback logging

In `RuntimeBridge.kt`, around line 1000-1002, add better logging before falling back to CLI.

### Fix 4: Improved error handling

Add try-catch blocks around process spawning to ensure proper cleanup.

## Files to Modify

1. `app/src/main/java/com/jarves/mh/runtime/RuntimeInstaller.kt`
2. `app/src/main/java/com/jarves/mh/runtime/HermesWarmServer.kt`
3. `app/src/main/java/com/jarves/mh/runtime/HermesRuntimeBridge.kt`

## Testing Recommendations

1. Test on slow devices with low storage
2. Verify aiohttp is installed by checking the venv
3. Simulate server failures to test fallback
4. Monitor memory usage during warm server lifecycle