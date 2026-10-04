package dev.mcmateriallist.core.ui;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MaterialRowLayoutTest {
    @Test void reservesDistinctHeadItemCountsAndRightActions() {
        var row = MaterialRowLayout.fit(500, 30, 40, 50, 44).orElseThrow();
        assertEquals(276, row.totalX());
        assertEquals(314, row.missingX());
        assertEquals(362, row.availableX());
        assertEquals(420, row.noticeX());
        assertEquals(434, row.doneX());
        assertEquals(456, row.ignoreX());
        assertEquals(224, row.nameWidth());
        assertTrue(44 + row.nameWidth() < row.totalX());
        assertTrue(row.availableX() + 50 < row.noticeX());
        assertTrue(row.doneX() + 20 < row.ignoreX());
    }
    @Test void sacrificesNameSpaceBeforeActionHitAreasOnNarrowRows() {
        var row = MaterialRowLayout.fit(280, 30, 40, 50, 44).orElseThrow();
        assertEquals(4, row.nameWidth());
        assertEquals(214, row.doneX());
        assertEquals(236, row.ignoreX());
        assertTrue(MaterialRowLayout.fit(260, 30, 40, 50, 44).isEmpty());
    }
    @Test void oversizedCountsDoNotOverflowIntoControls() {
        assertTrue(MaterialRowLayout.fit(400, Integer.MAX_VALUE, 40, 50, 44).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> MaterialRowLayout.fit(400, -1, 40, 50, 44));
    }
}
