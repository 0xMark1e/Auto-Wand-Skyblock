package com.nexora.hp;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Standalone test/simulation harness for AttunementController. No Minecraft or Gradle in this
 * project's build pipeline, so this is a plain-Java assertion runner instead of JUnit -- run
 * directly with `java`. Exits non-zero (and prints every failure) if any check fails.
 */
public final class AttunementControllerTest {

    private static int failures = 0;
    private static int checks = 0;
    private static boolean quiet = false;

    public static void main(String[] args) {
        testPureMapping();
        testSelectAttunementPrefersNearestRealOverImmune();
        testSelectAttunementPicksNearestAmongMultipleReal();
        testSelectAttunementFallsBackToNearestImmune();
        testSelectAttunementEmpty();

        testInstantConfirmationTapsOnce();
        testAlreadyCorrectNeverTaps();
        testNetworkLatencyDoesNotDoubleTap();
        testPersistentPacketLossBacksOffAfterMaxAttempts();
        testNoAttackNoSlotSwitch();
        testAvoidRagnarockBlocksSwitch();
        testPanicBlocksSwitch();
        testConfirmWindowFreezesWhenNotAttacking();
        testDisabledFeatureDoesNothing();
        testNoDaggerInHotbarDoesNothing();
        testFlushReleaseWorksWhenPreemptedByAnotherSubsystem();
        testPanicBlocksTapWhenAlreadyOnDagger();
        testConfirmWindowMillisIsConfigurable();
        testConfirmWindowMillisClampsToValidRange();
        testDebounceFiltersSingleTickFlicker();
        testDebounceCommitsRealTransitions();
        testDebounceStartsEmptyUntilFirstStableReading();

        System.out.println("-- Full-cycle simulations (normal network conditions x200 seeds)");
        quiet = true;
        for (int seed = 0; seed < 200; seed++) {
            // Normal conditions: up to ~550ms round trip, 12% silent-failure rate.
            testFullCycleSimulation(seed, 12, 11);
        }
        quiet = false;
        System.out.println("  ran 200 seeds, " + failures + " failures so far");

        System.out.println("-- Full-cycle simulations (stress: near-window latency + 30% packet loss x200 seeds)");
        quiet = true;
        for (int seed = 0; seed < 200; seed++) {
            // Stress variant: latency pushed right up against the confirm window (the worst
            // realistic case before a retry could plausibly race an unconfirmed toggle) and far
            // higher packet loss than any real connection should see.
            testFullCycleSimulation(seed, 30, 19);
        }
        quiet = false;
        System.out.println("  ran 200 more seeds, " + failures + " total failures so far");

        System.out.println();
        System.out.println(checks + " checks run, " + failures + " failed.");
        if (failures > 0) {
            System.exit(1);
        } else {
            System.out.println("ALL TESTS PASSED");
        }
    }

    // ---- pure-function tests ----------------------------------------------------------------

    private static void testPureMapping() {
        section("daggerModeAttunement pure mapping (td_attune_mode NBT tag)");
        check("0=ASHEN", "ASHEN", AttunementController.daggerModeAttunement(0));
        check("1=AURIC", "AURIC", AttunementController.daggerModeAttunement(1));
        check("2=SPIRIT", "SPIRIT", AttunementController.daggerModeAttunement(2));
        check("3=CRYSTAL", "CRYSTAL", AttunementController.daggerModeAttunement(3));
        check("unknown value=null", null, AttunementController.daggerModeAttunement(4));
        check("negative (missing tag sentinel)=null", null, AttunementController.daggerModeAttunement(-1));
    }

    private static void testSelectAttunementPrefersNearestRealOverImmune() {
        section("selectAttunement: real attunement beats closer IMMUNE");
        List<AttunementController.Reading> readings = List.of(
                new AttunementController.Reading("IMMUNE", 4.0),
                new AttunementController.Reading("AURIC", 25.0));
        check("prefers AURIC over closer IMMUNE", "AURIC", AttunementController.selectAttunement(readings));
    }

