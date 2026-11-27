package com.intermanage.backend.collector.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.intermanage.backend.collector.entity.CollectorFileLine;

public interface CollectorFileLineRepository extends JpaRepository<CollectorFileLine, UUID> {
}
