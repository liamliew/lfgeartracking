package com.lfcreative.geartracker.asset

import com.lfcreative.geartracker.asset.create.Draft
import com.lfcreative.geartracker.asset.create.NamePattern
import com.lfcreative.geartracker.asset.create.Remembered
import com.lfcreative.geartracker.asset.create.Step
import com.lfcreative.geartracker.asset.create.TagInput
import com.lfcreative.geartracker.asset.create.TagMode
import com.lfcreative.geartracker.asset.create.WizardLogic
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private val plain = CategoryInfo("c1", "Mics")
private val withRequired = CategoryInfo("c2", "Lenses", fields = listOf(FieldDef("mount", "Mount", required = true), FieldDef("note", "Note")))

class NamePatternTest {
    @Test fun `placeholder is replaced by the counter`() {
        assertEquals("SM58 #1", NamePattern.render("SM58 #{n}", 1))
        assertEquals("SM58 #12", NamePattern.render("SM58 #{n}", 12))
        assertEquals("Hard case", NamePattern.render("Hard case", 5))
        assertEquals("A 3 B 3", NamePattern.render("A {n} B {n}", 3))
    }

    @Test fun `detects patterns`() {
        assertTrue(NamePattern.isPattern("Mic {n}"))
        assertFalse(NamePattern.isPattern("Mic"))
    }
}

class WizardStepsTest {
    @Test fun `full flow skips FIELDS unless the category has required fields`() {
        assertEquals(listOf(Step.TAG, Step.TEMPLATE, Step.IDENTITY, Step.LOCATION, Step.CONFIRM), WizardLogic.steps(plain, false))
        assertEquals(listOf(Step.TAG, Step.TEMPLATE, Step.IDENTITY, Step.LOCATION, Step.CONFIRM), WizardLogic.steps(null, false))
        assertEquals(
            listOf(Step.TAG, Step.TEMPLATE, Step.IDENTITY, Step.FIELDS, Step.LOCATION, Step.CONFIRM),
            WizardLogic.steps(withRequired, false),
        )
    }

    @Test fun `same-as-last is the fast path tag, serial, create`() {
        assertEquals(listOf(Step.TAG, Step.IDENTITY, Step.CONFIRM), WizardLogic.steps(withRequired, true))
    }

    @Test fun `next and previous walk the list and clamp at the ends`() {
        assertEquals(Step.TEMPLATE, WizardLogic.next(Step.TAG, plain, false))
        assertEquals(Step.CONFIRM, WizardLogic.next(Step.LOCATION, plain, false))
        assertEquals(Step.CONFIRM, WizardLogic.next(Step.CONFIRM, plain, false))
        assertEquals(Step.TAG, WizardLogic.previous(Step.TAG, plain, false))
        assertEquals(Step.IDENTITY, WizardLogic.previous(Step.FIELDS, withRequired, false) )
        assertEquals(Step.IDENTITY, WizardLogic.previous(Step.CONFIRM, plain, true))
    }
}

class WizardValidationTest {
    private val full = Draft(
        tag = TagInput("110164", TagMode.BARCODE), categoryId = "c2", namePattern = "Lens {n}",
        custom = mapOf("mount" to "E"), locationId = "l1",
    )

    @Test fun `each step explains what is missing`() {
        assertEquals("Scan a barcode, tap an NFC tag, or choose Generate code", WizardLogic.validate(Step.TAG, Draft(), null))
        assertNull(WizardLogic.validate(Step.TAG, Draft(tag = TagInput.Generate), null))
        assertEquals("Enter a name", WizardLogic.validate(Step.IDENTITY, Draft(), null))
        assertEquals("Mount is required", WizardLogic.validate(Step.FIELDS, full.copy(custom = emptyMap()), withRequired))
        assertEquals("Mount is required", WizardLogic.validate(Step.FIELDS, full.copy(custom = mapOf("mount" to "  ")), withRequired))
        assertEquals("Scan the location where it lives", WizardLogic.validate(Step.LOCATION, full.copy(locationId = null), withRequired))
    }