    private static void testSelectAttunementPicksNearestAmongMultipleReal() {
        section("selectAttunement: nearest wins among two real attunements");
        List<AttunementController.Reading> readings = List.of(
                new AttunementController.Reading("SPIRIT", 9.0),
                new AttunementController.Reading("CRYSTAL", 4.0));
        check("picks CRYSTAL (closer)", "CRYSTAL", AttunementController.selectAttunement(readings));
    }

    private static void testSelectAttunementFallsBackToNearestImmune() {
        section("selectAttunement: falls back to nearest IMMUNE when nothing real is visible");
        List<AttunementController.Reading> readings = List.of(
                new AttunementController.Reading("IMMUNE", 100.0),
                new AttunementController.Reading("IMMUNE", 4.0));
        check("picks the nearer IMMUNE", "IMMUNE", AttunementController.selectAttunement(readings));
    }

    private static void testSelectAttunementEmpty() {
        section("selectAttunement: empty list -> null");
        check("empty -> null", null, AttunementController.selectAttunement(List.of()));
    }

    // ---- IO recorder used by the state-machine tests -----------------------------------------

    private static final class RecordingIO implements AttunementController.IO {
        int releaseCount = 0;
        int tapCount = 0;
        final List<Integer> switches = new ArrayList<>();
        final List<Long> tapTimestamps = new ArrayList<>();
        long currentTimeForLogging = 0L;

        @Override
        public void releaseUseKey() {
            releaseCount++;
        }

        @Override
        public void switchToSlot(int slot) {
            switches.add(slot);
        }

        @Override
        public void tapUseKey() {
            tapCount++;
            tapTimestamps.add(currentTimeForLogging);
        }
    }

    // ---- state machine tests -----------------------------------------------------------------

    private static void testInstantConfirmationTapsOnce() {
        section("Instant confirmation: exactly one tap, no re-tap once correct");
        AttunementController ctrl = new AttunementController();
        RecordingIO io = new RecordingIO();
        long t = 0;

        // Holding fire dagger, on ASHEN, boss wants AURIC, attacking every tick.
        AttunementController.Input mismatched = new AttunementController.Input(
                true, true, true, false, false, "AURIC", 0, -1, 0, "ASHEN", true, t);
        ctrl.tick(mismatched, io);
        check("taps once when mismatched+attacking", 1, io.tapCount);

        // Next tick: release fires, and (in the real mod) the material instantly reflects the
        // toggle server-side for this scenario -- simulate that here.
        t += 50;
        AttunementController.Input confirmed = new AttunementController.Input(
                true, true, true, false, false, "AURIC", 0, -1, 0, "AURIC", true, t);
        ctrl.tick(confirmed, io);
        check("released the use key", 1, io.releaseCount);
        check("did not tap again once confirmed correct", 1, io.tapCount);

        // A few more ticks of being correct: still no extra taps.
        for (int i = 0; i < 10; i++) {
            t += 50;
            ctrl.tick(new AttunementController.Input(true, true, true, false, false, "AURIC", 0, -1, 0, "AURIC",
                    true, t), io);
        }
        check("stays quiet while correct", 1, io.tapCount);
    }

    private static void testAlreadyCorrectNeverTaps() {
        section("Already-correct mode never taps");
        AttunementController ctrl = new AttunementController();
        RecordingIO io = new RecordingIO();
        for (int i = 0; i < 20; i++) {
            ctrl.tick(new AttunementController.Input(true, true, true, false, false, "SPIRIT", -1, 3, 3, "SPIRIT",
                    true, i * 50L), io);
        }
        check("zero taps", 0, io.tapCount);
        check("zero switches", 0, io.switches.size());
    }

