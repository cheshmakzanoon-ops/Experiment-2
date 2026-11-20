package com.artflow.studio.data.export

import com.artflow.studio.core.export.ProcreateReadBudget
import java.io.File

/** Shares bounded ZIP-directory inspection while retaining Procreate's opening reservation. */
internal object ProcreateZipIndex {
    fun check(
        file: File,
        budget: ProcreateReadBudget,
        maximumEntries: Int,
    ) {
        ZipDirectoryPreflight(
            maximumEntries = maximumEntries,
            damagedMessage = "The Procreate archive index is damaged",
            entryLimitMessage = "This Procreate document has too many entries",
            checkActive = budget::ensureActive,
            admit = budget::reserveArchiveIndex,
        ).check(file)
    }
}
