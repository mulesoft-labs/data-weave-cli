package org.mule.weave.lib;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

class ScriptRuntimeLifecycleTest {

    @Test
    void destroyWaitsForAnAdmittedLeaseAndRejectsNewAdmission() throws Exception {
        long handle = ScriptRuntime.register(new ScriptRuntime());
        ScriptRuntime.EngineLease lease = ScriptRuntime.acquire(handle);
        CountDownLatch destroyStarted = new CountDownLatch(1);
        AtomicBoolean destroyReturned = new AtomicBoolean(false);
        Thread destroyer = daemonThread(() -> {
            destroyStarted.countDown();
            ScriptRuntime.destroy(handle);
            destroyReturned.set(true);
        });

        try {
            assertNotNull(lease);
            destroyer.start();
            assertTrue(destroyStarted.await(1, TimeUnit.SECONDS));
            awaitCondition(() -> admissionIsClosed(handle));
            assertFalse(destroyReturned.get());
        } finally {
            if (lease != null) {
                lease.close();
            }
            if (destroyer.getState() != Thread.State.NEW) {
                destroyer.join(1_000);
            }
            ScriptRuntime.destroy(handle);
        }

        assertFalse(destroyer.isAlive());
        assertTrue(destroyReturned.get());
        assertCannotAcquire(handle);
    }

    @Test
    void multipleLeasesMustAllDrainBeforeDestroyReturns() throws Exception {
        long handle = ScriptRuntime.register(new ScriptRuntime());
        ScriptRuntime.EngineLease firstLease = ScriptRuntime.acquire(handle);
        ScriptRuntime.EngineLease secondLease = ScriptRuntime.acquire(handle);

        Thread destroyer = null;
        AtomicBoolean destroyReturned = new AtomicBoolean(false);
        try {
            assertNotNull(firstLease);
            assertNotNull(secondLease);
            CountDownLatch destroyStarted = new CountDownLatch(1);
            destroyer = daemonThread(() -> {
                destroyStarted.countDown();
                ScriptRuntime.destroy(handle);
                destroyReturned.set(true);
            });
            destroyer.start();
            assertTrue(destroyStarted.await(1, TimeUnit.SECONDS));
            awaitCondition(() -> admissionIsClosed(handle));
            firstLease.close();
            assertFalse(destroyReturned.get());
            secondLease.close();
            destroyer.join(1_000);
        } finally {
            if (firstLease != null) {
                firstLease.close();
            }
            if (secondLease != null) {
                secondLease.close();
            }
            if (destroyer != null) {
                destroyer.join(1_000);
            }
            ScriptRuntime.destroy(handle);
        }

        assertFalse(destroyer.isAlive());
        assertTrue(destroyReturned.get());
    }

    @Test
    void concurrentDestroyCallsCoordinateAndComplete() throws Exception {
        long handle = ScriptRuntime.register(new ScriptRuntime());
        ScriptRuntime.EngineLease lease = ScriptRuntime.acquire(handle);
        CountDownLatch firstDestroyStarted = new CountDownLatch(1);
        CountDownLatch secondDestroyStarted = new CountDownLatch(1);
        AtomicBoolean firstResult = new AtomicBoolean(false);
        AtomicBoolean secondResult = new AtomicBoolean(false);
        Thread firstDestroyer = destroyThread(handle, firstDestroyStarted, firstResult);
        Thread secondDestroyer = destroyThread(handle, secondDestroyStarted, secondResult);

        try {
            assertNotNull(lease);
            firstDestroyer.start();
            assertTrue(firstDestroyStarted.await(1, TimeUnit.SECONDS));
            awaitCondition(() -> admissionIsClosed(handle));
            awaitCondition(() -> isWaiting(firstDestroyer));
            secondDestroyer.start();
            assertTrue(secondDestroyStarted.await(1, TimeUnit.SECONDS));
            awaitCondition(() -> isWaiting(secondDestroyer));
        } finally {
            if (lease != null) {
                lease.close();
            }
            if (firstDestroyer.getState() != Thread.State.NEW) {
                firstDestroyer.join(1_000);
            }
            if (secondDestroyer.getState() != Thread.State.NEW) {
                secondDestroyer.join(1_000);
            }
            ScriptRuntime.destroy(handle);
        }

        assertFalse(firstDestroyer.isAlive());
        assertFalse(secondDestroyer.isAlive());
        assertTrue(firstResult.get());
        assertTrue(secondResult.get());
        assertCannotAcquire(handle);
    }