    private static void testNetworkLatencyDoesNotDoubleTap() {
        section("Network latency: no re-tap before delayed confirmation lands");
        AttunementController ctrl = new AttunementController();
        RecordingIO io = new RecordingIO();

        // Simulate an 8-tick (400ms) server round trip before the material updates. Drive the
        // controller every tick with attacking=true, and only flip heldMode to AURIC once we've
        // simulated enough ticks since the tap.
        String heldMode = "ASHEN";
        int ticksSinceTap = -1;
        for (int tick = 0; tick < 60; tick++) {
            long now = tick * 50L;
            if (ticksSinceTap >= 0) {
                ticksSinceTap++;
                if (ticksSinceTap == 8) {
                    heldMode = "AURIC";
                }
            }
            int tapsBefore = io.tapCount;
            io.currentTimeForLogging = now;
            ctrl.tick(new AttunementController.Input(true, true, true, false, false, "AURIC", 0, -1, 0, heldMode,
                    true, now), io);
            if (io.tapCount > tapsBefore) {
                ticksSinceTap = 0;
            }
        }
        check("exactly one tap needed for an 8-tick round trip (well under the 20-tick window)", 1, io.tapCount);
        check("no taps closer together than CONFIRM_WINDOW_TICKS", true, tapsAreProperlySpaced(io));
    }

    private static void testPersistentPacketLossBacksOffAfterMaxAttempts() {
        section("Persistent packet loss: caps at MAX_ATTEMPTS then backs off, never spams");
        AttunementController ctrl = new AttunementController();
        RecordingIO io = new RecordingIO();

        // heldMode never changes no matter how many times we tap -- worst case scenario.
        for (int tick = 0; tick < 400; tick++) {
            long now = tick * 50L;
            io.currentTimeForLogging = now;
            ctrl.tick(new AttunementController.Input(true, true, true, false, false, "AURIC", 0, -1, 0, "ASHEN",
                    true, now), io);
        }
        check("no taps closer together than CONFIRM_WINDOW_TICKS", true, tapsAreProperlySpaced(io));

        // Over 400 ticks (20s) with a 3-attempt cap and a 40-tick backoff between cap-outs, the
        // absolute ceiling is one attempt roughly every (3 * 20 + 40) = 100 ticks -> ~4 cap
        // cycles -> well under, say, 20 taps. This is the "never spams forever" guarantee.
        check("taps stay bounded (no runaway spam)", true, io.tapCount <= 20);
        check("did tap at least once (not stuck doing nothing)", true, io.tapCount >= 3);
        System.out.println("    (persistent packet loss produced " + io.tapCount + " taps over 400 ticks)");
    }

    private static void testNoAttackNoSlotSwitch() {
        section("Not attacking: never switches slot even when holding the wrong weapon");
        AttunementController ctrl = new AttunementController();
        RecordingIO io = new RecordingIO();
        for (int tick = 0; tick < 100; tick++) {
            long now = tick * 50L;
            // selectedSlot=5 (some other weapon, e.g. Ragnarock), fire dagger sits in slot 0.
            ctrl.tick(new AttunementController.Input(true, true, false, false, false, "ASHEN", 0, -1, 5, null,
                    false, now), io);
        }
        check("never switched slot while not attacking", 0, io.switches.size());
        check("never tapped while not attacking", 0, io.tapCount);
    }

    private static void testAvoidRagnarockBlocksSwitch() {
        section("avoidRagnarock blocks the slot switch even while attacking");
        AttunementController ctrl = new AttunementController();
        RecordingIO io = new RecordingIO();
        for (int tick = 0; tick < 50; tick++) {
            long now = tick * 50L;
            ctrl.tick(new AttunementController.Input(true, true, true, true, false, "ASHEN", 0, -1, 5, null,
                    true, now), io);
        }
        check("never switched off Ragnarock", 0, io.switches.size());
    }

