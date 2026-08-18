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
