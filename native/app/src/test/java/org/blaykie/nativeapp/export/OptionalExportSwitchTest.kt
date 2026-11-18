package org.blaykie.nativeapp.export

import org.junit.Assert.*
import org.junit.Test

class OptionalExportSwitchTest {
    @Test fun disablingOnlyCancelsPendingOptionalWork() {
        for (phase in listOf(ExportPhase.PREPARING, ExportPhase.FALLBACK, ExportPhase.CHOOSE, ExportPhase.WAITING)) {
            assertTrue(shouldCancelOptionalExport(true, phase))
            assertFalse(shouldCancelOptionalExport(false, phase))
        }
        for (phase in listOf(ExportPhase.IDLE, ExportPhase.WRITING, ExportPhase.DONE)) {
            assertFalse(shouldCancelOptionalExport(true, phase))
        }
    }
}