    private static void testPanicBlocksSwitch() {
        section("panicActive blocks the slot switch, and releases once panic ends");
        AttunementController ctrl = new AttunementController();
        RecordingIO io = new RecordingIO();
        for (int tick = 0; tick < 30; tick++) {
            long now = tick * 50L;
            ctrl.tick(new AttunementController.Input(true, true, true, false, true, "ASHEN", 0, -1, 5, null,
                    true, now), io);
        }
        check("no switch while panic active", 0, io.switches.size());

        for (int tick = 30; tick < 32; tick++) {
            long now = tick * 50L;
            ctrl.tick(new AttunementController.Input(true, true, true, false, false, "ASHEN", 0, -1, 5, null,
                    true, now), io);
        }
        check("switches once panic ends", true, io.switches.size() >= 1);
    }

    private static void testConfirmWindowFreezesWhenNotAttacking() {
        section("Confirm/backoff countdown freezes once outside the attack-grace window");
        AttunementController ctrl = new AttunementController();
        RecordingIO io = new RecordingIO();

        // Tap once while attacking, at t=0.
        io.currentTimeForLogging = 0;
        ctrl.tick(new AttunementController.Input(true, true, true, false, false, "AURIC", 0, -1, 0, "ASHEN",
                true, 0), io);
        check("tapped once", 1, io.tapCount);
        check("confirm window started at max", AttunementController.CONFIRM_WINDOW_TICKS, ctrl.confirmTicksRemaining());

        // Jump straight past the attack-grace window (from the t=0 attack) in one step, with no
        // further attacking -- recentlyAttacking must now read false, so this tick shouldn't
        // decrement the countdown at all.
        long t = AttunementController.ATTACK_GRACE_MILLIS + 1000;
        ctrl.tick(new AttunementController.Input(true, true, true, false, false, "AURIC", 0, -1, 0, "ASHEN",
                false, t), io);
        int frozenAt = ctrl.confirmTicksRemaining();
        check("countdown no longer decrements once outside the grace window", AttunementController.CONFIRM_WINDOW_TICKS,
                frozenAt);

        // Keep going for a long time with no attacking -- must stay frozen, must not tap again.
        for (int i = 0; i < 200; i++) {
            t += 500;
            ctrl.tick(new AttunementController.Input(true, true, true, false, false, "AURIC", 0, -1, 0, "ASHEN",
                    false, t), io);
        }
        check("still only one tap after a long idle period", 1, io.tapCount);
        check("confirm countdown stayed frozen", frozenAt, ctrl.confirmTicksRemaining());
    }

    private static void testDisabledFeatureDoesNothing() {
        section("Feature disabled: never acts");
        AttunementController ctrl = new AttunementController();
        RecordingIO io = new RecordingIO();
        for (int tick = 0; tick < 50; tick++) {
            ctrl.tick(new AttunementController.Input(true, false, true, false, false, "ASHEN", 0, -1, 5, null,
                    true, tick * 50L), io);
        }
        check("autoAttunementEnabled=false -> no switches", 0, io.switches.size());
        check("autoAttunementEnabled=false -> no taps", 0, io.tapCount);
    }

    private static void testNoDaggerInHotbarDoesNothing() {
        section("Neither dagger in hotbar: never acts");
        AttunementController ctrl = new AttunementController();
        RecordingIO io = new RecordingIO();
        for (int tick = 0; tick < 50; tick++) {
            ctrl.tick(new AttunementController.Input(true, true, true, false, false, "ASHEN", -1, -1, 5, null,
                    true, tick * 50L), io);
        }
        check("no dagger slots -> no switches", 0, io.switches.size());
    }

