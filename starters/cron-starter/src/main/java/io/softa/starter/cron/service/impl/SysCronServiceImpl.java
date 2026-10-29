package io.softa.starter.cron.service.impl;

import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.softa.framework.base.exception.IllegalArgumentException;
import io.softa.framework.orm.compute.CronUtils;
import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.cron.entity.SysCron;
import io.softa.starter.cron.scheduler.CronScheduler;
import io.softa.starter.cron.service.SysCronService;

/**
 * SysCron Model Service Implementation
 */
@Service
public class SysCronServiceImpl extends EntityServiceImpl<SysCron, Long> implements SysCronService {

    @Autowired
    @Lazy
    private CronScheduler cronScheduler;

    /**
     * Execute the specified cron job immediately
     *
     * @param sysCron Cron job object
     */
    private void executeCron(SysCron sysCron) {
        CronUtils.getCron(sysCron.getName(), sysCron.getCronExpression());
        cronScheduler.sendToMessageQueue(sysCron);
    }

    /**
     * Execute the specified cron job immediately
     *
     * @param cronId Cron job ID
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void executeNow(Long cronId) {
        SysCron sysCron = this.getById(cronId)
                .orElseThrow(() -> new IllegalArgumentException("The cron job with ID `{0}` does not exist", cronId));
        this.executeCron(sysCron);
    }

    /**
     * Execute the specified multiple cron jobs immediately
     *
     * @param cronIds Cron job IDs
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void executeMultipleNow(List<Long> cronIds) {
        List<SysCron> sysCronList = this.getByIds(cronIds);
        for (SysCron sysCron : sysCronList) {
            this.executeCron(sysCron);
        }
    }

}