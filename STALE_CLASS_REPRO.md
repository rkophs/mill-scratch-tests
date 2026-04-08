# Mill Stale Class File Reproduction

This document demonstrates how Mill's incremental compilation leaves stale `.class` files
when source files are deleted, causing silent build corruption.

## Setup

Two modules exist:

- **`mill-generated-code-test`** — Uses `org.immutables:value` annotation processing.
  `TestImmutableAlpha` depends on the *generated* `ImmutableTestImmutableBeta` class.
- **`mill-non-generated-code-test`** — Plain Java POJOs.
  `Gamma` depends on `Delta`.

## Reproduction Steps

### 1. Clean build (rm -rf out/)

```
$ rm -rf out/
$ mill __.compile
85] compiling 2 Java sources to out/mill-non-generated-code-test/compile.dest/classes ...
86] compiling 2 Java sources to out/mill-generated-code-test/compile.dest/classes ...
86/86, SUCCESS] mill __.compile 17s
```

### 2. Verify compiled classes

```
$ ls out/mill-generated-code-test/compile.dest/classes/com/rkophs/mill/test/
ImmutableTestImmutableAlpha$Builder.class
ImmutableTestImmutableAlpha.class
ImmutableTestImmutableBeta$Builder.class
ImmutableTestImmutableBeta.class
TestImmutableAlpha.class
TestImmutableBeta.class

$ ls out/mill-non-generated-code-test/compile.dest/classes/com/rkophs/mill/test/
Delta.class
Gamma.class
```

### 3. Delete source files that other classes depend on

```
$ rm mill-generated-code-test/src/com/rkophs/mill/test/TestImmutableBeta.java
$ rm mill-non-generated-code-test/src/com/rkophs/mill/test/Delta.java
```

### 4. Incremental compile succeeds (BUG)

```
$ mill __.compile
85] compiling 1 Java source to out/mill-non-generated-code-test/compile.dest/classes ...
86] compiling 1 Java source to out/mill-generated-code-test/compile.dest/classes ...
86/86, SUCCESS] mill __.compile 1s
```

**Expected:** Compilation should fail — `Gamma` depends on `Delta` and `TestImmutableAlpha`
depends on `ImmutableTestImmutableBeta`, both of which no longer have source files.

**Actual:** Compilation succeeds because stale `.class` files for `Delta` and
`ImmutableTestImmutableBeta` remain in the output directory:

```
$ ls out/mill-generated-code-test/compile.dest/classes/com/rkophs/mill/test/
ImmutableTestImmutableAlpha$Builder.class
ImmutableTestImmutableAlpha.class
ImmutableTestImmutableBeta$Builder.class   <-- STALE
ImmutableTestImmutableBeta.class           <-- STALE
TestImmutableAlpha.class
TestImmutableBeta.class                    <-- STALE

$ ls out/mill-non-generated-code-test/compile.dest/classes/com/rkophs/mill/test/
Delta.class    <-- STALE
Gamma.class
```

### 5. Assembly jars include stale classes

```
$ mill __.assembly
138/138, SUCCESS] mill __.assembly 1s

$ jar tf out/mill-generated-code-test/assembly.dest/out.jar | grep com/rkophs
com/rkophs/mill/test/TestImmutableBeta.class              <-- STALE
com/rkophs/mill/test/ImmutableTestImmutableAlpha$Builder.class
com/rkophs/mill/test/ImmutableTestImmutableBeta.class     <-- STALE
com/rkophs/mill/test/ImmutableTestImmutableAlpha.class
com/rkophs/mill/test/TestImmutableAlpha.class
com/rkophs/mill/test/ImmutableTestImmutableBeta$Builder.class  <-- STALE

$ jar tf out/mill-non-generated-code-test/assembly.dest/out.jar | grep com/rkophs
com/rkophs/mill/test/Gamma.class
com/rkophs/mill/test/Delta.class    <-- STALE
```

### 6. `mill clean` does NOT remove stale classes

```
$ mill clean
1/1, SUCCESS] mill clean

$ mill __.compile
85] compiling 1 Java source to out/mill-non-generated-code-test/compile.dest/classes ...
86] compiling 1 Java source to out/mill-generated-code-test/compile.dest/classes ...
86/86, SUCCESS] mill __.compile 1s

$ ls out/mill-generated-code-test/compile.dest/classes/com/rkophs/mill/test/
ImmutableTestImmutableAlpha$Builder.class
ImmutableTestImmutableAlpha.class
ImmutableTestImmutableBeta$Builder.class   <-- STILL STALE
ImmutableTestImmutableBeta.class           <-- STILL STALE
TestImmutableAlpha.class
TestImmutableBeta.class                    <-- STILL STALE

$ ls out/mill-non-generated-code-test/compile.dest/classes/com/rkophs/mill/test/
Delta.class    <-- STILL STALE
Gamma.class
```

`mill clean` does not remove the stale `.class` files. Only a full `rm -rf out/` does.

### 7. Only `rm -rf out/` proves the code is actually broken

```
$ rm -rf out/
$ mill __.compile
85] [error] mill-non-generated-code-test/src/com/rkophs/mill/test/Gamma.java:5:19
85]     private final Delta delta;
85]                   ^^^^^
85] cannot find symbol

86] [error] mill-generated-code-test/src/com/rkophs/mill/test/TestImmutableAlpha.java:8:5
86]     ImmutableTestImmutableBeta getBeta();
86]     ^^^^^^^^^^^^^^^^^^^^^^^^^^
86] cannot find symbol

86/86, 2 FAILED] mill __.compile 16s
```

## Summary

When a source file is deleted, Mill's incremental compilation does not remove the
corresponding `.class` files from the output directory. This causes:

1. **Silent compilation success** — The compiler resolves symbols against stale `.class`
   files, masking the fact that the source no longer exists.
2. **Stale artifacts in jars** — Assembly (and presumably publishing) packages dead classes
   whose source has been deleted.
3. **Non-reproducible builds** — An incremental build produces a different (and incorrect)
   result compared to a clean build.

This affects both annotation-processor-generated code and plain Java source files.