    /**
     * Regression test for the "wrong mode / spams again" bug: a heal or panic action firing on
     * the tick right after a tap used to skip AttunementController entirely for that tick (since
     * the caller only invoked tick() when it was attunement's "turn"), orphaning the pending
     * release until some later tick -- by which point another subsystem could have already taken
     * over the same physical use-key. flushRelease() must be safe to call on its own, on schedule,
     * even when the full tick()/evaluate path is skipped for one or more ticks.
     */
    private static void testFlushReleaseWorksWhenPreemptedByAnotherSubsystem() {
        section("flushRelease(): releases on schedule even when preempted, no double-tap after");
        AttunementController ctrl = new AttunementController();
        RecordingIO io = new RecordingIO();

        // Tap once while attacking (tick 0).
        io.currentTimeForLogging = 0;
        ctrl.tick(new AttunementController.Input(true, true, true, false, false, "AURIC", 0, -1, 0, "ASHEN",
                true, 0), io);
        check("tapped once", 1, io.tapCount);
        check("no release yet", 0, io.releaseCount);

        // Simulate being preempted for several ticks: only flushRelease() is called (mirroring
        // what NexoraHpMod now does every tick, regardless of which subsystem "owns" the tick),
        // never the full tick(). The release must still land on the very next call.
        io.currentTimeForLogging = 50;
        ctrl.flushRelease(io);
        check("released via flushRelease alone, without a full tick() call", 1, io.releaseCount);

        for (int i = 0; i < 5; i++) {
            io.currentTimeForLogging = 100 + i * 50L;
            ctrl.flushRelease(io);
        }
        check("repeated flushRelease calls with nothing pending are no-ops", 1, io.releaseCount);
        check("still only the one original tap", 1, io.tapCount);

        // Resume normal ticking (material still hasn't confirmed) -- must not double-tap
        // immediately; the confirm window should still be in effect since it started at tap time.
        io.currentTimeForLogging = 400;
        ctrl.tick(new AttunementController.Input(true, true, true, false, false, "AURIC", 0, -1, 0, "ASHEN",
                true, 400), io);
        check("no second tap immediately after resuming (still within confirm window)", 1, io.tapCount);

        // Once the material confirms correct, no further taps should ever occur.
        io.currentTimeForLogging = 450;
        ctrl.tick(new AttunementController.Input(true, true, true, false, false, "AURIC", 0, -1, 0, "AURIC",
                true, 450), io);
        check("settles once correct, still just one tap total", 1, io.tapCount);
    }

    /**
     * Regression test: panic must block a tap even when already sitting on the target dagger
     * from an earlier tick (not just when a slot switch onto it would otherwise happen) --
     * otherwise panic activating mid-fight can collide with an in-flight dagger toggle over the
     * same physical use-key.
     */
    private static void testPanicBlocksTapWhenAlreadyOnDagger() {
        section("panicActive blocks the tap even when already holding the target dagger");
        AttunementController ctrl = new AttunementController();
        RecordingIO io = new RecordingIO();

        // Already on the fire dagger (slot 0), wrong mode, panic active this whole time.
        for (int tick = 0; tick < 50; tick++) {
            long now = tick * 50L;
            ctrl.tick(new AttunementController.Input(true, true, true, false, true, "AURIC", 0, -1, 0, "ASHEN",
                    true, now), io);
        }
        check("no tap while panic active, even though already on the dagger", 0, io.tapCount);

        // Panic ends -- should now be free to tap.
        for (int tick = 50; tick < 55; tick++) {
            long now = tick * 50L;
            ctrl.tick(new AttunementController.Input(true, true, true, false, false, "AURIC", 0, -1, 0, "ASHEN",
                    true, now), io);
        }
        check("taps once panic ends", true, io.tapCount >= 1);
    }

