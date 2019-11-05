package esw.ocs.scripts.examples.testData

import esw.ocs.dsl.core.reusableScript

// ESW-134: Reuse code by ability to import logic from one script into another
val OperationsAndDiagModeHandlers = reusableScript {
    handleDiagnosticMode { startTime, hint ->
        // do some actions to go to diagnostic mode based on hint
        diagnosticModeForSequencer(
            "testSequencerId6", "testObservingMode6",
            startTime,
            hint
        )
    }

    handleOperationsMode {
        // do some actions to go to operations mode
        operationsModeForSequencer("testSequencerId6", "testObservingMode6")
    }
}