package io.softa.starter.metadata.sequence;

/**
 * What a template preview needs from the server, for a sequence whose form may hold unsaved edits.
 *
 * <p>The client renders the template itself. It cannot tell on its own which number comes next: that
 * depends on the stored counter and on whether the reset period has turned, and a null last-reset
 * key differs from an empty one. So the server answers that, and the client applies it to the step
 * and start value on the form: {@code continuesCounter ? currentValue + step : startValue}.
 *
 * @param continuesCounter whether the next allocation continues the stored counter (false: it starts
 *                         over at the start value — a fresh counter, or a new reset period)
 * @param currentValue     the stored counter, 0 when it has never been used
 * @param companyAbbr      what {@code {companyAbbr}} renders for this sequence
 */
public record SequenceTemplatePreview(boolean continuesCounter, long currentValue, CompanyAbbrPreview companyAbbr) {
}