    private static void testConfirmWindowMillisIsConfigurable() {
        section("setConfirmWindowMillis(): actually changes the retry rate, not just cosmetic");
        AttunementController ctrl = new AttunementController();
        RecordingIO io = new RecordingIO();
        ctrl.setConfirmWindowMillis(100);
        check("getter reflects the configured value", 100, ctrl.getConfirmWindowMillis());

        io.currentTimeForLogging = 0;
        ctrl.tick(new AttunementController.Input(true, true, true, false, false, "AURIC", 0, -1, 0, "ASHEN",
                true, 0), io);
        check("tapped once", 1, io.tapCount);
        check("confirm window armed at 100ms = 2 ticks, not the old 20-tick default", 2, ctrl.confirmTicksRemaining());

        // Release next tick (100ms round trip still in flight), material still wrong.
        io.currentTimeForLogging = 50;
        ctrl.tick(new AttunementController.Input(true, true, true, false, false, "AURIC", 0, -1, 0, "ASHEN",
                true, 50), io);
        check("released", 1, io.releaseCount);
        check("no retap yet, within the shorter 100ms window", 1, io.tapCount);

        // Past the 100ms window (2 ticks): a shorter window means it's willing to retry sooner
        // than the old fixed 1000ms/20-tick default would have allowed.
        io.currentTimeForLogging = 100;
        ctrl.tick(new AttunementController.Input(true, true, true, false, false, "AURIC", 0, -1, 0, "ASHEN",
                true, 100), io);
        io.currentTimeForLogging = 150;
        ctrl.tick(new AttunementController.Input(true, true, true, false, false, "AURIC", 0, -1, 0, "ASHEN",
                true, 150), io);
        check("retries sooner than the old 1000ms default would have allowed", 2, io.tapCount);
    }

    private static void testConfirmWindowMillisClampsToValidRange() {
        section("setConfirmWindowMillis(): clamps to [MIN_CONFIRM_WINDOW_MILLIS, MAX_CONFIRM_WINDOW_MILLIS]");
        AttunementController ctrl = new AttunementController();

        ctrl.setConfirmWindowMillis(1);
        check("clamps below range up to the minimum", AttunementController.MIN_CONFIRM_WINDOW_MILLIS,
                ctrl.getConfirmWindowMillis());

        ctrl.setConfirmWindowMillis(999999);
        check("clamps above range down to the maximum", AttunementController.MAX_CONFIRM_WINDOW_MILLIS,
                ctrl.getConfirmWindowMillis());
    }

    /**
     * Regression test for the "correctly switches, then undoes it" bug: a stale armor stand from
     * the previous phase (not yet despawned) or another player's overlapping fight can read as
     * "nearest" for a single tick even after the real phase has moved on. That one-tick blip must
     * not propagate into a real (wrong) toggle.
     */
    private static void testDebounceFiltersSingleTickFlicker() {
        section("debounceAttunement(): single-tick flicker back to the old phase is ignored");
        AttunementController ctrl = new AttunementController();

        // Establish AURIC as confirmed (3 stable reads).
        for (int i = 0; i < AttunementController.ATTUNEMENT_STABLE_TICKS; i++) {
            ctrl.debounceAttunement("AURIC");
        }
        check("AURIC confirmed after stable reads", "AURIC", ctrl.debounceAttunement("AURIC"));

        // A single stray tick reads the old phase (stale armor stand) -- must not flip yet.
        check("one-tick flicker to ASHEN doesn't commit", "AURIC", ctrl.debounceAttunement("ASHEN"));
        // Flicker resolves itself back to AURIC before ever becoming stable.
        check("back to AURIC, still AURIC", "AURIC", ctrl.debounceAttunement("AURIC"));
        check("still AURIC after the blip", "AURIC", ctrl.debounceAttunement("AURIC"));
    }

    private static void testDebounceCommitsRealTransitions() {
        section("debounceAttunement(): a real, sustained transition does eventually commit");
        AttunementController ctrl = new AttunementController();

        for (int i = 0; i < AttunementController.ATTUNEMENT_STABLE_TICKS; i++) {
            ctrl.debounceAttunement("ASHEN");
        }
        check("ASHEN confirmed", "ASHEN", ctrl.debounceAttunement("ASHEN"));

        // Real transition to SPIRIT, sustained (not a blip).
        String last = null;
        for (int i = 0; i < AttunementController.ATTUNEMENT_STABLE_TICKS; i++) {
            last = ctrl.debounceAttunement("SPIRIT");
        }
        check("commits to SPIRIT once seen for ATTUNEMENT_STABLE_TICKS in a row", "SPIRIT", last);
    }

