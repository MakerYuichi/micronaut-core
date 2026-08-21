# Issue #12829 investigation notes

## Confirmed
- DefaultApplicationContext.stop() calls super.stop() BEFORE environment.stop()
- environment.stop() -> dropProperties() clears refreshablePropertySources from the map
- destroyLifeCycleBean() explicitly skips the environment, deferring its destruction to stop()
  ("handle environment separately, see stop() method") — suggesting ShutdownEvent listeners
  are intended to run while environment/properties are still live

## Open question
- Where exactly does ShutdownEvent get published in DefaultBeanContext (super.stop())?
- Is the bug specific to @MicronautTest(rebuildContext = true) creating a second
  environment/context whose shutdown listener reads from a different environment
  instance than the one holding live properties?

## Next step
- Trace ShutdownEvent publication in DefaultBeanContext.java

## UPDATE: core shutdown sequence is correct
Confirmed in DefaultBeanContext.stop() (line ~404):
    publishEvent(new ShutdownEvent(this));  // fires FIRST, before any bean destruction
    ...
Then DefaultApplicationContext.stop() calls super.stop() (which fires ShutdownEvent)
BEFORE environment.stop() (which wipes properties). So in the normal path,
ShutdownEvent listeners see live properties -- this ordering is correct.

## Revised hypothesis
The bug is NOT in micronaut-core's shutdown sequence. It must be specific to
@MicronautTest(rebuildContext = true), which lives in the micronaut-test module
(separate repo/module from micronaut-core -- our grep for "rebuildContext" inside
micronaut-core found nothing, confirming this).

Likely candidates:
- rebuildContext may create a NEW environment/context per test, and the
  ShutdownEvent listener bean may be holding a reference to (or reading from)
  a STALE environment from a previous test run, rather than the current live one.
- Or: test teardown between tests may call environment.stop()/close() at a
  different point than production shutdown does, ahead of firing ShutdownEvent
  to the rebuilt context's listeners.

## Next step
Need to find the micronaut-test module/repo (likely a SEPARATE github repo:
micronaut-projects/micronaut-test) and look at how rebuildContext manages
context/environment lifecycle between test executions.

## UPDATE: found the likely real mechanism
DefaultApplicationContext has two constructors:
  1. DefaultApplicationContext(configuration) -> environmentManaged = true
  2. DefaultApplicationContext(configuration, environment) -> environmentManaged = false

stop() only calls environment.stop() when environmentManaged is true (line 312).

Hypothesis: @MicronautTest(rebuildContext = true) likely reuses one Environment
instance across multiple rebuilt ApplicationContexts (via constructor #2, to avoid
re-reading config on every rebuild). If so, environment lifecycle becomes decoupled
from any single context's lifecycle, which could explain properties being wiped/stale
at unexpected times relative to a specific rebuilt context's ShutdownEvent.

Properties interface is a @ConfigurationProperties proxy (isEnabled() likely resolves
live against Environment on each call, not a cached value) -- consistent with a
"shared/stale environment state" bug rather than a per-context caching bug.

## Next step
Write a test using constructor #2 (shared environment across two contexts) to try
to reproduce "second context's ShutdownEvent listener sees wrong environment state"
purely within micronaut-core, without needing the micronaut-test module.

## CORRECTION: earlier shared-Environment theory was wrong
Verified against micronaut-test's actual source (AbstractMicronautExtension.java):
rebuildContext builds a completely FRESH ApplicationContext + Environment via
builder.build() -- it does NOT reuse/share the old Environment instance.
So the shared-environment reproduction, while a real latent bug in
DefaultApplicationContext.stop(), is NOT the mechanism behind #12829.

## ACTUAL root cause, confirmed by running the real reproducer
Ran auloin/micronaut-5-regressions' PropertiesShutdownHookTest directly. Log sequence:

  "On startup - Is app enabled? true"          (test-scoped @Property override applied)
  "On shutdown - Is app still enabled? false"  (override already reverted by shutdown time)

The test uses @Property(name = "app.enabled", value = "true") on the test METHOD.
This is a per-test property override applied/reverted by micronaut-test's JUnit5
extension around the test method body. The context's actual stop()/ShutdownEvent
fires later (at rebuild/teardown time), AFTER the per-test property override has
already been reverted -- so the ShutdownEvent listener reads the reverted
(default/false) value instead of the value that was live during the test.

This points to the bug living in micronaut-test (specifically the ordering between
per-test @Property revert and context stop/ShutdownEvent), not in micronaut-core's
shutdown sequence itself, which we already verified fires ShutdownEvent correctly
relative to its OWN environment lifecycle.
