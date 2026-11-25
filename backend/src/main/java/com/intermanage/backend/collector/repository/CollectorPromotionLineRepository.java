package com.intermanage.backend.collector.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.intermanage.backend.collector.entity.CollectorPromotionLine;

public interface CollectorPromotionLineRepository extends JpaRepository<CollectorPromotionLine, UUID> {

    List<CollectorPromotionLine> findByPromotionIdOrderByPosition(UUID promotionId);
}
