package io.softa.starter.seed.service.impl;

import org.springframework.stereotype.Service;

import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.seed.entity.SeedLoadBatch;
import io.softa.starter.seed.service.SeedLoadBatchService;

/**
 * SeedLoadBatch Model Service Implementation
 */
@Service
public class SeedLoadBatchServiceImpl extends EntityServiceImpl<SeedLoadBatch, Long> implements SeedLoadBatchService {
}