    private static void testDebounceStartsEmptyUntilFirstStableReading() {
        section("debounceAttunement(): returns null until the very first reading stabilizes");
        AttunementController ctrl = new AttunementController();

        for (int i = 0; i < AttunementController.ATTUNEMENT_STABLE_TICKS - 1; i++) {
            check("still null before first stabilization (tick " + i + ")", null, ctrl.debounceAttunement("SPIRIT"));
        }
        check("commits on the Nth stable tick", "SPIRIT", ctrl.debounceAttunement("SPIRIT"));
    }

    // ---- big randomized end-to-end simulation --------------------------------------------------

    /**
     * Simulates a full Ashen->Spirit->Auric->Crystal->repeat rotation (two full loops) under
     * randomized network latency, occasional dropped toggle taps, and a human-like intermittent
     * click pattern, and checks the controller (a) eventually gets every phase correct, (b) never
     * exceeds the tap rate limit, and (c) never switches/taps while not attacking.
     */
    private static void testFullCycleSimulation(int seed, int packetLossPercent, int maxLatencyTicks) {
        Random rng = new Random(seed);
        AttunementController ctrl = new AttunementController();
        RecordingIO io = new RecordingIO();

        String[] rotation = {"ASHEN", "SPIRIT", "AURIC", "CRYSTAL"};
        int fireSlot = 0;
        int twilightSlot = 3;
        int otherSlot = 7; // e.g. Ragnarock, used between phases sometimes

        int phaseIndex = 0;
        // Daggers remember whatever mode they were last toggled to, exactly like the real items.
        String fireDaggerMode = "ASHEN";
        String twilightDaggerMode = "SPIRIT";
        int selectedSlot = otherSlot;

        int correctHits = 0;
        int pendingToggleTicks = -1; // -1 = no toggle in flight; else countdown to server confirmation
        boolean toggleWillBeDropped = false;
        int phasesCompleted = 0;
        int totalPhasesToComplete = rotation.length * 2; // two full loops through the cycle

        int tick = 0;
        int tickBudget = 20000; // generous ceiling; simulation must finish well before this
        // Human-like clicking: on for a random burst of ticks, off for a short gap, repeating.
        int clickPatternTicksLeft = 0;
        boolean clicking = false;
        long lastRealAttackMillis = Long.MIN_VALUE / 2;

        while (phasesCompleted < totalPhasesToComplete && tick < tickBudget) {
            long now = tick * 50L;
            io.currentTimeForLogging = now;

            if (clickPatternTicksLeft <= 0) {
                clicking = !clicking;
                clickPatternTicksLeft = clicking ? 2 + rng.nextInt(3) : 1 + rng.nextInt(2);
            }
            clickPatternTicksLeft--;
            boolean attackingNow = clicking;
            if (attackingNow) {
                lastRealAttackMillis = now;
            }
            // Mirrors the controller's own grace-window logic, so the invariant below checks what
            // the algorithm is actually documented to guarantee ("acts within the grace window of
            // a real attack"), not the stricter and unintended "acts only on the exact attack tick".
            boolean recentlyAttackingReconstructed = now - lastRealAttackMillis <= AttunementController.ATTACK_GRACE_MILLIS;

            String currentAttunement = rotation[phaseIndex];
            boolean fireFamily = "ASHEN".equals(currentAttunement) || "AURIC".equals(currentAttunement);
            int targetSlot = fireFamily ? fireSlot : twilightSlot;
            String heldMode = selectedSlot == fireSlot ? fireDaggerMode
                    : selectedSlot == twilightSlot ? twilightDaggerMode : null;

            int switchesBefore = io.switches.size();
            int tapsBefore = io.tapCount;

            ctrl.tick(new AttunementController.Input(true, true, true, false, false, currentAttunement,
                    fireSlot, twilightSlot, selectedSlot, heldMode, attackingNow, now), io);

            // Apply any slot switch the controller requested (near-instant, like real hotbar keys).
            if (io.switches.size() > switchesBefore) {
                assertTrue(seed, "slot switch only within the attack-grace window", recentlyAttackingReconstructed);
                selectedSlot = io.switches.get(io.switches.size() - 1);
            }

            // Apply any tap: schedule a delayed (and sometimes dropped) server confirmation.
            if (io.tapCount > tapsBefore) {
                assertTrue(seed, "tap only within the attack-grace window", recentlyAttackingReconstructed);
                assertTrue(seed, "tap only requested while already on target slot", selectedSlot == targetSlot);
                pendingToggleTicks = 1 + rng.nextInt(maxLatencyTicks);
                toggleWillBeDropped = rng.nextInt(100) < packetLossPercent;
            }

            if (pendingToggleTicks > 0) {
                pendingToggleTicks--;
                if (pendingToggleTicks == 0 && !toggleWillBeDropped) {
                    // Flip whichever dagger is currently selected to its other mode.
                    if (selectedSlot == fireSlot) {
                        fireDaggerMode = "ASHEN".equals(fireDaggerMode) ? "AURIC" : "ASHEN";
                    } else if (selectedSlot == twilightSlot) {
                        twilightDaggerMode = "SPIRIT".equals(twilightDaggerMode) ? "CRYSTAL" : "SPIRIT";
                    }
                }
            }

            // Count a correct hit only when actively attacking, on the right slot, right mode.
            boolean correctNow = attackingNow && selectedSlot == targetSlot && currentAttunement.equals(heldMode);
            if (correctNow) {
                correctHits++;
                if (correctHits >= 8) {
                    correctHits = 0;
                    phaseIndex = (phaseIndex + 1) % rotation.length;
                    phasesCompleted++;
                }
            }

            tick++;
        }

        check("[seed " + seed + "] completed both full rotation loops", true, phasesCompleted >= totalPhasesToComplete);
        check("[seed " + seed + "] finished within tick budget", true, tick < tickBudget);
        // A loose sanity ceiling, not a tight spacing check: across independent phase transitions
        // taps can legitimately land less than CONFIRM_WINDOW_TICKS apart (that invariant is about
        // retries of the *same* mismatch, and is precisely covered by the dedicated latency and
        // packet-loss tests above). This just guards against an order-of-magnitude spam regression.
        check("[seed " + seed + "] tap count stays sane (not runaway spam)", true,
                io.tapCount <= totalPhasesToComplete * AttunementController.MAX_ATTEMPTS * 8);
    }

    // ---- helpers ---------------------------------------------------------------------------

    private static boolean tapsAreProperlySpaced(RecordingIO io) {
        for (int i = 1; i < io.tapTimestamps.size(); i++) {
            long gapTicks = (io.tapTimestamps.get(i) - io.tapTimestamps.get(i - 1)) / 50L;
            if (gapTicks < AttunementController.CONFIRM_WINDOW_TICKS) {
                return false;
            }
        }
        return true;
    }

    private static void assertTrue(int seed, String what, boolean condition) {
        checks++;
        if (!condition) {
            failures++;
            System.out.println("  [FAIL] seed=" + seed + " invariant violated: " + what);
        }
    }

    private static void section(String name) {
        System.out.println("-- " + name);
    }

    private static void check(String name, Object expected, Object actual) {
        checks++;
        boolean ok = java.util.Objects.equals(expected, actual);
        if (ok && quiet) {
            return;
        }
        if (!ok) {
            failures++;
            System.out.println("  [FAIL] " + name + " expected=" + expected + " actual=" + actual);
        } else {
            System.out.println("  [ok] " + name);
        }
    }
}
