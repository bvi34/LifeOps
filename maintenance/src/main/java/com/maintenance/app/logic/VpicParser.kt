package com.maintenance.app.logic

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException

/**
 * Pure parsing of NHTSA vPIC's `DecodeVinValues` response — 154 flat fields — down to the dozen
 * this app has any use for. No network and no Android; the HTTP hop lives in `data/net/VpicClient`.
 *
 * Two habits, both about not being clever:
 *
 * - **Empty is absent.** vPIC returns `""` for everything it doesn't know, in every one of those
 *   154 fields. Turning those into nulls here means nothing downstream has to remember that an
 *   empty string is not an answer.
 * - **A complaint is not a failure.** vPIC decodes a VIN whose check digit doesn't compute and tells
 *   you so in `ErrorText`; that text is carried through as a note rather than thrown, because plenty
 *   of real vehicles built outside North America have exactly that property, and this app already
 *   takes the position that a bad check digit is worth mentioning and is not a refusal.
 */
object VpicParser {

    /** Parsed, or null when the payload is not a vPIC response at all. */
    fun parse(json: String): VehicleFacts? {
        val response = try {
            Gson().fromJson(json, VpicResponse::class.java)
        } catch (_: JsonSyntaxException) {
            return null
        } ?: return null

        val row = response.Results?.firstOrNull() ?: return null

        return VehicleFacts(
            make = row.Make.clean()?.titleCase(),
            model = row.Model.clean(),
            year = row.ModelYear.clean()?.toIntOrNull(),
            trim = row.Trim.clean(),
            bodyClass = row.BodyClass.clean(),
            driveType = row.DriveType.clean(),
            engineCylinders = row.EngineCylinders.clean()?.toIntOrNull(),
            displacementLitres = row.DisplacementL.clean()?.toDoubleOrNull(),
            fuel = row.FuelTypePrimary.clean(),
            transmission = row.TransmissionStyle.clean(),
            manufacturer = row.Manufacturer.clean(),
            plant = row.PlantCity.clean()?.titleCase(),
            note = row.ErrorText.clean()
                // vPIC says "0 - VIN decoded clean. Check Digit (9th position) is correct" when all
                // is well; that is not a note anybody needs to read.
                ?.takeIf { row.ErrorCode.clean().orEmpty().split(",").any { code -> code.trim() != "0" } }
                ?.let { tidyError(it) }
        ).takeIf { !it.isEmpty }
    }

    /**
     * vPIC's error text arrives as a numbered, comma-joined list — "1 - Check Digit (9th position)
     * does not calculate properly". The numbers are for machines.
     */
    private fun tidyError(text: String): String =
        text.split(";")
            .map { it.trim().substringAfter(" - ", it.trim()) }
            .filter { it.isNotBlank() }
            .joinToString("; ")

    private fun String?.clean(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

    /** Only the fields we read; Gson ignores the other 140. */
    private data class VpicResponse(val Results: List<VpicRow>?)

    @Suppress("PropertyName")
    private data class VpicRow(
        val Make: String? = null,
        val Model: String? = null,
        val ModelYear: String? = null,
        val Trim: String? = null,
        val BodyClass: String? = null,
        val DriveType: String? = null,
        val EngineCylinders: String? = null,
        val DisplacementL: String? = null,
        val FuelTypePrimary: String? = null,
        val TransmissionStyle: String? = null,
        val Manufacturer: String? = null,
        val PlantCity: String? = null,
        val ErrorCode: String? = null,
        val ErrorText: String? = null
    )
}
