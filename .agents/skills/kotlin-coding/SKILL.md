---
name: kotlin-coding
description: >-
  Idiomatic Kotlin 2.1+ coding guidelines and best practices. Use when writing, refactoring, or reviewing
  Kotlin code, Coroutines, Flow/StateFlow, Structured Concurrency, kotlinx.serialization, Ktor client,
  type-safe error handling (Result/sealed classes), and memory-efficient algorithms.
---

# Idiomatic Kotlin 2.1+ Guidelines

This skill provides best practices for writing high-performance, robust, and idiomatic Kotlin code in modern Android and multiplatform applications.

## 1. Coroutines and Structured Concurrency

- **Lifecycle Ownership**:
  - Services, ViewModels, and Application classes must own their `CoroutineScope` with a `SupervisorJob()`.
  - Always cancel the scope in `onDestroy()` / `onCleared()`.
- **Thread Safety**:
  - **Never call `runBlocking` on the Android Main thread (`Dispatchers.Main`)**.
  - Confine blocking operations (disk I/O, crypto, IPC) to `Dispatchers.IO`.
  - Use `Mutex.withLock` to serialize critical asynchronous start/stop transitions.
  - Concurrency caps: Use `kotlinx.coroutines.sync.Semaphore(N)` when executing fan-out network probes or file checks.

## 2. Reactive Data Streams (Flow & StateFlow)

- **Encapsulation**:
  - Expose read-only `StateFlow<T>` or `SharedFlow<T>` publicly (`asStateFlow()`).
  - Keep backing `MutableStateFlow<T>` strictly private.
- **Backpressure & Distinct Updates**:
  - Use `distinctUntilChanged()` on flows emitting redundant values.
  - When collecting in Compose, use `collectAsStateWithLifecycle()` to stop collection in background.

## 3. Type-Safe Modeling & Serialization

- **Sealed Class Hierarchies**:
  - Model domain errors as typed sealed classes (`sealed class AppErrorCode`, `sealed interface ConversionWarning`).
  - Keep the data and core layers free of translated UI strings. Use string resource IDs and map them at presentation time.
- **Serialization**:
  - Prefer `kotlinx.serialization` with `Json { ignoreUnknownKeys = true; isLenient = true }`.
  - Use `@Serializable` data classes and avoid reflection.

## 4. Null Safety & Defensive Programming

- Avoid `!!` (force unwrap). Use `?.let {}`, `?:`, `checkNotNull()`, or explicit `error()`.
- Return `Result<T>` or use `runCatching {}` for operations that can fail gracefully without crashing.
- Prefer immutability: use `val` over `var` and `List<T>` / `Map<K, V>` over mutable counterparts in public APIs.
