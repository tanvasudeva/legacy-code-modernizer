package com.legacy.modernizer.statemachine;

import com.legacy.modernizer.model.JobStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static com.legacy.modernizer.model.JobStatus.*;
import static org.junit.jupiter.api.Assertions.*;

class JobStateMachineTest {

    // -------------------------------------------------------------------------
    // Valid happy-path transitions
    // -------------------------------------------------------------------------

    @Test void pendingToAnalyzing()  { assertValid(PENDING,    ANALYZING);  }
    @Test void analyzingToPlanning() { assertValid(ANALYZING,  PLANNING);   }
    @Test void planningToGenerating(){ assertValid(PLANNING,   GENERATING); }
    @Test void generatingToDone()    { assertValid(GENERATING, DONE);       }

    // -------------------------------------------------------------------------
    // Valid error transitions — any non-terminal state → FAILED
    // -------------------------------------------------------------------------

    @Test void pendingToFailed()    { assertValid(PENDING,    FAILED); }
    @Test void analyzingToFailed()  { assertValid(ANALYZING,  FAILED); }
    @Test void planningToFailed()   { assertValid(PLANNING,   FAILED); }
    @Test void generatingToFailed() { assertValid(GENERATING, FAILED); }

    // -------------------------------------------------------------------------
    // Valid retry transition
    // -------------------------------------------------------------------------

    @Test void failedToPending() { assertValid(FAILED, PENDING); }

    // -------------------------------------------------------------------------
    // Invalid transitions
    // -------------------------------------------------------------------------

    @Test void pendingSkipsToPlanningIsInvalid()   { assertInvalid(PENDING,   PLANNING);   }
    @Test void pendingSkipsToGeneratingIsInvalid() { assertInvalid(PENDING,   GENERATING); }
    @Test void pendingSkipsToDoneIsInvalid()       { assertInvalid(PENDING,   DONE);       }
    @Test void analyzingSkipsToDoneIsInvalid()     { assertInvalid(ANALYZING, DONE);       }
    @Test void doneCannotAdvance()                 { assertInvalid(DONE,      ANALYZING);  }
    @Test void doneCannotFail()                    { assertInvalid(DONE,      FAILED);     }
    @Test void failedCannotGoToDone()              { assertInvalid(FAILED,    DONE);       }
    @Test void failedCannotAdvanceForward()        { assertInvalid(FAILED,    ANALYZING);  }

    @ParameterizedTest
    @EnumSource(value = JobStatus.class, names = {"ANALYZING", "PLANNING", "GENERATING", "DONE", "FAILED"})
    void noStateCanTransitionBackToPendingExceptFailed(JobStatus from) {
        if (from == FAILED) return; // retry is valid
        assertInvalid(from, PENDING);
    }

    // -------------------------------------------------------------------------
    // nextState — happy path sequence
    // -------------------------------------------------------------------------

    @Test
    void nextStateSequenceIsCorrect() {
        assertEquals(ANALYZING,  JobStateMachine.nextState(PENDING));
        assertEquals(PLANNING,   JobStateMachine.nextState(ANALYZING));
        assertEquals(GENERATING, JobStateMachine.nextState(PLANNING));
        assertEquals(DONE,       JobStateMachine.nextState(GENERATING));
    }

    @Test void nextStateFromDoneThrows()   { assertThrows(IllegalStateException.class, () -> JobStateMachine.nextState(DONE));   }
    @Test void nextStateFromFailedThrows() { assertThrows(IllegalStateException.class, () -> JobStateMachine.nextState(FAILED)); }

    // -------------------------------------------------------------------------
    // isTerminal
    // -------------------------------------------------------------------------

    @Test void doneIsTerminal()       { assertTrue(JobStateMachine.isTerminal(DONE));    }
    @Test void failedIsTerminal()     { assertTrue(JobStateMachine.isTerminal(FAILED));  }
    @Test void pendingIsNotTerminal() { assertFalse(JobStateMachine.isTerminal(PENDING));}
    @Test void analyzingNotTerminal() { assertFalse(JobStateMachine.isTerminal(ANALYZING)); }

    // -------------------------------------------------------------------------

    private static void assertValid(JobStatus from, JobStatus to) {
        assertDoesNotThrow(() -> JobStateMachine.validate(from, to),
                from + " → " + to + " should be valid");
    }

    private static void assertInvalid(JobStatus from, JobStatus to) {
        assertThrows(IllegalStateException.class, () -> JobStateMachine.validate(from, to),
                from + " → " + to + " should be invalid");
    }
}
