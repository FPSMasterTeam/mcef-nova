/*
 * MCEF (Minecraft Chromium Embedded Framework)
 * Copyright (C) 2025 CCBlueX
 * Copyright (C) 2023 CinemaMod Group
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301
 * USA
 */

package net.ccbluex.liquidbounce.mcef.cef;

import net.ccbluex.liquidbounce.mcef.MCEF;
import org.cef.CefApp;

import java.util.ArrayDeque;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Dedicated CEF browser-process UI thread (Windows/Linux).
 *
 * <p>CEF requires CefInitialize, CefDoMessageLoopWork and CefShutdown to run on one and the same
 * thread — that thread becomes the browser process "UI thread". Historically MCEF used the game's
 * render thread for this and pumped N_DoMessageLoopWork once per frame. That call blocks: with the
 * browser idle, Chromium drops the Windows timer resolution to 15.6 ms and the pump's internal
 * waits quantize to it, eating ~13 ms of every game frame (the "40 fps until the GUI opens" bug).
 *
 * <p>This thread takes that role instead, and pumps exactly when CEF asks it to via
 * OnScheduleMessagePumpWork (external_message_pump=true — the integration the CEF docs prescribe;
 * this jcef's native side always enables it for windowless rendering). However long a pump call
 * blocks here, the render thread never notices. Browser paints consequently arrive on this thread;
 * {@link MCEFBrowser} buffers them CPU-side and the render thread uploads via
 * {@link MCEF#update()}.
 *
 * <p>Not used on macOS: there the native layer marshals CEF calls onto the AppKit main thread
 * (which is Minecraft's render thread) and the legacy per-frame pump stays.
 */
public final class CefMessageLoopThread implements CefApp.MessagePumpScheduler {

    /**
     * Upper clamp for CEF-requested pump delays, mirroring cefclient's external pump reference
     * implementation (kMaxTimerDelay = 1000/30). Guards against a lost wakeup starving the loop.
     */
    private static final long MAX_TIMER_DELAY_MS = 1000L / 30L;

    /**
     * Pump at least this often even if CEF never schedules work — pure insurance; with
     * external_message_pump every posted task triggers a schedule callback.
     */
    private static final long IDLE_HEARTBEAT_MS = 500L;

    private final Object lock = new Object();
    private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
    private final Thread thread;

    /** Nano deadline of the next requested pump; Long.MAX_VALUE = none requested. Guarded by lock. */
    private long pumpDeadlineNanos = Long.MAX_VALUE;
    private boolean stopped = false;

    /** The CefApp to pump. Set once initialization has completed on this thread. */
    private final AtomicReference<CefApp> pumpTarget = new AtomicReference<>();

    public CefMessageLoopThread() {
        thread = new Thread(this::run, "MCEF-CefMessageLoop");
        thread.setDaemon(true);
        thread.start();
    }

    public boolean isOnThread() {
        return Thread.currentThread() == thread;
    }

    /** Start pumping this app. Called after N_Initialize succeeded (on this thread). */
    public void attachPumpTarget(CefApp app) {
        pumpTarget.set(app);
        scheduleMessagePumpWork(0);
    }

    /** CEF's OnScheduleMessagePumpWork — called from arbitrary native threads. */
    @Override
    public void scheduleMessagePumpWork(long delayMs) {
        long clamped = Math.max(0L, Math.min(delayMs, MAX_TIMER_DELAY_MS));
        long deadline = System.nanoTime() + clamped * 1_000_000L;
        synchronized (lock) {
            if (deadline < pumpDeadlineNanos) {
                pumpDeadlineNanos = deadline;
                lock.notifyAll();
            }
        }
    }

    /** Run a task on the CEF thread and wait for its result. Used for init and shutdown. */
    public <T> T invokeAndWait(Callable<T> callable) {
        return invokeAndWait(callable, 0L);
    }

    /**
     * Run a task on the CEF thread and wait for its result, giving up after {@code timeoutMs}
     * (0 = wait forever). On timeout the task keeps running on the CEF thread; null is returned.
     */
    public <T> T invokeAndWait(Callable<T> callable, long timeoutMs) {
        if (isOnThread()) {
            try {
                return callable.call();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        synchronized (lock) {
            if (stopped) {
                throw new IllegalStateException("CEF message loop thread is stopped");
            }
            tasks.add(() -> {
                try {
                    result.set(callable.call());
                } catch (Throwable t) {
                    error.set(t);
                } finally {
                    latch.countDown();
                }
            });
            lock.notifyAll();
        }
        try {
            if (timeoutMs <= 0) {
                latch.await();
            } else if (!latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                MCEF.INSTANCE.getLogger().warn("CEF thread task timed out after {} ms", timeoutMs);
                return null;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted waiting for CEF thread task", e);
        }
        if (error.get() != null) {
            if (error.get() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new RuntimeException(error.get());
        }
        return result.get();
    }

    /** Stop the loop after the currently queued tasks have run. */
    public void stop() {
        synchronized (lock) {
            stopped = true;
            lock.notifyAll();
        }
        if (!isOnThread()) {
            try {
                thread.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void run() {
        while (true) {
            Runnable task = null;
            boolean pump = false;
            synchronized (lock) {
                while (true) {
                    if (!tasks.isEmpty()) {
                        task = tasks.poll();
                        break;
                    }
                    if (stopped) {
                        return;
                    }
                    long now = System.nanoTime();
                    if (pumpDeadlineNanos <= now) {
                        // Consume the request before pumping: a schedule arriving DURING the pump
                        // must set a fresh deadline rather than be swallowed.
                        pumpDeadlineNanos = Long.MAX_VALUE;
                        pump = true;
                        break;
                    }
                    long waitMs = pumpDeadlineNanos == Long.MAX_VALUE
                            ? IDLE_HEARTBEAT_MS
                            : Math.max(1L, (pumpDeadlineNanos - now) / 1_000_000L);
                    try {
                        lock.wait(waitMs);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    if (pumpDeadlineNanos == Long.MAX_VALUE && tasks.isEmpty() && !stopped) {
                        // Idle heartbeat elapsed with nothing scheduled.
                        pump = true;
                        break;
                    }
                }
            }
            if (task != null) {
                try {
                    task.run();
                } catch (Throwable t) {
                    MCEF.INSTANCE.getLogger().error("CEF thread task failed", t);
                }
                continue;
            }
            if (pump) {
                CefApp app = pumpTarget.get();
                if (app != null) {
                    try {
                        app.N_DoMessageLoopWork();
                    } catch (Throwable t) {
                        MCEF.INSTANCE.getLogger().error("CefDoMessageLoopWork failed", t);
                    }
                }
            }
        }
    }
}
