package io.softa.starter.metadata.sequence;

/**
 * What {@code {companyAbbr}} will render for a sequence, for a template preview.
 *
 * @param applies      whether the numbered model has a company at all; when false the token always
 *                     renders empty, whatever company abbreviations exist
 * @param companyName  the sample company's name, null when no company has an abbreviation
 * @param abbreviation the sample company's abbreviation, null when no company has one
 */
public record CompanyAbbrPreview(boolean applies, String companyName, String abbreviation) {

    static CompanyAbbrPreview notApplicable() {
        return new CompanyAbbrPreview(false, null, null);
    }
}
