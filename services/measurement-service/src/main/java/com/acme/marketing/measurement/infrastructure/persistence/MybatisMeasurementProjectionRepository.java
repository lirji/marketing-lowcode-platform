package com.acme.marketing.measurement.infrastructure.persistence;

import com.acme.marketing.measurement.application.MeasurementProjectionRepository;
import com.acme.marketing.measurement.infrastructure.persistence.mapper.MeasurementMapper;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

/** 基于 MyBatis 的看板投影仓储适配器。 */
@Repository
public class MybatisMeasurementProjectionRepository implements MeasurementProjectionRepository {
    private final MeasurementMapper mapper;

    public MybatisMeasurementProjectionRepository(MeasurementMapper mapper) {
        this.mapper = mapper;
    }

    @Override public Optional<StoredDelta> findDelta(String tenantId, String deltaId) {
        return Optional.ofNullable(mapper.selectProjectionDelta(tenantId, deltaId));
    }
    @Override public Optional<String> findPayloadHashBySource(String topic, int partition, long offset) {
        return Optional.ofNullable(mapper.selectProjectionPayloadHashBySource(topic, partition, offset));
    }
    @Override public boolean trySaveDelta(DeltaWrite write) {
        try {
            return mapper.insertProjectionDelta(write) == 1;
        } catch (DuplicateKeyException duplicate) {
            return false;
        }
    }
    @Override public List<FactCount> sumCounts(String tenantId, String from, String to) {
        return mapper.selectProjectionCounts(tenantId, from, to);
    }
    @Override public Amounts sumAmounts(String tenantId, String from, String to) {
        return mapper.selectProjectionAmounts(tenantId, from, to);
    }
    @Override public List<SeriesBucket> sumSeries(String tenantId, String from, String to, boolean hourly) {
        return mapper.selectProjectionSeries(tenantId, from, to, hourly);
    }
}
