package net.javacrumbs.cloffle;

import com.oracle.truffle.api.debug.SuspendAnchor;
import com.oracle.truffle.api.debug.SuspendedEvent;

/**
 * Shared stepping policies for debugger/DAP tests.
 */
final class DebugStepPolicies {

    private DebugStepPolicies() {
    }

    /**
     * If we are stopped at callee-entry BEFORE and user locals are still unreadable, step once more to the
     * first user-meaningful stop. Returns true when the caller should return immediately.
     */
    static boolean maybeAdvancePastEntryBefore(
            SuspendedEvent event,
            boolean[] autoAdvanced,
            boolean hasUnreadableLocals,
            Runnable requeueCurrentHandler) {
        if (!autoAdvanced[0]
                && hasUnreadableLocals
                && event.getSuspendAnchor() == SuspendAnchor.BEFORE) {
            autoAdvanced[0] = true;
            requeueCurrentHandler.run();
            event.prepareStepInto(1);
            return true;
        }
        return false;
    }
}

