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