    @Test
    void closingAnEngineLeaseTwiceIsHarmless() throws Exception {
        long handle = ScriptRuntime.register(new ScriptRuntime());
        ScriptRuntime.EngineLease firstLease = ScriptRuntime.acquire(handle);
        ScriptRuntime.EngineLease secondLease = null;
        AtomicBoolean destroyReturned = new AtomicBoolean(false);
        Thread destroyer = null;

        try {
            assertNotNull(firstLease);
            firstLease.close();
            firstLease.close();
            secondLease = ScriptRuntime.acquire(handle);
            assertNotNull(secondLease);
            destroyer = daemonThread(() -> {
                ScriptRuntime.destroy(handle);
                destroyReturned.set(true);
            });
            destroyer.start();
            awaitCondition(() -> admissionIsClosed(handle));
            assertFalse(destroyReturned.get());
        } finally {
            if (firstLease != null) {
                firstLease.close();
            }
            if (secondLease != null) {
                secondLease.close();
            }
            if (destroyer != null) {
                destroyer.join(1_000);
            }
            ScriptRuntime.destroy(handle);
        }

        assertFalse(destroyer.isAlive());
        assertTrue(destroyReturned.get());
    }

    @Test
    void interruptedDestroyRestoresInterruptAfterTheLeaseDrains() throws Exception {
        long handle = ScriptRuntime.register(new ScriptRuntime());
        ScriptRuntime.EngineLease lease = ScriptRuntime.acquire(handle);
        CountDownLatch destroyStarted = new CountDownLatch(1);
        AtomicBoolean destroyResult = new AtomicBoolean(false);
        AtomicBoolean interruptedOnReturn = new AtomicBoolean(false);
        Thread destroyer = daemonThread(() -> {
            destroyStarted.countDown();
            destroyResult.set(ScriptRuntime.destroy(handle));
            interruptedOnReturn.set(Thread.currentThread().isInterrupted());
        });

        try {
            assertNotNull(lease);
            destroyer.start();
            assertTrue(destroyStarted.await(1, TimeUnit.SECONDS));
            awaitCondition(() -> admissionIsClosed(handle));
            awaitCondition(() -> isWaiting(destroyer));
            destroyer.interrupt();
            awaitCondition(() -> isWaiting(destroyer) && !destroyer.isInterrupted());
            assertFalse(interruptedOnReturn.get());
        } finally {
            if (lease != null) {
                lease.close();
            }
            if (destroyer.getState() != Thread.State.NEW) {
                destroyer.join(1_000);
            }
            ScriptRuntime.destroy(handle);
        }

        assertFalse(destroyer.isAlive());
        assertTrue(destroyResult.get());
        assertTrue(interruptedOnReturn.get());
    }

    @Test
    void unknownHandleCannotAcquireALease() {
        assertCannotAcquire(Long.MAX_VALUE);
    }

    @Test
    void leaseExposesTheRegisteredRuntime() {
        ScriptRuntime runtime = new ScriptRuntime();
        long handle = ScriptRuntime.register(runtime);
        ScriptRuntime.EngineLease lease = ScriptRuntime.acquire(handle);

        try {
            assertNotNull(lease);
            assertSame(runtime, lease.runtime());
        } finally {
            if (lease != null) {
                lease.close();
            }
            ScriptRuntime.destroy(handle);
        }
    }

    @Test
    void registerRejectsNullRuntime() {
        assertThrows(NullPointerException.class, () -> ScriptRuntime.register(null));
    }

    @Test
    void registerDoesNotPublishANonPositiveHandleAfterOverflow() throws Exception {
        Field nextHandleField = ScriptRuntime.class.getDeclaredField("NEXT_HANDLE");
        nextHandleField.setAccessible(true);
        AtomicLong nextHandle = (AtomicLong) nextHandleField.get(null);
        long previousHandle = nextHandle.getAndSet(Long.MAX_VALUE);
        long lastPositiveHandle = 0;

        try {
            lastPositiveHandle = ScriptRuntime.register(new ScriptRuntime());
            assertEquals(Long.MAX_VALUE, lastPositiveHandle);
            assertThrows(IllegalStateException.class,
                    () -> ScriptRuntime.register(new ScriptRuntime()));
            assertCannotAcquire(Long.MIN_VALUE);
        } finally {
            if (lastPositiveHandle > 0) {
                ScriptRuntime.destroy(lastPositiveHandle);
            }
            nextHandle.set(previousHandle);
        }
    }

    private static Thread destroyThread(long handle, CountDownLatch started, AtomicBoolean result) {
        return daemonThread(() -> {
            started.countDown();
            result.set(ScriptRuntime.destroy(handle));
        });
    }

    private static Thread daemonThread(Runnable action) {
        Thread thread = new Thread(action);
        thread.setDaemon(true);
        return thread;
    }

    private static boolean admissionIsClosed(long handle) {
        ScriptRuntime.EngineLease probe = ScriptRuntime.acquire(handle);
        if (probe == null) {
            return true;
        }
        try {
            return false;
        } finally {
            probe.close();
        }
    }

    private static void assertCannotAcquire(long handle) {
        ScriptRuntime.EngineLease lease = ScriptRuntime.acquire(handle);
        try {
            assertNull(lease);
        } finally {
            if (lease != null) {
                lease.close();
            }
        }
    }

    private static boolean isWaiting(Thread thread) {
        return thread.getState() == Thread.State.WAITING;
    }

    private static void awaitCondition(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.onSpinWait();
        }
        assertTrue(condition.getAsBoolean(), "Condition was not met within one second");
    }
}
