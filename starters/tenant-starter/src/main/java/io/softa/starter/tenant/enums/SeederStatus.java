package io.softa.starter.tenant.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

import io.softa.framework.base.annotation.OptionSet;

/**
 * Per-seeder progress recorded in {@link io.softa.starter.tenant.entity.TenantSeedProgress}: a seeder is
 * either DONE for a tenant or FAILED terminally. Absence of a row = not started / in flight.
 */
@Getter
@AllArgsConstructor
@OptionSet
public enum SeederStatus {
    DONE("Done"),
    // Written by each seed consumer from its catch block (TenantProvisioningStatusService.markSeederFailed),
    // so the row names which seeder failed while the tenant itself only shows Initializing or Draft. Not
    // terminal: the consumer rethrows, MQ redelivers or Retry Setup runs it again, and a later success
    // overwrites this row with DONE.
    FAILED("Failed"),
    ;

    @JsonValue
    private final String status;

}
