package io.softa.starter.seed.service.impl;

import org.springframework.stereotype.Service;

import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.seed.entity.SeedLoadRecord;
import io.softa.starter.seed.service.SeedLoadRecordService;

/**
 * SeedLoadRecord Model Service Implementation
 */
@Service
public class SeedLoadRecordServiceImpl extends EntityServiceImpl<SeedLoadRecord, Long> implements SeedLoadRecordService {
}
