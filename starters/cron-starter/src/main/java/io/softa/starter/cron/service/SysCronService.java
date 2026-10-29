package io.softa.starter.cron.service;

import java.util.List;

import io.softa.framework.orm.service.EntityService;
import io.softa.starter.cron.entity.SysCron;

/**
 * SysCron Model Service Interface
 */
public interface SysCronService extends EntityService<SysCron, Long> {

    /**
     * Execute the specified cron job immediately
     *
     * @param cronId Cron job ID
     */
    void executeNow(Long cronId);

    /**
     * Execute the specified multiple cron jobs immediately
     *
     * @param cronIds Cron job IDs
     */
    void executeMultipleNow(List<Long> cronIds);

}