    @Test fun `confirm needs every earlier step to be valid`() {
        assertNull(WizardLogic.validate(Step.CONFIRM, full, withRequired))
        assertEquals("Mount is required", WizardLogic.validate(Step.CONFIRM, full.copy(custom = emptyMap()), withRequired))
        assertEquals("Enter a name", WizardLogic.validate(Step.CONFIRM, full.copy(namePattern = ""), withRequired))
    }
}

class SameAsLastTest {
    private val created = Draft(
        tag = TagInput("110164", TagMode.BARCODE), categoryId = "c1", templateId = "t1", namePattern = "SM58 #{n}", number = 3,
        serial = "SN-9", manufacturer = "Shure", model = "SM58", custom = mapOf("k" to "v"), locationId = "l1", locationLabel = "Bin 1A",
    )

    @Test fun `create and next keeps everything except tag and serial, and advances the number`() {
        val next = WizardLogic.afterCreate(created)
        assertNull(next.tag); assertEquals("", next.serial)
        assertEquals(4, next.number)
        assertEquals("SM58 #4", next.name)
        assertEquals("Shure", next.manufacturer); assertEquals("l1", next.locationId); assertEquals(mapOf("k" to "v"), next.custom)
    }

    @Test fun `same as last restores the previous asset's choices`() {
        val remembered = WizardLogic.remember(created, null)
        val draft = WizardLogic.sameAsLast(remembered)
        assertNull(draft.tag); assertEquals("", draft.serial)
        assertEquals("SM58 #4", draft.name)
        assertEquals("c1", draft.categoryId); assertEquals("Bin 1A", draft.locationLabel); assertEquals("Shure", draft.manufacturer)
    }

    @Test fun `recent templates are most-recent-first, unique and capped at five`() {
        var r: Remembered? = null
        listOf("a", "b", "c", "a", "d", "e", "f").forEach { r = WizardLogic.remember(created.copy(templateId = it), r) }
        assertEquals(listOf("f", "e", "d", "a", "c"), r!!.recentTemplateIds)
    }
}

class CreateRequestTest {
    private val draft = Draft(
        tag = TagInput("04-AA-BB-CC", TagMode.NFC), categoryId = "c1", namePattern = "Mic {n}", number = 2,
        serial = " SN1 ", manufacturer = "Shure", isContainer = false, custom = mapOf("a" to "1", "blank" to " "), locationId = "l1",
    )

    @Test fun `request matches the create_asset contract`() {
        val j = WizardLogic.request(draft, "11111111-1111-4111-8111-111111111111", "2026-11-01T10:00:00Z", "dev1")
        assertEquals("11111111-1111-4111-8111-111111111111", j["id"]!!.jsonPrimitive.content)
        assertEquals("Mic 2", j["name"]!!.jsonPrimitive.content)
        assertEquals("SN1", j["serial_number"]!!.jsonPrimitive.content)
        assertEquals("nfc", j["tag"]!!.jsonObject["kind"]!!.jsonPrimitive.content)
        assertEquals("04-AA-BB-CC", j["tag"]!!.jsonObject["value"]!!.jsonPrimitive.content)
        assertEquals("false", j["is_container"]!!.jsonPrimitive.content)
        assertEquals("l1", j["location_id"]!!.jsonPrimitive.content)
        assertEquals(setOf("a"), j["custom"]!!.jsonObject.keys)
        assertEquals("dev1", j["device_id"]!!.jsonPrimitive.content)
    }

    @Test fun `generate code sends no tag and empty optional fields are omitted`() {
        val j = WizardLogic.request(Draft(tag = TagInput.Generate, namePattern = "Case"), "id", "2026-11-01T10:00:00Z")
        assertFalse(j.containsKey("tag")); assertFalse(j.containsKey("serial_number")); assertFalse(j.containsKey("custom"))
        assertFalse(j.containsKey("location_id")); assertFalse(j.containsKey("manufacturer"))
    }
}
