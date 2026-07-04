package io.softa.starter.studio.release.service.impl;

import org.springframework.stereotype.Service;

import io.softa.framework.orm.service.impl.EntityServiceImpl;
import io.softa.starter.studio.release.entity.DesignSnapshot;
import io.softa.starter.studio.release.service.DesignSnapshotService;

/**
 * DesignSnapshot Model Service Implementation.
 */
@Service
public class DesignSnapshotServiceImpl extends EntityServiceImpl<DesignSnapshot, Long> implements DesignSnapshotService {

}
