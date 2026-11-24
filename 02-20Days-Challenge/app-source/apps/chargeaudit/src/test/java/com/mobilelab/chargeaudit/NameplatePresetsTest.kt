package com.mobilelab.chargeaudit

import com.mobilelab.chargeaudit.model.NameplatePresets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NameplatePresetsTest {
    private val cp = NameplatePresets.huaweiCp11qc

    @Test fun cp11qcUsesTheOutputRatingAndNotCellCapacityForItsReference() {
        assertEquals("华为 CP11QC", cp.model)
        assertEquals(10_000.0, cp.cellTypicalMah!!, 0.0)
        assertEquals(9_500.0, cp.cellRatedMah!!, 0.0)
        assertEquals(34.49, cp.ratedEnergyWh!!, 0.0)
        assertEquals(6_200.0, cp.ratedOutputMah!!, 0.0)
        assertEquals(5.0, cp.ratedOutputV!!, 0.0)
        assertEquals(2.0, cp.ratedOutputCurrentA!!, 0.0)
        assertEquals("5 V / 9 V ⎓ 2 A（最大）", cp.ratedInput)
        assertEquals(cp.ratedInput, cp.ratedOutput)
        assertEquals(31.0 / 34.49, cp.ratedOutputToLabelRatio!!, 1e-12)
        assertNull(cp.phoneCapacityMah)
        assertNull(cp.phoneNominalV)
        assertEquals("", cp.testerModel)
    }

    @Test fun separatePhoneAndMeterFieldsDoNotChangeBankIdentity() {
        val plate = cp.copy(phoneCapacityMah = 9_000.0, phoneNominalV = 3.8, testerModel = "meter")
        assertTrue(NameplatePresets.matchesHuaweiCp11qc(plate))
        assertEquals(plate, NameplatePresets.updateBank(plate, cp.model, 34.49, 6_200.0, 5.0))
    }

    @Test fun changingBankClearsInapplicableHardwareRatingsAndDoesNotMutateOriginal() {
        val custom = NameplatePresets.updateBank(cp, "Other", 45.0, null, null)
        assertEquals(45.0, custom.ratedEnergyWh!!, 0.0)
        assertNull(custom.cellTypicalMah)
        assertNull(custom.cellRatedMah)
        assertNull(custom.ratedOutputCurrentA)
        assertNull(custom.ratedInput)
        assertNull(custom.ratedOutput)
        assertFalse(NameplatePresets.matchesHuaweiCp11qc(custom))
        assertTrue(NameplatePresets.matchesHuaweiCp11qc(cp))
    }
}
