package dev.mcmateriallist.core.ui;

import dev.mcmateriallist.core.work.Progress;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class WorkProgressPresentationTest {
    @Test void incompleteFractionIsRedWithBothTranslatedMaterialAndRegionPrefixes() {
        for (String prefix : new String[]{"Progress: ", "Regions built: ", "進捗: ", "完了した領域: "}) {
            assertEquals(prefix + "\u00a7c4 / 19 (21%)\u00a7r", WorkProgressPresentation.style(prefix + "4 / 19 (21%)", new Progress(4, 19, 21)));
        }
    }
    @Test void completeFractionRetainsTheTranslatedText() {
        for (String prefix : new String[]{"Progress: ", "Regions built: ", "進捗: ", "完了した領域: "}) {
            assertEquals(prefix + "19 / 19 (100%)", WorkProgressPresentation.style(prefix + "19 / 19 (100%)", new Progress(19, 19, 100)));
        }
    }
    @Test void emptyDatasetAndOtherNumbersKeepTheirOriginalStyling() {
        assertEquals("Progress: 0 / 0 (0%)", WorkProgressPresentation.style("Progress: 0 / 0 (0%)", new Progress(0, 0, 0)));
        assertEquals("Progress 4: \u00a7c4 / 19 (21%)\u00a7r; 19 items", WorkProgressPresentation.style("Progress 4: 4 / 19 (21%); 19 items", new Progress(4, 19, 21)));
    }
}
