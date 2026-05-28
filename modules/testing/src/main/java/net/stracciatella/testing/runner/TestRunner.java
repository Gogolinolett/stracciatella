package net.stracciatella.testing.runner;

import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.stracciatella.testing.api.MinecraftTest;
import net.stracciatella.testing.api.TestContext;
import net.stracciatella.testing.api.TestSuite;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class TestRunner {
    private static final Logger LOGGER = LoggerFactory.getLogger("TestRunner");
    private static final TestRunner INSTANCE = new TestRunner();

    private final List<RegisteredTest> registeredTests = new ArrayList<>();
    private final List<TestResult> results = new CopyOnWriteArrayList<>();
    private volatile boolean running;
    private volatile boolean autoRunTriggered;
    private TestContext activeContext;
    private static volatile int tickMultiplier = 1;

    private TestRunner() {
    }

    public static TestRunner instance() {
        return INSTANCE;
    }

    /**
     * Register a test suite class. All methods annotated with {@link MinecraftTest} will be discovered.
     */
    public void registerSuite(Class<?> suiteClass) {
        TestSuite suiteAnnotation = suiteClass.getAnnotation(TestSuite.class);
        String suiteName;
        if (suiteAnnotation != null && !suiteAnnotation.name().isEmpty()) {
            suiteName = suiteAnnotation.name();
        } else {
            suiteName = suiteClass.getSimpleName();
        }

        Object instance;
        try {
            instance = suiteClass.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            LOGGER.error("Failed to instantiate test suite: {}", suiteClass.getName(), e);
            return;
        }

        for (Method method : suiteClass.getDeclaredMethods()) {
            MinecraftTest annotation = method.getAnnotation(MinecraftTest.class);
            if (annotation == null) continue;

            Class<?>[] params = method.getParameterTypes();
            if (params.length != 1 || params[0] != TestContext.class) {
                LOGGER.warn("Skipping test method {} - must accept exactly one TestContext parameter", method.getName());
                continue;
            }

            method.setAccessible(true);
            int repeat = Math.max(1, annotation.repeat());
            for (int i = 1; i <= repeat; i++) {
                registeredTests.add(new RegisteredTest(suiteName, instance, method, annotation, i));
            }
        }

        LOGGER.info("Registered test suite '{}' with {} tests", suiteName,
                registeredTests.stream().filter(t -> t.suiteName().equals(suiteName)).count());
    }

    /**
     * Start running all registered tests on a dedicated thread.
     *
     * @param onComplete called on the test thread when all tests finish
     */
    public void start(Runnable onComplete) {
        if (running) return;
        running = true;

        TestContext ctx = new TestContext();
        activeContext = ctx;

        Thread testThread = new Thread(() -> {
            try {
                runAll(ctx);
            } finally {
                running = false;
                activeContext = null;
                if (onComplete != null) {
                    onComplete.run();
                }
            }
        }, "Test Runner");
        testThread.setDaemon(true);
        testThread.start();
    }

    /**
     * Called every client tick by the mixin. Signals the test thread and handles auto-run.
     */
    public void onClientTick() {
        // Auto-run when player joins world if system property is set
        if (!autoRunTriggered && !running && "true".equals(System.getProperty("stracciatella.testing.autorun"))) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null && !registeredTests.isEmpty()) {
                autoRunTriggered = true;
                LOGGER.info("Auto-run triggered by system property");
                start(() -> {
                    boolean allPassed = results.stream().allMatch(r -> r.status() == TestResult.Status.PASSED);
                    if (allPassed) {
                        LOGGER.info("All tests PASSED — shutting down");
                    } else {
                        LOGGER.error("Some tests FAILED — shutting down");
                    }
                    Minecraft.getInstance().stop();
                });
            }
        }

        // Signal the test thread that a tick has completed
        if (activeContext != null) {
            activeContext.onClientTick();
        }
    }

    /**
     * Run all registered tests synchronously on the calling thread.
     */
    private void runAll(TestContext ctx) {
        List<RegisteredTest> tests = new ArrayList<>(registeredTests);
        tests.sort(Comparator.comparingInt(t -> t.annotation().order()));
        results.clear();

        // Wait for player to be in world
        ctx.waitFor(mc -> mc.player != null, 6000);

        // Release mouse grab so cursor is free during tests
        ctx.runOnClient(mc -> mc.mouseHandler.releaseMouse());

        int speed = 7;
        try {
            speed = Integer.parseInt(System.getProperty("stracciatella.testing.tickMultiplier", "10"));
            if (speed < 1) speed = 1;
        } catch (NumberFormatException ignored) {
        }
        tickMultiplier = speed;
        ctx.runCommand("tick rate " + (speed * 20));

        LOGGER.info("Starting {} tests", tests.size());

        try {
            for (RegisteredTest test : tests) {
                LOGGER.info("Running: [{}] {}", test.suiteName(), test.displayName());

                int timeoutTicks = test.annotation().timeoutTicks() * tickMultiplier;
                ctx.setTimeoutTicks(timeoutTicks);

                long startTime = System.currentTimeMillis();
                try {
                    test.method().invoke(test.suiteInstance(), ctx);
                    long elapsed = System.currentTimeMillis() - startTime;
                    results.add(new TestResult(test.suiteName(), test.displayName(),
                            TestResult.Status.PASSED, "", elapsed));
                } catch (Exception e) {
                    long elapsed = System.currentTimeMillis() - startTime;
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    String message = cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();

                    TestResult.Status status;
                    if (message.contains("Timed out") || message.contains("timed out")) {
                        status = TestResult.Status.TIMED_OUT;
                    } else if (cause instanceof AssertionError) {
                        status = TestResult.Status.FAILED;
                    } else {
                        status = TestResult.Status.ERROR;
                    }

                    results.add(new TestResult(test.suiteName(), test.displayName(),
                            status, message, elapsed));
                }

                // Respawn the player if the test killed them. Without this, the
                // death screen blocks tick advance and subsequent tests either
                // hang on waitFor or run against a dead player. The packet is
                // the same one the death screen's "Respawn" button sends.
                respawnIfDead(ctx, test.displayName());

                // Heal the player to full HP between tests. Fall damage and
                // other survival-mode hits accumulate across tests that
                // don't actively manage gamemode — without a reset, the 10th
                // test in a chain dies before it even starts. Instant-health
                // is gamemode-agnostic (no-op in creative, full heal in
                // survival).
                try {
                    ctx.runCommand("effect give @s minecraft:instant_health 1 5 true");
                } catch (Throwable ignored) {
                    // Healing is best-effort: a failure here shouldn't block
                    // subsequent tests from running.
                }
            }

            printReport();
        } finally {
            tickMultiplier = 1;
            ctx.runCommand("tick rate 20");
        }
    }

    /**
     * If the player is currently dead (or in the death screen), send the
     * respawn packet and wait for the server to put us back in a live state.
     * Called after every test so a death-by-fall doesn't propagate to later
     * tests as a hang.
     */
    private void respawnIfDead(TestContext ctx, String testName) {
        boolean dead;
        try {
            dead = ctx.computeOnClient(mc -> mc.player != null && mc.player.isDeadOrDying());
        } catch (Throwable e) {
            LOGGER.warn("Could not check death state after '{}': {}", testName, e.getMessage());
            return;
        }
        if (!dead) {
            return;
        }
        LOGGER.warn("Player died during '{}', respawning before next test", testName);
        try {
            ctx.runOnClient(mc -> {
                // Dismiss the death screen so the next test isn't fighting a UI overlay.
                if (mc.screen != null) {
                    mc.setScreen(null);
                }
                if (mc.player != null && mc.player.connection != null) {
                    mc.player.connection.send(new ServerboundClientCommandPacket(
                            ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
                }
            });
            // Poll via waitTick — NOT waitFor — because waitFor evaluates its
            // predicate via TestContext.onClientTick, which itself fails-fast
            // when the player is dead. While the server is processing our
            // respawn the player is still dead client-side, so a waitFor here
            // would throw "Player died during test" against our own respawn.
            // 200 accelerated ticks ≈ 2s wall @ 10x — plenty for the server
            // roundtrip plus ability/position sync.
            boolean respawned = false;
            for (int i = 0; i < 200; i++) {
                ctx.waitTick();
                boolean stillDead;
                try {
                    stillDead = ctx.computeOnClient(mc -> mc.player != null && mc.player.isDeadOrDying());
                } catch (Throwable inner) {
                    stillDead = true;
                }
                if (!stillDead) {
                    respawned = true;
                    break;
                }
            }
            if (!respawned) {
                LOGGER.warn("Player did not respawn within timeout after '{}'", testName);
            }
        } catch (Throwable e) {
            // Never let respawn failure crash the test runner — we'd rather
            // continue and let subsequent tests' setup either work around the
            // dead state or fail cleanly themselves.
            LOGGER.warn("Respawn after '{}' failed: {}", testName, e.getMessage());
        }
    }

    private void printReport() {
        LOGGER.info("========== TEST RESULTS ==========");
        int passed = 0;
        int failed = 0;
        for (TestResult result : results) {
            String icon = switch (result.status()) {
                case PASSED -> "PASS";
                case FAILED -> "FAIL";
                case TIMED_OUT -> "TIMEOUT";
                case ERROR -> "ERROR";
            };
            String line = String.format("[%s] %s > %s (%dms)", icon, result.suiteName(), result.testName(), result.durationMs());
            if (!result.message().isEmpty()) {
                line += " - " + result.message();
            }
            if (result.status() == TestResult.Status.PASSED) {
                LOGGER.info(line);
                passed++;
            } else {
                LOGGER.error(line);
                failed++;
            }
        }
        LOGGER.info("==================================");
        LOGGER.info("Total: {} | Passed: {} | Failed: {}", passed + failed, passed, failed);
        LOGGER.info("==================================");
    }

    public static int getTickMultiplier() {
        return tickMultiplier;
    }

    public boolean isRunning() {
        return running;
    }

    public List<TestResult> results() {
        return List.copyOf(results);
    }
}
